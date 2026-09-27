package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.services.banner.BannerSessionStore
import ai.releva.sdk.types.response.BannerResponse
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages banner display in an Activity or Fragment.
 * Listens to BannerDisplayController and shows banners as popups, bars, flyouts, or static views.
 *
 * Static banners are automatically inserted relative to the fragment's content based on
 * displayStrategy: "afterbegin" (before content), "beforeend" (after content), "replace" (replaces content).
 *
 * Usage:
 * ```kotlin
 * val bannerDisplay = BannerDisplayManager(client, targetSelector = "#home-content")
 * bannerDisplay.attach(fragment)
 * ```
 */
class BannerDisplayManager(
    private val client: RelevaClient,
    private val targetSelector: String,
    private val onLinkTap: (String) -> Unit
) {
    private val displayedBanners = mutableMapOf<String, BannerResponse>()
    private val activeDialogs = mutableListOf<Dialog>()
    private var collectJob: Job? = null
    private var activity: AppCompatActivity? = null
    private var scope: CoroutineScope? = null

    // Retention across a host recreation: the ViewModel lives in the host's own store, and for
    // a Fragment host the observer below is what decides that a teardown is the host going away
    // rather than a navigation. Both are dropped in detach().
    private var retention: BannerRetentionViewModel? = null
    private var handOffLifecycle: Lifecycle? = null
    private var handOffObserver: LifecycleEventObserver? = null

    // View wrapping for static banners and bar overlays
    private var rootView: ViewGroup? = null              // The fragment/activity root view (untouched)
    private var outerWrapper: FrameLayout? = null        // FrameLayout for bar overlays (inside root)
    private var innerWrapper: LinearLayout? = null       // LinearLayout for static banner zones
    private var contentHolder: FrameLayout? = null       // Holds original children of root

    companion object {
        private const val TAG = "BannerDisplayManager"
        private const val BANNER_TAG_PREFIX = "releva_banner_"
        private const val RETENTION_KEY_PREFIX = "ai.releva.sdk.bannerRetention:"

        // What FrameLayout.layoutChildren substitutes for a child whose LayoutParams leave gravity
        // unspecified, which is how the bar's content wrapper was added before
        // `contentVerticalAlign` could place it. Naming it keeps the default path identical rather
        // than merely equivalent.
        private const val DEFAULT_CHILD_GRAVITY = Gravity.TOP or Gravity.START
    }

    /**
     * Attach to a Fragment. Will automatically clean up when the fragment's view is destroyed.
     */
    fun attach(fragment: Fragment) {
        val viewLifecycleOwner = fragment.viewLifecycleOwner
        scope = fragment.viewLifecycleOwner.lifecycleScope
        activity = fragment.activity as? AppCompatActivity
        rootView = fragment.view as? ViewGroup
        retention = retentionFor(fragment)

        viewLifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startCollecting()
                Lifecycle.Event.ON_PAUSE -> stopCollecting()
                Lifecycle.Event.ON_DESTROY -> detach()
                else -> {}
            }
        })
        activity?.let { handOverWhenHostIsDestroyed(it) }

        wrapChildren()
        restoreRetainedBanners()
    }

    /**
     * Attach to an Activity. Will automatically clean up when the activity is destroyed.
     */
    fun attach(activity: AppCompatActivity) {
        this.activity = activity
        scope = activity.lifecycleScope
        rootView = activity.window.decorView.findViewById(android.R.id.content)
        retention = retentionFor(activity)

        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startCollecting()
                Lifecycle.Event.ON_PAUSE -> stopCollecting()
                // The host itself is going away, so hand the banners over before detach()
                // takes them off the screen. A destroy that is a real finish rather than a
                // recreation clears the store, and the hand-off goes with it.
                Lifecycle.Event.ON_DESTROY -> {
                    handOver()
                    detach()
                }
                else -> {}
            }
        })

        wrapChildren()
        restoreRetainedBanners()
    }

    private fun retentionFor(owner: ViewModelStoreOwner): BannerRetentionViewModel =
        ViewModelProvider(owner)[
            RETENTION_KEY_PREFIX + targetSelector,
            BannerRetentionViewModel::class.java
        ]

    /**
     * Hands the banners over on the host *activity's* `ON_DESTROY` rather than on the fragment's
     * view lifecycle, which is the difference between a recreation and a navigation: a fragment
     * loses its view to both, but only the activity being destroyed means the host is going away
     * and taking everything on screen with it. A fragment put on the back stack detaches without
     * handing anything over, and [detach] takes this observer off with it, so a later
     * configuration change while the user is on another screen has nothing to hand over either.
     *
     * The activity's `ON_DESTROY` is dispatched before its FragmentManager tears the fragments
     * down, so the banners are still on screen — and still in [displayedBanners] — when it runs.
     */
    private fun handOverWhenHostIsDestroyed(host: AppCompatActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) handOver()
        }
        handOffLifecycle = host.lifecycle
        handOffObserver = observer
        host.lifecycle.addObserver(observer)
    }

    private fun handOver() {
        retention?.retain(displayedBanners.values.toList())
    }

    /**
     * Puts back what the host instance this one replaces had on screen. Deliberately not a new
     * display: the banner was marked shown and its impression tracked when it first rendered,
     * and a rotation is the same impression of the same banner, not a second one.
     */
    private fun restoreRetainedBanners() {
        val retained = retention?.take().orEmpty()
        if (retained.isEmpty()) return
        Log.d(TAG, "Restoring ${retained.size} banner(s) onto the recreated host")
        for (banner in retained) {
            displayedBanners[banner.token] = banner
            renderBanner(banner)
        }
    }

    /**
     * Wraps the root view's children inside a FrameLayout > LinearLayout structure,
     * without detaching the root view from its parent (safe for NavHostFragment).
     *
     * rootView (untouched — stays attached to NavHostFragment/Activity)
     *   └── outerWrapper (FrameLayout — for bar overlays)
     *         └── innerWrapper (LinearLayout vertical — for static banner zones)
     *               ├── [afterbegin banners]
     *               ├── contentHolder (FrameLayout, weight=1 — holds original children)
     *               └── [beforeend banners]
     */
    private fun wrapChildren() {
        val root = rootView ?: return
        val ctx = activity ?: return

        // Collect original children and their layout params
        val children = mutableListOf<Pair<View, ViewGroup.LayoutParams?>>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            children.add(child to child.layoutParams)
        }
        root.removeAllViews()

        // contentHolder: holds original children in a FrameLayout
        val holder = FrameLayout(ctx)
        for ((child, params) in children) {
            if (params != null) holder.addView(child, params) else holder.addView(child)
        }

        // innerWrapper: LinearLayout for static banner placement
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, getStatusBarHeight(ctx), 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        holder.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        inner.addView(holder)

        // outerWrapper: FrameLayout for bar overlays
        val outer = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        outer.addView(inner)

        root.addView(outer)

        outerWrapper = outer
        innerWrapper = inner
        contentHolder = holder
    }

    /**
     * Restores the original children back into the root view.
     */
    private fun unwrapChildren() {
        val root = rootView ?: return
        val holder = contentHolder ?: return

        removeAllStaticBannerViews()
        holder.visibility = View.VISIBLE

        // Move original children back to root
        val children = mutableListOf<Pair<View, ViewGroup.LayoutParams?>>()
        for (i in 0 until holder.childCount) {
            val child = holder.getChildAt(i)
            children.add(child to child.layoutParams)
        }
        holder.removeAllViews()
        root.removeAllViews()

        for ((child, params) in children) {
            if (params != null) root.addView(child, params) else root.addView(child)
        }

        outerWrapper = null
        innerWrapper = null
        contentHolder = null
    }

    private fun startCollecting() {
        if (collectJob != null) return
        collectJob = scope?.launch {
            BannerDisplayController.bannerFlow.collect { banner ->
                if (shouldDisplayBanner(banner)) {
                    displayedBanners[banner.token] = banner
                    showBanner(banner)
                }
            }
        }
    }

    private fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
    }

    /**
     * Dismiss all active popup/flyout dialogs.
     */
    fun dismissAll() {
        activeDialogs.toList().forEach { it.dismiss() }
    }

    private fun detach() {
        handOffObserver?.let { handOffLifecycle?.removeObserver(it) }
        handOffObserver = null
        handOffLifecycle = null
        retention = null
        stopCollecting()
        dismissAll()
        displayedBanners.clear()
        unwrapChildren()
        rootView = null
        activity = null
        scope = null
    }

    private fun shouldDisplayBanner(banner: BannerResponse): Boolean {
        if (banner.design == null) return false
        if (banner.displayType == "custom") return false
        if (banner.displayType == "static" && banner.cssSelector != targetSelector) return false
        return true
    }

    private fun showBanner(banner: BannerResponse) {
        Log.d(TAG, "Showing banner: ${banner.token}, type: ${banner.displayType}")
        renderBanner(banner)
        // This is the only place in the banner pipeline that knows a banner actually rendered,
        // as opposed to merely being emitted into BannerDisplayController — shouldDisplayBanner
        // above may have already filtered it out (no design, a custom displayType, or a static
        // banner whose cssSelector doesn't match this manager's targetSelector), and the emitter
        // itself may have found no attached collector or a full buffer. Marking here, rather than
        // at emission time, is what lets a banner that was dropped rather than shown be retried
        // on the next trigger instead of being suppressed for the rest of the session.
        BannerSessionStore.markShown(banner.token)
        trackImpression(banner)
    }

    /**
     * Builds the banner and puts it on screen. Shared by the first display and by a restore onto
     * a recreated host, so no display type can come back from a recreation differently from the
     * way it went up.
     */
    private fun renderBanner(banner: BannerResponse) {
        when (banner.displayType) {
            "popup" -> showPopupBanner(banner)
            "bar" -> showBarBanner(banner)
            "flyout" -> showFlyoutBanner(banner)
            "static" -> showStaticBanner(banner)
            else -> showStaticBanner(banner)
        }
    }

    /**
     * [contentView] as a [ScrollView]'s single child, wrapped so that an authored
     * `contentVerticalAlign` [gravity] can place it inside the card — and returned untouched when
     * there is none, which is how the scroll views below were built before these keys existed.
     *
     * The wrapper is what holds the key to what the contract says it is: observable only when the
     * card is taller than its content. `ScrollView.isFillViewport` stretches a child *shorter*
     * than the viewport to fill it, so the wrapper is exactly the card's height while there is
     * room to move the content, and exactly the content's height once there is not — and at that
     * size every gravity lands the content in the same place.
     *
     * Giving [contentView] the gravity directly instead would place a design taller than the card
     * at a negative top: `ScrollView` measures its child with an UNSPECIFIED height spec and then
     * derives `getScrollRange()` from that child's height alone, never its offset, so the top of
     * the design would sit above the scroll range with no scroll position able to reach it.
     */
    private fun alignedContent(
        ctx: android.content.Context,
        contentView: View,
        gravity: Int?
    ): View = if (gravity == null) contentView else FrameLayout(ctx).apply {
        addView(contentView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun showPopupBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val bodyValues = getDesignBodyValues(banner)
        val chrome = BannerChrome.of(banner, ctx)
        // `cardBackgroundColor` owns this now. The Unlayer `popupBackgroundColor` it replaces was
        // never authored — our editor runs Unlayer in web display mode, which never shows the Popup
        // Builder — so reading it was an editor default being treated as intent. White is what the
        // absent key resolved to then and what the documented default resolves to now.
        val popupBgColor = chrome.backgroundColor ?: Color.WHITE
        val overlayColor = getOverlayColor(banner)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // Full-screen popup — only close button dismisses
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        val dp = ctx.resources.displayMetrics.density

        // Check for body background image
        val bgImageMap = bodyValues["backgroundImage"] as? Map<String, Any?>
        val bgImageUrl = bgImageMap?.get("url") as? String ?: ""
        val hasBgImage = bgImageUrl.isNotEmpty()

        // Full-screen unless the author gave the card a size of its own. Computed before the
        // render call below, not after: an authored `cardWidth` has to reach `maxWidthPx` so the
        // design's own `contentWidth` is coerced against the card it will actually sit in, not
        // against the full screen it no longer spans.
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        val cardWidth = chrome.widthPx(screenWidth)
        val cardHeight = chrome.heightPx(screenHeight)

        val contentView = DesignRenderer.render(
            ctx, banner.design!!,
            maxWidthPx = cardWidth ?: screenWidth,
            transparentBody = hasBgImage
        ) { url ->
            Log.d(TAG, "Banner link tapped in popup: $url")
            dialog.dismiss()
            trackClick(banner)
            onLinkTap(url)
        }

        // Popup card
        val popupContainer = FrameLayout(ctx)
        chrome.applyCardBackground(popupContainer, if (hasBgImage) Color.TRANSPARENT else popupBgColor)

        // Scrollable content
        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            addView(alignedContent(ctx, contentView, chrome.contentGravity), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // If body has a background image, wrap the scroll content with it
        if (hasBgImage) {
            val bgWrapper = DesignRenderer.wrapWithBackgroundImage(
                ctx, scrollView, bgImageMap!!, forceCover = true
            )
            popupContainer.addView(bgWrapper, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            popupContainer.addView(scrollView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // Centred on both axes, as this type has always been. The popup has never read
        // `displayPosition` — `auto` means "what this SDK does today", which here is centred —
        // so only an authored `cardPosition*` moves the card.
        val verticalGravity = chrome.verticalGravity(Gravity.CENTER_VERTICAL)
        val horizontalGravity = chrome.horizontalGravity(Gravity.CENTER_HORIZONTAL)
        // Which system-bar insets the card's content has to be held clear of, decided per edge by
        // the two authored keys that can take the card off it: an axis left unsized spans the
        // window, so the card reaches both of that axis's edges, and a sized card reaches only the
        // edge it is anchored to — neither, when it is centred.
        val insetTop = cardHeight == null || verticalGravity == Gravity.TOP
        val insetBottom = cardHeight == null || verticalGravity == Gravity.BOTTOM
        val insetLeft = cardWidth == null || horizontalGravity == Gravity.START
        val insetRight = cardWidth == null || horizontalGravity == Gravity.END

        // Close button — added last so it's on top of everything
        val statusBarHeight = getStatusBarHeight(ctx)
        val closeButton = buildCloseButton(ctx, banner) {
            dialog.dismiss()
            closeBanner(banner)
        }
        popupContainer.addView(closeButton, FrameLayout.LayoutParams(
            (32 * dp).toInt(), (32 * dp).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            // The listener below overwrites this once real insets are available, but the initial
            // value has to already be right for the first layout pass.
            topMargin = (if (insetTop) statusBarHeight else 0) + (8 * dp).toInt()
            rightMargin = (8 * dp).toInt()
        })

        // A full-screen popup's *background* is meant to run edge to edge — that is what
        // makes it read as a takeover rather than a card. Its *content* is not: with the
        // scroll view filling the window, the design's first line sat under the status
        // bar, so a popup whose design was a heading and a button showed as an empty
        // coloured rectangle with a working close button.
        //
        // The inset goes on the scroll view, which is transparent, so the container's
        // colour or background image still fills the screen behind it. Real window insets
        // rather than the status_bar_height resource: that resource is the height the bar
        // *would* have, so it is wrong wherever the bar is hidden, and it says nothing
        // about a display cutout, the gesture pill, or a landscape bar that sits on the
        // side. displayCutout is unioned in for notches deeper than the bar itself.
        //
        // Swift arrives at the same place from the other direction: BannerDisplayView
        // reads geometry.safeAreaInsets and hands them to the chrome, which ignores the
        // safe area for its background only.
        //
        // All of which is the edge-to-edge card's problem, and so each edge's inset is applied
        // only while the card actually reaches that edge — the four flags above. A card the
        // author sized and centred reaches none of them and ends up with the zeroes it started
        // with; `cardWidth: "90%"` alone, the most natural authored value of the nine, still runs
        // the card the full height of the window and keeps its top and bottom insets.
        ViewCompat.setOnApplyWindowInsetsListener(popupContainer) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Floored at statusBarHeight, not just bars.top: a window that reports zero
            // system-bar insets (observed on some OEM skins even with the status bar drawn
            // and opaque) would otherwise zero out the content padding and drop the close
            // button to 8dp — under the status bar rather than below it. bars.top still wins
            // wherever it exceeds the resource estimate (a taller cutout, a landscape bar),
            // since the resource is only ever a floor, not the true value.
            val top = if (insetTop) maxOf(bars.top, statusBarHeight) else 0
            val bottom = if (insetBottom) bars.bottom else 0
            val left = if (insetLeft) bars.left else 0
            val right = if (insetRight) bars.right else 0
            scrollView.setPadding(left, top, right, bottom)
            (closeButton.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = top + (8 * dp).toInt()
                lp.rightMargin = right + (8 * dp).toInt()
                closeButton.layoutParams = lp
            }
            windowInsets
        }

        // Ensure close button is visible above content
        closeButton.bringToFront()

        val popupParams = FrameLayout.LayoutParams(
            cardWidth ?: WindowManager.LayoutParams.MATCH_PARENT,
            cardHeight ?: WindowManager.LayoutParams.MATCH_PARENT,
            // Inert while the card fills the window, which is what makes it safe at the default:
            // a card can only move once the author has given it a size.
            verticalGravity or horizontalGravity
        ).apply {
            // Set on both edges of the axis: for a card anchored to one of them the other is
            // inert, and for a card that still spans the axis the pair reads as an inset.
            chrome.offsetVerticalPx(screenHeight)?.let { topMargin = it; bottomMargin = it }
            chrome.offsetHorizontalPx(screenWidth)?.let { leftMargin = it; rightMargin = it }
        }
        // No overlay — full-screen popup replaces the screen
        val rootLayout = FrameLayout(ctx)
        rootLayout.addView(popupContainer, popupParams)

        dialog.setContentView(rootLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        dialog.setOnDismissListener {
            displayedBanners.remove(banner.token)
            activeDialogs.remove(dialog)
        }
        activeDialogs.add(dialog)
        dialog.show()

        // Make dialog full-screen
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun showBarBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val root = outerWrapper ?: return
        val chrome = BannerChrome.of(banner, ctx)

        val dp = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        // Where the bar sits: `displayPosition == "bottom"` as it always has, now reached through
        // the chrome's lookup so that an authored key can override it and an unexpected value
        // cannot fall through an implicit else. This is the SDK's one vertical `displayPosition`.
        val verticalGravity = chrome.verticalGravity(chrome.verticalFromDisplayPosition(Gravity.TOP))
        val cardWidth = chrome.widthPx(screenWidth)
        val barWidth = cardWidth ?: screenWidth
        val verticalPadding = chrome.offsetVerticalPx(screenHeight) ?: (12 * dp).toInt()
        val horizontalPadding = chrome.offsetHorizontalPx(screenWidth) ?: (16 * dp).toInt()
        // Bounded at both ends before it reaches `DesignRenderer.render`'s `maxWidthPx`: a small
        // `cardWidth` or a large `cardOffsetHorizontal` would take it below zero, where Android
        // reads -1/-2 as MATCH_PARENT/WRAP_CONTENT rather than as "no width at all", and a
        // negative `cardOffsetHorizontal` — which the contract allows — would inflate it past the
        // card's own width and let the design be sized wider than the card holding it.
        val availableWidth = (barWidth - horizontalPadding * 2).coerceIn(0, barWidth)

        val contentView = DesignRenderer.render(ctx, banner.design!!, maxWidthPx = availableWidth) { url ->
            trackClick(banner)
            onLinkTap(url)
        }
        // Only a bar hard against the top of the window has to clear the status bar.
        val statusBarPad = if (verticalGravity == Gravity.TOP) getStatusBarHeight(ctx) else 0

        // The card: transparent and edge to edge until the author says otherwise.
        val barLayout = FrameLayout(ctx).apply {
            elevation = 10 * dp
        }
        chrome.applyCardBackground(barLayout, chrome.backgroundColor ?: Color.TRANSPARENT)

        val contentWrapper = FrameLayout(ctx).apply {
            setPadding(horizontalPadding, statusBarPad + verticalPadding, horizontalPadding, verticalPadding)
            addView(contentView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        barLayout.addView(contentWrapper, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            chrome.contentGravity ?: DEFAULT_CHILD_GRAVITY
        ))

        // Close button positioned so ~1/4 overlaps the content boundary
        val closeSize = (24 * dp).toInt()
        val closeOverlap = closeSize / 4 // ~6dp overlap
        val closeButton = buildCloseButton(ctx, banner) {
            (barLayout.parent as? ViewGroup)?.removeView(barLayout)
            closeBanner(banner)
        }
        barLayout.addView(closeButton, FrameLayout.LayoutParams(
            closeSize, closeSize,
            Gravity.TOP or Gravity.END
        ).apply {
            // statusBarPad is zero anywhere but the top, which is the only place it was ever added.
            topMargin = statusBarPad + verticalPadding - closeOverlap
            rightMargin = horizontalPadding - closeOverlap
        })
        barLayout.clipChildren = false
        barLayout.clipToPadding = false

        // Add to outer wrapper as overlay
        root.addView(barLayout, FrameLayout.LayoutParams(
            cardWidth ?: ViewGroup.LayoutParams.MATCH_PARENT,
            chrome.heightPx(screenHeight) ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            // The horizontal half is inert while the bar spans the width, as it does by default,
            // and takes no `displayPosition`: the bar has never read the column on this axis.
            verticalGravity or chrome.horizontalGravity(Gravity.CENTER_HORIZONTAL)
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun showFlyoutBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val overlayColor = getOverlayColor(banner)
        val chrome = BannerChrome.of(banner, ctx)
        // `displayPosition == "left"` as it always has, now through the chrome's lookup so that an
        // authored key can override it and an unexpected value cannot fall through an implicit
        // else. This is the SDK's one horizontal `displayPosition`.
        val horizontalGravity = chrome.horizontalGravity(chrome.horizontalFromDisplayPosition(Gravity.END))
        val isLeft = horizontalGravity == Gravity.START

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val dp = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        val flyoutWidth = chrome.widthPx(screenWidth) ?: (screenWidth * 0.8).toInt()

        // Check for body background image
        val bodyValues = getDesignBodyValues(banner)
        val bgImageMap = bodyValues["backgroundImage"] as? Map<String, Any?>
        val bgImageUrl = bgImageMap?.get("url") as? String ?: ""
        val hasBgImage = bgImageUrl.isNotEmpty()

        val contentView = DesignRenderer.render(
            ctx, banner.design!!,
            maxWidthPx = flyoutWidth,
            transparentBody = hasBgImage
        ) { url ->
            dialog.dismiss()
            trackClick(banner)
            onLinkTap(url)
        }

        val overlayLayout = FrameLayout(ctx).apply {
            setBackgroundColor(overlayColor)
            setOnClickListener {
                dialog.dismiss()
                trackDismiss(banner)
            }
        }

        val flyoutContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 10 * dp
            isClickable = true
            // Vertical only: this axis keeps today's real padding (the status-bar pad an
            // authored `cardOffsetVertical` replaces), and the card spans the full height so
            // only the top edge is ever reachable. The horizontal axis has no padding to
            // preserve on master, and gets its inset as a margin below instead, so an authored
            // `cardOffsetHorizontal` moves the card away from its anchored edge rather than
            // shrinking the content it was just rendered to fit.
            setPadding(0, chrome.offsetVerticalPx(screenHeight) ?: getStatusBarHeight(ctx), 0, 0)
        }
        // Applied here only without a background image: with one, bgWrapper below is the view
        // that is actually the card, and the chrome has to go there instead.
        if (!hasBgImage) {
            chrome.applyCardBackground(flyoutContainer, chrome.backgroundColor ?: Color.WHITE)
        }

        // Close button on the outer edge: left flyout → close on right, right flyout → close on left
        val closeButton = buildCloseButton(ctx, banner) {
            dialog.dismiss()
            closeBanner(banner)
        }
        val closeGravity = if (isLeft) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
        val closeRow = FrameLayout(ctx).apply {
            addView(closeButton, FrameLayout.LayoutParams(
                (32 * dp).toInt(), (32 * dp).toInt(),
                closeGravity
            ).apply {
                topMargin = (8 * dp).toInt()
                if (isLeft) rightMargin = (8 * dp).toInt() else leftMargin = (8 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            })
        }
        flyoutContainer.addView(closeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Scrollable content below close button
        val contentGravity = chrome.contentGravity
        val scrollView = ScrollView(ctx).apply {
            // Off at the default, as on master. It is what stretches the wrapper below to the
            // card's height, which is the condition that makes an alignment observable at all.
            isFillViewport = contentGravity != null
            addView(alignedContent(ctx, contentView, contentGravity), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        flyoutContainer.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // If body has a background image, wrap the flyout content in a FrameLayout with the image behind
        val flyoutView: View = if (hasBgImage) {
            val bgWrapper = FrameLayout(ctx).apply {
                isClickable = true
                elevation = 10 * dp
            }
            // bgWrapper, not flyoutContainer, is the view that is actually the card on this path:
            // flyoutContainer only stacks the close button and scrollable content on top of the
            // image, which is bgWrapper's other, sibling child. Painting the chrome here is what
            // makes an authored cardBorderRadius clip the image along with them, instead of
            // clipping only its own descendants while the image paints the full square rect
            // underneath, unclipped, and the radius silently does nothing.
            chrome.applyCardBackground(bgWrapper, Color.TRANSPARENT)
            val bgImageView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            DesignRenderer.loadImageAsync(bgImageUrl, bgImageView)
            bgWrapper.addView(bgImageView)
            bgWrapper.addView(flyoutContainer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            bgWrapper
        } else {
            flyoutContainer
        }

        overlayLayout.addView(flyoutView, FrameLayout.LayoutParams(
            flyoutWidth,
            chrome.heightPx(screenHeight) ?: ViewGroup.LayoutParams.MATCH_PARENT,
            // The vertical half is inert while the flyout spans the height, as it does by default,
            // and takes no `displayPosition`: the flyout has never read the column on this axis.
            horizontalGravity or chrome.verticalGravity(Gravity.TOP)
        ).apply {
            // Inset the card from the edge it's anchored to. Only the anchored edge's margin
            // does anything — the card doesn't span the full width, so the far edge is inert —
            // and a margin moves the card itself rather than padding its content, which is what
            // keeps `flyoutWidth` (what the content above was just rendered to fit) equal to the
            // card's actual width instead of leaving it wider than the space left for it.
            val sideInset = chrome.offsetHorizontalPx(screenWidth) ?: 0
            if (isLeft) leftMargin = sideInset else rightMargin = sideInset
        })

        dialog.setContentView(overlayLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        dialog.setOnDismissListener {
            displayedBanners.remove(banner.token)
            activeDialogs.remove(dialog)
        }
        activeDialogs.add(dialog)
        dialog.show()
    }

    private fun showStaticBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val wrapper = innerWrapper ?: return
        val content = contentHolder ?: return

        val contentView = DesignRenderer.render(ctx, banner.design!!) { url ->
            trackClick(banner)
            onLinkTap(url)
        }

        contentView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        contentView.tag = "$BANNER_TAG_PREFIX${banner.token}"

        val strategy = banner.displayStrategy ?: "afterbegin"
        Log.d(TAG, "Static banner strategy: $strategy for ${banner.token}")

        when (strategy) {
            "afterbegin" -> {
                // Insert before the original content
                val contentIndex = wrapper.indexOfChild(content)
                wrapper.addView(contentView, contentIndex.coerceAtLeast(0))
            }
            "beforeend", "afterend" -> {
                // Insert after the original content
                wrapper.addView(contentView)
            }
            "replace" -> {
                // Hide original content and insert banner in its place
                content.visibility = View.GONE
                val contentIndex = wrapper.indexOfChild(content)
                wrapper.addView(contentView, contentIndex + 1)
            }
            else -> {
                // Default: after content
                wrapper.addView(contentView)
            }
        }
    }

    private fun removeStaticBannerView(token: String) {
        val wrapper = innerWrapper ?: return
        val tag = "$BANNER_TAG_PREFIX$token"
        for (i in wrapper.childCount - 1 downTo 0) {
            if (wrapper.getChildAt(i).tag == tag) {
                wrapper.removeViewAt(i)
                contentHolder?.visibility = View.VISIBLE
                break
            }
        }
    }

    private fun removeAllStaticBannerViews() {
        val wrapper = innerWrapper ?: return
        for (i in wrapper.childCount - 1 downTo 0) {
            val child = wrapper.getChildAt(i)
            if (child.tag?.toString()?.startsWith(BANNER_TAG_PREFIX) == true) {
                wrapper.removeViewAt(i)
            }
        }
    }

    private fun buildCloseButton(
        context: android.content.Context,
        banner: BannerResponse,
        onClick: () -> Unit
    ): View {
        val dp = context.resources.displayMetrics.density
        val bodyValues = getDesignBodyValues(banner)

        val bgColor = DesignRenderer.parseColor(bodyValues["popupCloseButton_backgroundColor"])
            ?: DesignRenderer.parseColor(banner.cssStyles["closeButtonBackgroundColor"])
            ?: Color.WHITE
        val iconColor = DesignRenderer.parseColor(bodyValues["popupCloseButton_iconColor"])
            ?: DesignRenderer.parseColor(banner.cssStyles["closeButtonColor"])
            ?: Color.DKGRAY
        val borderColor = DesignRenderer.parseColor(banner.cssStyles["closeButtonBorder"])
            ?: Color.LTGRAY

        val button = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(iconColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE

            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 16 * dp
                setStroke((1 * dp).toInt(), borderColor)
            }

            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { onClick() }
        }

        return button
    }

    private fun closeBanner(banner: BannerResponse) {
        displayedBanners.remove(banner.token)
        trackDismiss(banner)
    }

    private fun trackImpression(banner: BannerResponse) {
        Log.d(TAG, "Tracking impression for banner: ${banner.token}")
        scope?.launch(Dispatchers.IO) {
            try {
                client.bannerImpression(banner)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to track banner impression", e)
            }
        }
    }

    private fun trackClick(banner: BannerResponse) {
        Log.d(TAG, "Tracking click for banner: ${banner.token}")
        scope?.launch(Dispatchers.IO) {
            try {
                client.bannerAction(banner, action = "bannerClick")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to track banner click", e)
            }
        }
    }

    private fun trackDismiss(banner: BannerResponse) {
        Log.d(TAG, "Tracking dismiss for banner: ${banner.token}")
        scope?.launch(Dispatchers.IO) {
            try {
                client.bannerAction(banner, action = "bannerClose")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to track banner dismiss", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getDesignBodyValues(banner: BannerResponse): Map<String, Any?> {
        val design = banner.design ?: return emptyMap()
        val body = design["body"] as? Map<String, Any?> ?: return emptyMap()
        return body["values"] as? Map<String, Any?> ?: emptyMap()
    }

    private fun getOverlayColor(banner: BannerResponse): Int {
        val bodyValues = getDesignBodyValues(banner)
        DesignRenderer.parseColor(bodyValues["popupOverlay_backgroundColor"])?.let { return it }
        DesignRenderer.parseColor(banner.cssStyles["overlayColor"])?.let { return it }
        return Color.argb(128, 0, 0, 0)
    }

    private fun getStatusBarHeight(ctx: android.content.Context): Int {
        val resourceId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) ctx.resources.getDimensionPixelSize(resourceId) else 0
    }

}
