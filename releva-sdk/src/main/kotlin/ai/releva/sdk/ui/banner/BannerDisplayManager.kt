package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.services.banner.BannerSessionStore
import ai.releva.sdk.types.response.BannerResponse
import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
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
import kotlin.math.roundToInt

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

        // The popup card's cap: this far short of the box it is placed in on each side, and never
        // under the floor. sdk-swift's and sdk-react-native's 16pt and 120pt.
        private const val POPUP_CARD_GUTTER_DP = 16f
        private const val POPUP_CARD_FLOOR_DP = 120f
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
        // The authored `cardBackgroundColor`, then the design's own `popupBackgroundColor`, then
        // white: sdk-swift's order, which sdk-react-native adopted with the content-sized card on
        // 2026-10-05. `popupBackgroundColor` had been dropped as an editor default (#FFFFFF on every
        // production banner that carries it); it is back as the fallback so the SDKs read the same
        // keys in the same order.
        val popupBgColor = chrome.backgroundColor
            ?: DesignRenderer.parseColor(bodyValues["popupBackgroundColor"])
            ?: Color.WHITE
        val overlayColor = getOverlayColor(banner)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // The window fills the screen, so there is no "outside" for the platform to detect: a tap
        // beside the card lands on the dimmed backdrop below, which closes the popup itself.
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        val dp = ctx.resources.displayMetrics.density

        // Check for body background image
        val bgImageMap = bodyValues["backgroundImage"] as? Map<String, Any?>
        val bgImageUrl = bgImageMap?.get("url") as? String ?: ""
        val hasBgImage = bgImageUrl.isNotEmpty()

        // THE CARD, sized the way sdk-swift sizes it (PR #23) and sdk-react-native since 2026-10-05:
        // the authored `cardWidth`, else the design's `popupWidth` (600 when unset), and the
        // authored `cardHeight`, else as tall as its content. PopupCardLayout caps both 16dp short
        // of the box the card is placed in, measured at layout time. Until this release an unsized
        // popup filled the window instead.
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        val gutter = (POPUP_CARD_GUTTER_DP * dp).roundToInt()
        val floor = (POPUP_CARD_FLOOR_DP * dp).roundToInt()
        val cardWidth = chrome.popupWidthPx(screenWidth, bodyValues["popupWidth"])
        val cardHeight = chrome.heightPx(screenHeight)

        // `DesignRenderer.render` coerces the design's own `contentWidth` against `maxWidthPx`, so
        // it has to be the card's real width — the card's ScrollView does not scroll sideways and
        // anything wider is clipped away. Rendering happens before layout, so this is the cap
        // PopupCardLayout will apply, estimated from the display: exact wherever no system bar
        // sits at the side, which is portrait on every phone.
        val renderWidth = minOf(cardWidth, maxOf(screenWidth - 2 * gutter, floor))
        val contentView = DesignRenderer.render(
            ctx, banner.design!!,
            maxWidthPx = renderWidth,
            transparentBody = hasBgImage
        ) { url ->
            Log.d(TAG, "Banner link tapped in popup: $url")
            dialog.dismiss()
            trackClick(banner)
            onLinkTap(url)
        }

        // Popup card: rounded (the authored `cardBorderRadius`, else the design's `borderRadius`,
        // else 10dp) and lifted off the backdrop by an elevation shadow — sdk-swift draws black at
        // 25%, a 24pt blur, 8pt down; sdk-react-native uses `elevation: 12` on Android, as here.
        val popupContainer = PopupCardLayout(ctx, cardWidth, cardHeight, gutter, floor).apply {
            elevation = 12 * dp
            // Taps on the card's own area stay on the card; only the backdrop around it closes.
            isClickable = true
        }
        chrome.applyCardBackground(
            popupContainer,
            if (hasBgImage) Color.TRANSPARENT else popupBgColor,
            fallbackRadiusPx = chrome.popupCornerRadiusPx(bodyValues["borderRadius"])
        )

        // The close button sits over the card's top-right corner (below), so the content starts
        // below it rather than under it: on a content-sized card the design's first line would
        // otherwise run beneath the ✕. sdk-react-native reserves 48pt and sdk-swift 56pt for the
        // same reason; this is the control's margin, its painted side and the margin again (48dp
        // at the default size). `clipToPadding` off so the band scrolls with the content, as a
        // padding on React Native's content container does, rather than clipping it.
        val closeStyle = BannerCloseButtonStyle.of(banner.cssStyles)
        val closeSize = (closeStyle.sideDp * dp).toInt()
        val closeMargin = (8 * dp).toInt()
        val closeBand = closeMargin + closeSize + closeMargin

        // Scrollable content
        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, closeBand, 0, 0)
            addView(alignedContent(ctx, contentView, chrome.contentGravity), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        popupContainer.contentSizer = scrollView

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

        // THE BACKDROP, which is also the box the card is placed in. It fills the window, is
        // dimmed (the design's `popupOverlay_backgroundColor`, else `cssStyles.overlayColor`, else
        // black at 50% — the flyout's scrim, and sdk-react-native's), and a tap on it closes the
        // popup, as on sdk-swift and sdk-react-native. Until this release the popup had no
        // backdrop: it filled the window and its close button was the only way out.
        //
        // Its PADDING is the safe area: the insets listener below sets it to the system bars and
        // cutout the window reports, so the card's gravity places it inside what a person can see
        // and PopupCardLayout's cap is measured from that box rather than assumed from the
        // display. `clipToPadding` off so the card's shadow is not cut where it meets a bar.
        val rootLayout = FrameLayout(ctx).apply {
            setBackgroundColor(overlayColor)
            clipToPadding = false
            setOnClickListener {
                dialog.dismiss()
                trackDismiss(banner)
            }
            closeOnTapOnly(this)
        }

        // The close button, added to the window below rather than to the card. A button anchored
        // inside the card hangs outside its bounds once the card is smaller than the control —
        // and `ViewGroup.dispatchTouchEvent` only forwards a pointer to a child it falls inside, so
        // that part of it would stop responding. Staying a sibling of the card keeps it
        // dispatchable at any size; what follows is how it stays visually attached to the card.
        val statusBarHeight = getStatusBarHeight(ctx)
        val closeButton = buildCloseButton(ctx, closeStyle) {
            dialog.dismiss()
            closeBanner(banner)
        }
        val closeTapTarget = (closeStyle.tapTargetDp * dp).toInt()
        val closeRing = closeRingPx(closeStyle, dp)
        val closeParams = FrameLayout.LayoutParams(closeTapTarget, closeTapTarget)

        // The window's safe area, updated by the insets listener below and read every time the
        // card's own layout moves the button. Starts at statusBarHeight/0 so the very first
        // layout pass — which can run before the platform has dispatched real insets — already
        // clears the status bar rather than the literal window edge.
        var closeSafeTop = statusBarHeight
        var closeSafeRight = 0

        // Places the button from popupContainer's own laid-out rect — its top-right corner,
        // inset by closeMargin on each axis — rather than the window's, so it stays attached to
        // the card at any size or offset instead of landing in the screen's corner with nothing
        // behind it. `View.getX()`/`getY()` already fold in the card's own translation (the
        // offsets below) and the backdrop's padding, so a moved card carries its button with it.
        // Still clamped into the window's safe area: a card offset far enough to reach an edge
        // must not push the dialog's close control past it, off-screen or under a system bar.
        //
        // Every term here is the PAINTED square's — its corner and its bounds — and the view is
        // placed `closeRing` back from it, so widening the view to a 48dp tap target moves nothing
        // a person can see.
        fun positionCloseButton() {
            val cardRight = popupContainer.x + popupContainer.width
            val cardTop = popupContainer.y
            val maxX = (screenWidth - closeSafeRight - closeMargin - closeSize).toFloat()
            val minY = (closeSafeTop + closeMargin).toFloat()
            val maxY = (screenHeight - closeSize).toFloat().coerceAtLeast(minY)
            closeButton.x =
                (cardRight - closeSize - closeMargin).coerceIn(0f, maxX.coerceAtLeast(0f)) - closeRing
            closeButton.y = (cardTop + closeMargin).coerceIn(minY, maxY) - closeRing
        }
        popupContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionCloseButton() }
        // And again once the BACKDROP has laid out all of its children. The button is a child of
        // the padded backdrop too, so its own left/top move with the padding — and `setX`/`setY`
        // are a translation from those. The card is laid out before the button, so its listener
        // above computes that translation against the button's previous position; this one runs
        // after both and corrects it.
        rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionCloseButton() }

        // The card is placed inside the bars, so its content needs no inset of its own any more:
        // the backdrop's padding is the whole of the window chrome. Real window insets rather
        // than the status_bar_height resource, which is the height the bar *would* have — wrong
        // wherever it is hidden, and silent about a cutout or a landscape bar on the side.
        //
        // The padding reads the insets EXACTLY, unfloored: a reported zero top inset is also a
        // window with a deliberately hidden status bar, and a floor there would hold a
        // top-anchored card 24dp below the window edge. The close control's own clamp keeps the
        // floor, as before — being wrong in that direction only moves it down a little.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (rootLayout.paddingLeft != bars.left || rootLayout.paddingTop != bars.top ||
                rootLayout.paddingRight != bars.right || rootLayout.paddingBottom != bars.bottom) {
                rootLayout.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            closeSafeTop = maxOf(bars.top, statusBarHeight)
            closeSafeRight = bars.right
            positionCloseButton()
            windowInsets
        }

        // WRAP_CONTENT on both axes: PopupCardLayout decides its own size against the box it is
        // handed, which is what lets the cap be measured. The gravity places it in that box.
        val popupParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            verticalGravity or horizontalGravity
        )
        rootLayout.addView(popupContainer, popupParams)
        // Displaces the card from wherever the gravity above put it. The close button is not a
        // child of the card, so this alone does not move it — the OnLayoutChangeListener above
        // does, the next time popupContainer's layout runs.
        chrome.applyOffsets(popupContainer, verticalGravity, horizontalGravity, screenWidth, screenHeight)
        // A sibling rather than a child, so it is reachable whatever the author did to the card's
        // size — and lifted ABOVE the card's elevation. Being added after the card is not enough:
        // Android draws and dispatches touches by Z first and child order second, so at the
        // card's 12dp the ✕ was drawn underneath it and the clickable card took its taps (device
        // QA, CLS-01…08: no ✕ visible, one tap did not close the popup).
        closeButton.elevation = popupContainer.elevation + 1 * dp
        rootLayout.addView(closeButton, closeParams)

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

        // The dialog's window fills the screen; the backdrop above is what dims it.
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
        // The bar has never read `displayPosition` on this axis, so this half is only ever an
        // authored key, and it is inert while the bar spans the width as it does by default.
        val horizontalGravity = chrome.horizontalGravity(Gravity.CENTER_HORIZONTAL)
        val cardWidth = chrome.widthPx(screenWidth)
        val barWidth = cardWidth ?: screenWidth
        val verticalPadding = (12 * dp).toInt()
        val horizontalPadding = (16 * dp).toInt()
        // The close button is an absolute sibling drawn OVER the content: 32dp wide by default,
        // covering the band from 8dp to 40dp in from the right. Content padded to 16 runs under
        // it and the tail of a headline is drawn beneath the glyph. Reserve the band instead: 42
        // is 40 plus 2 of clearance. This widens the right gutter on EVERY bar, authored chrome or
        // not — the collision predates the chrome keys, and keeping the default path
        // byte-identical would be preserving a bug rather than compatibility.
        val closeGutter = (42 * dp).toInt()
        // Floored before it reaches `DesignRenderer.render`'s `maxWidthPx`: on master the bar was
        // always the screen's width so this could not go negative, and a small authored `cardWidth`
        // now can — where Android reads -1/-2 as MATCH_PARENT/WRAP_CONTENT rather than as "no width
        // at all".
        val availableWidth = (barWidth - horizontalPadding - closeGutter).coerceAtLeast(0)

        val contentView = DesignRenderer.render(ctx, banner.design!!, maxWidthPx = availableWidth) { url ->
            trackClick(banner)
            onLinkTap(url)
        }
        // Only a bar hard against the top of the window has to clear the status bar — and it is the
        // CARD that clears it, not the content inside it.
        //
        // This used to be the content wrapper's top padding, which held the design clear of the
        // clock but left the card's own background running up behind it. Invisible while a bar
        // had no background, which is every banner before `cardBackgroundColor` existed; plainly
        // wrong once one does, and worst on a card the author narrowed — CHR-02's 240dp centred
        // card was photographed on 2026-10-03 with the status bar's clock and battery drawn over
        // its magenta, which is nobody's intent for a floating card.
        //
        // THE CONTENT DOES NOT MOVE. It was at `statusBarPad + verticalPadding` inside a card at
        // y=0; it is now at `verticalPadding` inside a card at y=statusBarPad. Same absolute
        // position, and the card's bottom is unchanged too — only its top edge, and the
        // background that used to reach past it, come down. A banner carrying none of these keys
        // has no background to see, so the default path is unchanged in what it draws.
        val statusBarPad = if (verticalGravity == Gravity.TOP) getStatusBarHeight(ctx) else 0

        // The card: transparent and edge to edge until the author says otherwise.
        val barLayout = FrameLayout(ctx).apply {
            elevation = 10 * dp
            // The bar is an overlay drawn OVER the app, and a touch no view consumes is handed to
            // whatever is beneath it: device QA found a tap on a bar's body opening the screen
            // under it. Clickable, the card consumes every touch on its own area. Its children
            // still see the touch first (links in the design), and `View.onTouchEvent` asks the
            // close control's TouchDelegate before the card's own click handling. The flyout's
            // card has always been clickable for the same reason.
            isClickable = true
        }
        chrome.applyCardBackground(barLayout, chrome.backgroundColor ?: Color.TRANSPARENT)

        val contentWrapper = FrameLayout(ctx).apply {
            // Relative, not absolute: the control it reserves for is at `Gravity.END`, so under
            // an RTL layout direction the band belongs on the left and `setPadding` would put it
            // on the right — copy under the glyph again, mirrored.
            setPaddingRelative(horizontalPadding, verticalPadding, closeGutter, verticalPadding)
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

        // The control is pulled out of the content's corner by a quarter of its own side, and its
        // right edge is anchored by its INNER edge: a larger painted square grows toward the card's
        // edge, so the band the 1.5.3 gutter reserves is only crossed once the square outgrows it.
        val closeStyle = BannerCloseButtonStyle.of(banner.cssStyles)
        val closeSize = (closeStyle.sideDp * dp).toInt()
        val closeRing = closeRingPx(closeStyle, dp)
        val closeOverlap = closeSize / 4
        // The band's outer edge: the 16dp padding plus the 24dp the control reaches into the gutter.
        val closeBand = horizontalPadding + (24 * dp).toInt()
        // The view is the painted square and nothing more: a 48dp view would give this WRAP_CONTENT
        // card a 48dp floor and move the copy of a compact bar. The tap target is a TouchDelegate.
        val closeButton = buildCloseButton(ctx, closeStyle, tapRing = false) {
            (barLayout.parent as? ViewGroup)?.removeView(barLayout)
            closeBanner(banner)
        }
        barLayout.addView(closeButton, FrameLayout.LayoutParams(
            closeSize, closeSize,
            Gravity.TOP or Gravity.END
        ).apply {
            // No statusBarPad: the CARD now clears the status bar, so the control is positioned
            // from the card's own top edge like every other child of it.
            topMargin = (verticalPadding - closeOverlap).coerceAtLeast(0)
            marginEnd = (closeBand - closeSize).coerceAtLeast(0)
        })
        // A pointer outside the card's own bounds is never dispatched to it, so the ring is
        // shifted back inside them rather than clipped, which keeps the full target wherever the
        // card is at least that big. Only what still does not fit is cut.
        val closeHitRect = Rect()
        barLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            closeButton.getHitRect(closeHitRect)
            closeHitRect.inset(-closeRing, -closeRing)
            closeHitRect.offset(
                maxOf(0, -closeHitRect.left) - maxOf(0, closeHitRect.right - barLayout.width),
                maxOf(0, -closeHitRect.top) - maxOf(0, closeHitRect.bottom - barLayout.height)
            )
            // A zero-size card leaves nothing tappable anyway; clearing the delegate rather than
            // leaving a stale one pointed at the last laid-out rect.
            barLayout.touchDelegate =
                if (closeHitRect.intersect(0, 0, barLayout.width, barLayout.height)) {
                    TouchDelegate(closeHitRect, closeButton)
                } else {
                    null
                }
        }
        barLayout.clipChildren = false
        barLayout.clipToPadding = false

        // Add to outer wrapper as overlay
        root.addView(barLayout, FrameLayout.LayoutParams(
            cardWidth ?: ViewGroup.LayoutParams.MATCH_PARENT,
            chrome.heightPx(screenHeight) ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            verticalGravity or horizontalGravity
        ).apply { topMargin = statusBarPad })
        // The card moves; the paddings above stay what they have always been. Padding the content
        // instead would shrink the area `availableWidth` just rendered the design to fit, and a
        // negative offset — which the contract allows — would give the wrapper a negative padding
        // and, with `clipChildren` off, draw the design outside the bar altogether.
        chrome.applyOffsets(barLayout, verticalGravity, horizontalGravity, screenWidth, screenHeight)

        // The close button rides on the card, and a bar with no authored width already spans the
        // screen — so translating it right carries its right edge, and the ✕ with it, off the
        // display and leaves the banner undismissable. The popup path has always bounded its own
        // control (positionCloseButton's coerceIn); the bar never did.
        //
        // The CARD still moves by exactly the authored amount: the contract is a translation and
        // is not this file's to bound. Only the control comes back, by the amount of the card now
        // past the edge — which, for a card that filled the width before the translation, IS the
        // translation. No measurement needed, and confined to that case: a bar with an authored
        // cardWidth has slack the offset is probably moving it within, and pulling the control in
        // there would move it off a card that never left the screen.
        if (cardWidth == null) {
            val overflow = barLayout.translationX.coerceAtLeast(0f)
            if (overflow > 0f) closeButton.translationX = -overflow
        }
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
        // Named for what it decides — which edge the close button goes on — rather than for the
        // card's own anchor, because `horizontalGravity` can now also be `CENTER_HORIZONTAL` once
        // `cardPositionHorizontal: "center"` is authored, and a centred card is not "left".
        val closeOnTrailingEdge = horizontalGravity == Gravity.START
        // The flyout has never read `displayPosition` on this axis, so this half is only ever an
        // authored key, and it is inert while the card spans the height as it does by default.
        val verticalGravity = chrome.verticalGravity(Gravity.TOP)

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
            closeOnTapOnly(this)
        }

        // The same clearance question the popup asks, in the SAME SHAPE. The first attempt at this
        // used `screenHeight - statusBarHeight` — no bottom inset and no reference to the anchor —
        // which is wrong because a flyout's vertical gravity is author-settable: on the `bottom`
        // and `center` arms a card around 93% tall passed that gate and was then laid out with its
        // own top edge under the status bar, with the padding gone. `100%` was caught, which is
        // what made the reasoning look complete.
        //
        // So it is decided in the insets listener below, where the real bars are, against
        // `screenHeight - top - bottom` exactly as the popup is. Starts as the padding this path
        // has always had, and is only ever REMOVED once the insets arrive — a flyout that never
        // receives them keeps the clearance rather than losing it.
        val flyoutContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 10 * dp
            isClickable = true
            setPadding(0, getStatusBarHeight(ctx), 0, 0)
        }
        // Applied here only without a background image: with one, bgWrapper below is the view
        // that is actually the card, and the chrome has to go there instead.
        if (!hasBgImage) {
            chrome.applyCardBackground(flyoutContainer, chrome.backgroundColor ?: Color.WHITE)
        }

        // Close button on the outer edge: left flyout → close on right, right flyout → close on left
        val closeStyle = BannerCloseButtonStyle.of(banner.cssStyles)
        val closeButton = buildCloseButton(ctx, closeStyle) {
            dialog.dismiss()
            closeBanner(banner)
        }
        val closeGravity = if (closeOnTrailingEdge) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
        // The painted square sits 8dp in from the side edge AND the top edge, less the ring the
        // tap target adds around it on each axis, so the ✕ stays in the same corner at every
        // closeFontSize instead of riding up as the ring shrinks.
        val closeMargin = ((8 * dp).toInt() - closeRingPx(closeStyle, dp)).coerceAtLeast(0)
        val closeRow = FrameLayout(ctx).apply {
            addView(closeButton, FrameLayout.LayoutParams(
                (closeStyle.tapTargetDp * dp).toInt(), (closeStyle.tapTargetDp * dp).toInt(),
                closeGravity
            ).apply {
                topMargin = closeMargin
                if (closeOnTrailingEdge) rightMargin = closeMargin else leftMargin = closeMargin
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
            horizontalGravity or verticalGravity
        ))
        // The same anchored-edge rule the popup applies, and for the same reason: a flyout given a
        // `cardHeight` and anchored to the bottom was placed against the DISPLAY's bottom, so its
        // last rows sat behind the navigation bar. A flyout with no cardHeight is pinned top to
        // bottom and stays full-bleed. This path has no insets listener of its own — the status
        // bar is read from the resource above — so it gets one.
        ViewCompat.setOnApplyWindowInsetsListener(flyoutView) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // sizedHorizontally is FALSE, not `flyoutWidth != null`. A flyout is a panel DOCKED to
            // a side edge: its 80% width is the SDK's own, not an authored `cardWidth`, and its
            // background is meant to reach that edge the way a full-bleed card's does. Insetting
            // it would open a gap between the panel and the screen edge with the scrim showing
            // through — ~48dp of it in landscape with three-button navigation. The vertical axis
            // is sized only when the author gave a `cardHeight`, and that is the axis where a
            // flyout can be anchored away from the edge it is docked to.
            val fResource = getStatusBarHeight(ctx)
            val fTop = maxOf(bars.top, fResource)
            val fHeight = chrome.heightPx(screenHeight)
            // Exact insets for the margin, as on the popup and for the same reason.
            applyAnchorInsets(
                flyoutView, bars,
                sizedVertically = fHeight != null, sizedHorizontally = false,
                verticalGravity = verticalGravity, horizontalGravity = horizontalGravity
            )
            // Where the card lands, against the floored bar — the popup's rule, same shape.
            val fTopV = when {
                fHeight == null -> 0
                (verticalGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP -> bars.top
                (verticalGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM ->
                    screenHeight - bars.bottom - fHeight
                else -> (screenHeight - fHeight) / 2 + (bars.top - bars.bottom) / 2
            }
            val fClearV = fHeight != null &&
                fTopV >= fTop && fTopV + fHeight <= screenHeight - bars.bottom &&
                chrome.verticalOffsetPx(screenHeight) == null
            // An UNSIZED flyout keeps master's padding EXACTLY: the fixed resource, not the live
            // `maxOf(bars.top, resource)`. The live value is larger wherever a display cutout
            // exceeds the status bar, which would move the content of every flyout in production
            // down — a fourth default-path change, where the CHANGELOG enumerates three and none
            // is on this display type. The floor belongs to the clearance decision, which only a
            // SIZED card makes.
            flyoutContainer.setPadding(
                0,
                when {
                    // The default path, byte-identical to master: the fixed resource. The live
                    // floored value is larger wherever a cutout exceeds the status bar, and using
                    // it here would move the content of every flyout in production down.
                    fHeight == null -> fResource
                    fClearV -> 0
                    // Sized and NOT clear: the real inset, because this is the clearance decision
                    // and the resource is only ever an estimate of it.
                    else -> fTop
                },
                0, 0
            )
            windowInsets
        }
        // flyoutView, not flyoutContainer: with a background image the card is the wrapper around
        // both, and translating the inner one would slide the content off its own image.
        chrome.applyOffsets(flyoutView, verticalGravity, horizontalGravity, screenWidth, screenHeight)

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

    /**
     * A dimmed backdrop closes its banner on a TAP, not on any gesture that ends inside it. A
     * clickable view counts a press that moved but stayed within its bounds as a click, and a
     * backdrop is the whole window — so a swipe or scroll started on it closed the popup (device
     * QA 2026-10-08, BAN-08: the swipe that scrolled the list on to the popup's trigger dismissed
     * the popup it had just triggered). The click listener stays the action, so accessibility's
     * click still closes it; touch reaches it only through [GestureDetector.onSingleTapUp].
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun closeOnTapOnly(backdrop: View) {
        val taps = GestureDetector(backdrop.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean = backdrop.performClick()
        }).apply {
            // There is no long-press action on a backdrop; leaving detection on means a press held
            // past the long-press timeout sets mInLongPress, and ACTION_UP never reaches
            // onSingleTapUp, so a slow tap stops dismissing the banner.
            setIsLongpressEnabled(false)
        }
        backdrop.setOnTouchListener { _, event -> taps.onTouchEvent(event); true }
    }

    /**
     * The transparent ring between the control's tap target — which is the view, so that it is
     * never below Android's 48dp minimum — and the square it actually paints, in device pixels.
     * Call sites place the control by the PAINTED square's corner, where it has always sat, and
     * pull the view back by this.
     */
    private fun closeRingPx(style: BannerCloseButtonStyle, density: Float): Int =
        ((style.tapTargetDp * density).toInt() - (style.sideDp * density).toInt()) / 2

    private fun buildCloseButton(
        context: android.content.Context,
        style: BannerCloseButtonStyle,
        tapRing: Boolean = true,
        onClick: () -> Unit
    ): View {
        val dp = context.resources.displayMetrics.density
        val ring = if (tapRing) closeRingPx(style, dp) else 0
        val glyphPadding = ring + ((style.sideDp - style.glyphSizeDp) / 2f * dp).roundToInt()

        return ImageButton(context).apply {
            // No intrinsic size, so the ImageView sizes it to the content box — the glyph box the
            // padding below leaves. configureBounds nulls mDrawMatrix on that branch, so no
            // scaleType is consulted.
            setImageDrawable(CloseGlyphDrawable(style.iconColor))

            background = InsetDrawable(
                GradientDrawable().apply {
                    setColor(style.backgroundColor)
                    cornerRadius = style.cornerRadiusDp * dp
                    setStroke((style.borderWidthDp * dp).roundToInt(), style.borderColor)
                },
                ring
            )

            setPadding(glyphPadding, glyphPadding, glyphPadding, glyphPadding)
            setOnClickListener { onClick() }
        }
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

    /**
     * Keep a card the author SIZED clear of the system bar it is anchored to.
     *
     * A card with no size on an axis is MATCH_PARENT and is meant to run edge to edge: a
     * full-bleed takeover whose background deliberately passes under the bars while its content
     * is padded clear of them. A card the author gave a size is not that. `cardPositionVertical:
     * "bottom"` on a 300dp card means "at the bottom", and anchoring it to the DISPLAY's bottom
     * puts its last rows behind the navigation bar — where a `cardOffsetVertical` meant to lift
     * it clear of that edge buys nothing, because the edge it is measured from is itself behind
     * the bar. Measured on a device 2026-10-03: CHR-08's 300dp card read 277dp of magenta, the
     * missing 23 being under the bar, and the 24dp gap the author asked for was invisible.
     *
     * Only on an axis the author SIZED, and then on both of its edges: the card's box becomes
     * the window less the bars and the gravity places it inside that, so an anchored card lands
     * on the bar's inner edge and a centred one is centred in what a person can see. An unsized
     * axis stays MATCH_PARENT and stays full-bleed, which is what CHR-09 and CHR-13 assert. The
     * content padding on the scroller is decided separately, by each caller's own per-edge CLEAR
     * predicate derived from this same margin — sized alone does not imply clear (a centred axis,
     * a 100%-height card and an offset can each still leave it covering a bar), so the padding
     * stays wherever that predicate says the card has not actually left the bar's edge.
     *
     * sdk-react-native reaches the same place by a different route — its overlay IS the safe
     * area, so every card it places is inside one already. Since the content-sized card, the
     * popup does too (its backdrop is padded to the bars), and this is the flyout's alone.
     */
    private fun applyAnchorInsets(
        view: View,
        bars: androidx.core.graphics.Insets,
        sizedVertically: Boolean,
        sizedHorizontally: Boolean,
        verticalGravity: Int,
        horizontalGravity: Int
    ) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val vCentred = (verticalGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL
        // HORIZONTAL_GRAVITY_MASK, not `START or END`. CENTER_HORIZONTAL is 0x01 and START/END
        // are 0x00800003 / 0x00800005, so `and (START or END)` is NON-ZERO for a centred card and
        // would have read it as anchored. Masked to 0x07 the three are 0x01 / 0x03 / 0x05 and
        // separate cleanly, which is the same reason the vertical check masks rather than tests
        // bits.
        val hCentred =
            (horizontalGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        // BOTH edges of a sized axis, not just the one the card is anchored to. The box the card
        // is placed in is the window less the bars, and the gravity then places it inside THAT —
        // so an anchored card lands on the bar's inner edge and a CENTRED one is centred in what
        // a person can see. Insetting only the anchored edge would centre a sized card in the
        // display instead, which on a phone with a 24dp status bar and a 48dp navigation bar
        // puts it 12dp below the middle of the area it is actually seen in.
        // A CENTRED axis takes HALF the difference, not both insets. FrameLayout.layoutChildren's
        // CENTER arms add `topMargin - bottomMargin` WHOLE, so margins equal to the two insets
        // would land the card at displayCentre + (top - bottom) where the visible centre is
        // displayCentre + (top - bottom)/2 — the same error as no inset at all, with the sign
        // flipped, and on a 24dp-status / 48dp-navigation phone that is 12dp the other way.
        // Halving the difference makes FrameLayout's own arithmetic land it on the visible
        // centre. An ANCHORED axis takes the whole inset: its arm is
        // `parentTop + topMargin` / `parentBottom - height - bottomMargin`, which is already
        // the bar's inner edge, and the opposite margin is ignored.
        val halfV = (bars.top - bars.bottom) / 2
        val halfH = (bars.left - bars.right) / 2
        val top = if (!sizedVertically) 0 else if (vCentred) maxOf(halfV, 0) else bars.top
        val bottom = if (!sizedVertically) 0 else if (vCentred) maxOf(-halfV, 0) else bars.bottom
        val left = if (!sizedHorizontally) 0 else if (hCentred) maxOf(halfH, 0) else bars.left
        val right = if (!sizedHorizontally) 0 else if (hCentred) maxOf(-halfH, 0) else bars.right
        if (lp.leftMargin != left || lp.topMargin != top ||
            lp.rightMargin != right || lp.bottomMargin != bottom) {
            lp.setMargins(left, top, right, bottom)
            view.layoutParams = lp
        }
    }

    private fun getStatusBarHeight(ctx: android.content.Context): Int {
        val resourceId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) ctx.resources.getDimensionPixelSize(resourceId) else 0
    }

}
