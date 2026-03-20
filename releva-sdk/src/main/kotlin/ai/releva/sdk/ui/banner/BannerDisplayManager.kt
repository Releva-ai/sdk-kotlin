package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.types.response.BannerResponse
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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

    // View wrapping for static banners and bar overlays
    private var rootView: ViewGroup? = null              // The fragment/activity root view (untouched)
    private var outerWrapper: FrameLayout? = null        // FrameLayout for bar overlays (inside root)
    private var innerWrapper: LinearLayout? = null       // LinearLayout for static banner zones
    private var contentHolder: FrameLayout? = null       // Holds original children of root

    companion object {
        private const val TAG = "BannerDisplayManager"
        private const val BANNER_TAG_PREFIX = "releva_banner_"
    }

    /**
     * Attach to a Fragment. Will automatically clean up when the fragment's view is destroyed.
     */
    fun attach(fragment: Fragment) {
        val viewLifecycleOwner = fragment.viewLifecycleOwner
        scope = fragment.viewLifecycleOwner.lifecycleScope
        activity = fragment.activity as? AppCompatActivity
        rootView = fragment.view as? ViewGroup

        viewLifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startCollecting()
                Lifecycle.Event.ON_PAUSE -> stopCollecting()
                Lifecycle.Event.ON_DESTROY -> detach()
                else -> {}
            }
        })

        wrapChildren()
    }

    /**
     * Attach to an Activity. Will automatically clean up when the activity is destroyed.
     */
    fun attach(activity: AppCompatActivity) {
        this.activity = activity
        scope = activity.lifecycleScope
        rootView = activity.window.decorView.findViewById(android.R.id.content)

        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startCollecting()
                Lifecycle.Event.ON_PAUSE -> stopCollecting()
                Lifecycle.Event.ON_DESTROY -> detach()
                else -> {}
            }
        })

        wrapChildren()
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
        when (banner.displayType) {
            "popup" -> showPopupBanner(banner)
            "bar" -> showBarBanner(banner)
            "flyout" -> showFlyoutBanner(banner)
            "static" -> showStaticBanner(banner)
            else -> showStaticBanner(banner)
        }
        trackImpression(banner)
    }

    @Suppress("UNCHECKED_CAST")
    private fun showPopupBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val bodyValues = getDesignBodyValues(banner)
        val popupBgColor = DesignRenderer.parseColor(bodyValues["popupBackgroundColor"]) ?: Color.WHITE
        val overlayColor = getOverlayColor(banner)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val dp = ctx.resources.displayMetrics.density

        // Check for body background image
        val bgImageMap = bodyValues["backgroundImage"] as? Map<String, Any?>
        val bgImageUrl = bgImageMap?.get("url") as? String ?: ""
        val hasBgImage = bgImageUrl.isNotEmpty()

        val contentView = DesignRenderer.render(
            ctx, banner.design!!,
            transparentBody = hasBgImage
        ) { url ->
            Log.d(TAG, "Banner link tapped in popup: $url")
            dialog.dismiss()
            trackClick(banner)
            onLinkTap(url)
        }

        // Always full-screen
        val popupWidth = WindowManager.LayoutParams.MATCH_PARENT
        val popupHeight = WindowManager.LayoutParams.MATCH_PARENT

        // Outer overlay layout
        val overlayLayout = FrameLayout(ctx).apply {
            setBackgroundColor(overlayColor)
            setOnClickListener {
                dialog.dismiss()
                trackDismiss(banner)
            }
        }

        // Popup container — no border radius, full screen
        val popupContainer = FrameLayout(ctx).apply {
            setBackgroundColor(popupBgColor)
            isClickable = true // Prevent click-through
        }

        // Content wrapper with top margin to clear the close button
        val closeButtonSpace = (48 * dp).toInt() // 8dp margin + 32dp button + 8dp gap
        val contentWrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, closeButtonSpace
                )
            })
            addView(contentView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // If body has a background image, wrap the popup content with it
        if (hasBgImage) {
            val bgWrapper = DesignRenderer.wrapWithBackgroundImage(
                ctx, contentWrapper, bgImageMap!!, forceCover = true
            )
            popupContainer.addView(bgWrapper, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            popupContainer.addView(contentWrapper, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // Close button
        val closeButton = buildCloseButton(ctx, banner) {
            dialog.dismiss()
            closeBanner(banner)
        }
        popupContainer.addView(closeButton, FrameLayout.LayoutParams(
            (32 * dp).toInt(), (32 * dp).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (8 * dp).toInt()
            rightMargin = (8 * dp).toInt()
        })

        val popupParams = FrameLayout.LayoutParams(popupWidth, popupHeight, Gravity.CENTER)
        overlayLayout.addView(popupContainer, popupParams)

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

    private fun showBarBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val root = outerWrapper ?: return
        val isBottom = banner.displayPosition == "bottom"

        val dp = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val verticalPadding = (12 * dp).toInt()
        val horizontalPadding = (16 * dp).toInt()
        val availableWidth = screenWidth - horizontalPadding * 2

        val contentView = DesignRenderer.render(ctx, banner.design!!, maxWidthPx = availableWidth) { url ->
            trackClick(banner)
            onLinkTap(url)
        }
        val statusBarPad = if (!isBottom) getStatusBarHeight(ctx) else 0

        val barLayout = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            elevation = 10 * dp
        }

        val contentWrapper = FrameLayout(ctx).apply {
            setPadding(horizontalPadding, statusBarPad + verticalPadding, horizontalPadding, verticalPadding)
            addView(contentView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        barLayout.addView(contentWrapper, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
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
            topMargin = if (isBottom) -closeOverlap else statusBarPad + (4 * dp).toInt()
            rightMargin = -closeOverlap
        })
        barLayout.clipChildren = false
        barLayout.clipToPadding = false

        // Add to outer wrapper as overlay
        root.addView(barLayout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (isBottom) Gravity.BOTTOM else Gravity.TOP
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun showFlyoutBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val overlayColor = getOverlayColor(banner)
        val isLeft = banner.displayPosition == "left"

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val dp = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val flyoutWidth = (screenWidth * 0.8).toInt()

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
            setBackgroundColor(Color.WHITE)
            elevation = 10 * dp
            isClickable = true
            setPadding(0, getStatusBarHeight(ctx), 0, 0)
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
        val scrollView = ScrollView(ctx).apply {
            addView(contentView, ViewGroup.LayoutParams(
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
            // Make flyout container background transparent so bg image shows through
            flyoutContainer.setBackgroundColor(Color.TRANSPARENT)
            bgWrapper
        } else {
            flyoutContainer
        }

        val flyoutGravity = if (isLeft) Gravity.START else Gravity.END
        overlayLayout.addView(flyoutView, FrameLayout.LayoutParams(
            flyoutWidth, ViewGroup.LayoutParams.MATCH_PARENT, flyoutGravity
        ))

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
            ?: DesignRenderer.parseColor(banner.cssStyles["closeButtonBackgroudColor"])
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

    private fun resolveDimension(value: Any?, relativeTo: Float, dp: Float = 1f): Float? {
        if (value == null) return null
        val str = value.toString().trim()
        if (str.endsWith("%")) {
            val percent = str.replace("%", "").toFloatOrNull()
            if (percent != null) return relativeTo * percent / 100f
        }
        val raw = str.replace(Regex("[a-zA-Z%]"), "").trim().toFloatOrNull()
        return raw?.times(dp)
    }
}
