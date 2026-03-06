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
 * Usage:
 * ```kotlin
 * val bannerDisplay = BannerDisplayManager(client, targetSelector = "#home-content")
 * bannerDisplay.attach(activity) // or attach(fragment)
 * ```
 */
class BannerDisplayManager(
    private val client: RelevaClient,
    private val targetSelector: String,
    private val onLinkTap: ((String) -> Unit)? = null
) {
    private val displayedBanners = mutableMapOf<String, BannerResponse>()
    private var collectJob: Job? = null
    private var activity: AppCompatActivity? = null
    private var rootView: ViewGroup? = null
    private var barContainer: FrameLayout? = null
    private var staticContainer: LinearLayout? = null
    private var scope: CoroutineScope? = null

    companion object {
        private const val TAG = "BannerDisplayManager"
    }

    /**
     * Attach to a Fragment. Will automatically clean up when the fragment's view is destroyed.
     */
    fun attach(fragment: Fragment) {
        val viewLifecycleOwner = fragment.viewLifecycleOwner
        scope = fragment.viewLifecycleOwner.lifecycleScope
        activity = fragment.activity as? AppCompatActivity

        viewLifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startCollecting()
                Lifecycle.Event.ON_PAUSE -> stopCollecting()
                Lifecycle.Event.ON_DESTROY -> detach()
                else -> {}
            }
        })

        // Find the root view to overlay banners on
        rootView = fragment.view as? ViewGroup
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
    }

    /**
     * Set a container where static banners will be inserted.
     */
    fun setStaticContainer(container: LinearLayout) {
        staticContainer = container
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

    private fun detach() {
        stopCollecting()
        displayedBanners.clear()
        barContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        barContainer = null
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

    private fun showPopupBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val bodyValues = getDesignBodyValues(banner)
        val popupBgColor = DesignRenderer.parseColor(bodyValues["popupBackgroundColor"]) ?: Color.WHITE
        val overlayColor = getOverlayColor(banner)
        val popupPosition = bodyValues["popupPosition"]?.toString() ?: ""
        val isFullScreen = popupPosition == "full-screen"
        val borderRadius = if (isFullScreen) 0f else getBorderRadius(banner)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val contentView = DesignRenderer.render(ctx, banner.design!!) { url ->
            trackClick(banner)
            onLinkTap?.invoke(url)
        }

        val dp = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels

        val popupWidth: Int
        val popupHeight: Int

        if (isFullScreen) {
            popupWidth = screenWidth
            popupHeight = screenHeight
        } else {
            val contentWidthVal = resolveDimension(bodyValues["contentWidth"], screenWidth.toFloat())
                ?: resolveDimension(bodyValues["popupWidth"], screenWidth.toFloat())
                ?: (400 * dp)
            popupWidth = contentWidthVal.toInt().coerceAtMost(screenWidth)

            val popupHeightVal = bodyValues["popupHeight"]
            popupHeight = if (popupHeightVal == "auto" || popupHeightVal == null) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                resolveDimension(popupHeightVal, screenHeight.toFloat())?.toInt()
                    ?: ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        // Outer overlay layout
        val overlayLayout = FrameLayout(ctx).apply {
            setBackgroundColor(overlayColor)
            setOnClickListener {
                dialog.dismiss()
                trackDismiss(banner)
            }
        }

        // Popup container
        val popupContainer = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(popupBgColor)
                cornerRadius = borderRadius * dp
            }
            clipToOutline = true
            isClickable = true // Prevent click-through
        }

        // ScrollView for content
        val scrollView = ScrollView(ctx).apply {
            addView(contentView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        popupContainer.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

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

        dialog.setOnDismissListener { displayedBanners.remove(banner.token) }
        dialog.show()
    }

    private fun showBarBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val root = rootView ?: return
        val isBottom = banner.displayPosition == "bottom"

        val contentView = DesignRenderer.render(ctx, banner.design!!) { url ->
            trackClick(banner)
            onLinkTap?.invoke(url)
        }

        val dp = ctx.resources.displayMetrics.density

        val barLayout = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            elevation = 10 * dp
        }

        val padding = (12 * dp).toInt()
        val horizontalPadding = (16 * dp).toInt()
        val contentWrapper = FrameLayout(ctx).apply {
            setPadding(horizontalPadding, padding, horizontalPadding, padding)
            addView(contentView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        barLayout.addView(contentWrapper, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Close button
        val closeButton = buildCloseButton(ctx, banner) {
            (barLayout.parent as? ViewGroup)?.removeView(barLayout)
            closeBanner(banner)
        }
        barLayout.addView(closeButton, FrameLayout.LayoutParams(
            (32 * dp).toInt(), (32 * dp).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (8 * dp).toInt()
            rightMargin = (8 * dp).toInt()
        })

        // Add to root as overlay
        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (isBottom) Gravity.BOTTOM else Gravity.TOP
        )

        // If root is not a FrameLayout, wrap in one
        if (root is FrameLayout) {
            root.addView(barLayout, barParams)
        } else {
            // For other ViewGroup types, use window overlay approach
            val container = FrameLayout(ctx).apply {
                addView(barLayout, barParams)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(container)
        }
    }

    private fun showFlyoutBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val overlayColor = getOverlayColor(banner)
        val isLeft = banner.displayPosition == "left"

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val contentView = DesignRenderer.render(ctx, banner.design!!) { url ->
            trackClick(banner)
            onLinkTap?.invoke(url)
        }

        val dp = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val flyoutWidth = (screenWidth * 0.8).toInt()

        val overlayLayout = FrameLayout(ctx).apply {
            setBackgroundColor(overlayColor)
            setOnClickListener {
                dialog.dismiss()
                trackDismiss(banner)
            }
        }

        val flyoutContainer = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            elevation = 10 * dp
            isClickable = true
        }

        val scrollView = ScrollView(ctx).apply {
            addView(contentView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        flyoutContainer.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Close button
        val closeButtonGravity = if (isLeft) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
        val closeButton = buildCloseButton(ctx, banner) {
            dialog.dismiss()
            closeBanner(banner)
        }
        flyoutContainer.addView(closeButton, FrameLayout.LayoutParams(
            (32 * dp).toInt(), (32 * dp).toInt(),
            closeButtonGravity
        ).apply {
            topMargin = (40 * dp).toInt()
            if (isLeft) leftMargin = (8 * dp).toInt() else rightMargin = (8 * dp).toInt()
        })

        val flyoutGravity = if (isLeft) Gravity.START else Gravity.END
        overlayLayout.addView(flyoutContainer, FrameLayout.LayoutParams(
            flyoutWidth, ViewGroup.LayoutParams.MATCH_PARENT, flyoutGravity
        ))

        dialog.setContentView(overlayLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        dialog.setOnDismissListener { displayedBanners.remove(banner.token) }
        dialog.show()
    }

    private fun showStaticBanner(banner: BannerResponse) {
        val ctx = activity ?: return
        val container = staticContainer ?: return

        val contentView = DesignRenderer.render(ctx, banner.design!!) { url ->
            trackClick(banner)
            onLinkTap?.invoke(url)
        }

        contentView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        container.addView(contentView)
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

    private fun getBorderRadius(banner: BannerResponse): Float {
        val bodyValues = getDesignBodyValues(banner)
        val str = bodyValues["borderRadius"]?.toString()?.replace(Regex("[a-zA-Z%]"), "")?.trim()
        str?.toFloatOrNull()?.let { return it }
        val cssStr = banner.cssStyles["closeButtonBorderRadius"]?.toString()
        cssStr?.toFloatOrNull()?.let { return it }
        return 8f
    }

    private fun resolveDimension(value: Any?, relativeTo: Float): Float? {
        if (value == null) return null
        val str = value.toString().trim()
        if (str.endsWith("%")) {
            val percent = str.replace("%", "").toFloatOrNull()
            if (percent != null) return relativeTo * percent / 100f
        }
        return str.replace(Regex("[a-zA-Z%]"), "").trim().toFloatOrNull()
    }
}
