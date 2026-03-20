package ai.releva.sdk.ui.banner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.net.URL
import java.util.concurrent.Executors

/**
 * Renders an Unlayer design JSON (body > rows > columns > contents) as native Android Views.
 */
object DesignRenderer {

    private val imageExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val RGBA_REGEX = Regex("""rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)""")
    private val RGB_REGEX = Regex("""rgb\((\d+),\s*(\d+),\s*(\d+)\)""")

    fun loadImageAsync(url: String, imageView: ImageView) {
        imageExecutor.execute {
            try {
                // Download bytes once to support two-pass decode without a second HTTP connection
                val bytes = URL(url).openConnection().run {
                    connectTimeout = 10000
                    readTimeout = 10000
                    connect()
                    val data = getInputStream().readBytes()
                    data
                }

                // First pass: get image dimensions without allocating a full bitmap
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

                // Cap longest side at 1024px
                val maxDim = 1024
                var sampleSize = 1
                val origMax = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                while (origMax / (sampleSize * 2) >= maxDim) sampleSize *= 2

                // Second pass: decode at reduced size
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)

                if (bitmap != null) {
                    mainHandler.post { imageView.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {
                // Image loading failed - leave imageView empty
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun render(
        context: Context,
        design: Map<String, Any?>,
        maxWidthPx: Int = context.resources.displayMetrics.widthPixels,
        transparentBody: Boolean = false,
        onLinkTap: ((String) -> Unit)? = null
    ): View {
        val body = design["body"] as? Map<String, Any?> ?: return View(context)
        val bodyValues = body["values"] as? Map<String, Any?> ?: emptyMap()
        val rows = body["rows"] as? List<*> ?: emptyList<Any>()

        val backgroundColor = parseColor(bodyValues["backgroundColor"]) ?: Color.TRANSPARENT
        val textColor = parseColor(bodyValues["textColor"]) ?: Color.BLACK
        val fontFamilyMap = bodyValues["fontFamily"] as? Map<String, Any?>
        val fontFamily = fontFamilyMap?.get("value") as? String ?: "sans-serif"

        val contentWidthRaw = bodyValues["contentWidth"]?.toString()?.trim()
        val isPercentWidth = contentWidthRaw?.endsWith("%") == true
        val contentWidthPx = if (isPercentWidth) null
            else parseDimension(bodyValues["contentWidth"], context)?.toInt()?.coerceAtMost(maxWidthPx)

        val bgImageMap = bodyValues["backgroundImage"] as? Map<String, Any?>
        val hasBgImage = !transparentBody && bgImageMap != null
            && (bgImageMap["url"] as? String)?.isNotEmpty() == true

        val outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (!transparentBody && !hasBgImage) {
                setBackgroundColor(backgroundColor)
            }
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (contentWidthPx != null) {
                LinearLayout.LayoutParams(contentWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        for (row in rows) {
            val rowMap = row as? Map<String, Any?> ?: continue
            innerLayout.addView(buildRow(context, rowMap, textColor, fontFamily, onLinkTap))
        }

        outerLayout.addView(innerLayout)

        if (hasBgImage) {
            return wrapWithBackgroundImage(context, outerLayout, bgImageMap!!)
        }

        return outerLayout
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildRow(
        context: Context,
        row: Map<String, Any?>,
        textColor: Int,
        fontFamily: String,
        onLinkTap: ((String) -> Unit)?
    ): View {
        val columns = row["columns"] as? List<*> ?: emptyList<Any>()
        val cells = row["cells"] as? List<*> ?: emptyList<Any>()
        val rowValues = row["values"] as? Map<String, Any?> ?: emptyMap()

        val bgColor = parseColor(rowValues["backgroundColor"])
        val columnsBgColor = parseColor(rowValues["columnsBackgroundColor"])
        val padding = parseEdgeInsets(rowValues["padding"])

        val rowBgImageMap = rowValues["backgroundImage"] as? Map<String, Any?>
        val hasRowBgImage = rowBgImageMap != null
            && (rowBgImageMap["url"] as? String)?.isNotEmpty() == true

        val layout: View = if (columns.size == 1) {
            val colMap = columns[0] as? Map<String, Any?> ?: return View(context)
            buildColumn(context, colMap, textColor, fontFamily, onLinkTap)
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in columns.indices) {
                    val colMap = columns[i] as? Map<String, Any?> ?: continue
                    val flex = if (i < cells.size) (cells[i] as? Number)?.toInt() ?: 1 else 1
                    val colView = buildColumn(context, colMap, textColor, fontFamily, onLinkTap)
                    colView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, flex.toFloat())
                    addView(colView)
                }
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        val wrapper = FrameLayout(context).apply {
            if (!hasRowBgImage) {
                (bgColor ?: columnsBgColor)?.let { setBackgroundColor(it) }
            }
            padding?.let { setPadding(it[3], it[0], it[1], it[2]) }
            addView(layout, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        if (hasRowBgImage) {
            return wrapWithBackgroundImage(context, wrapper, rowBgImageMap!!)
        }

        return wrapper
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildColumn(
        context: Context,
        column: Map<String, Any?>,
        textColor: Int,
        fontFamily: String,
        onLinkTap: ((String) -> Unit)?
    ): View {
        val contents = column["contents"] as? List<*> ?: emptyList<Any>()
        val colValues = column["values"] as? Map<String, Any?> ?: emptyMap()

        val backgroundColor = parseColor(colValues["backgroundColor"])
        val padding = parseEdgeInsets(colValues["padding"])
        val borderRadius = parseDimensionRaw(colValues["borderRadius"])

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            if (backgroundColor != null || borderRadius != null) {
                background = GradientDrawable().apply {
                    backgroundColor?.let { setColor(it) }
                    borderRadius?.let { cornerRadius = it }
                }
            }

            padding?.let { setPadding(it[3], it[0], it[1], it[2]) }
        }

        for (content in contents) {
            val contentMap = content as? Map<String, Any?> ?: continue
            layout.addView(buildContent(context, contentMap, textColor, fontFamily, onLinkTap))
        }

        return layout
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildContent(
        context: Context,
        content: Map<String, Any?>,
        textColor: Int,
        fontFamily: String,
        onLinkTap: ((String) -> Unit)?
    ): View {
        val type = content["type"] as? String ?: ""
        val values = content["values"] as? Map<String, Any?> ?: emptyMap()
        val containerPadding = parseEdgeInsets(values["containerPadding"])

        val child = when (type) {
            "image" -> buildImage(context, values, onLinkTap)
            "text" -> buildText(context, values, textColor, fontFamily)
            "heading" -> buildHeading(context, values, textColor, fontFamily)
            "button" -> buildButton(context, values, fontFamily, onLinkTap)
            "carousel" -> buildCarousel(context, content, onLinkTap)
            "divider" -> buildDivider(context, values)
            else -> View(context)
        }

        val wrapper = FrameLayout(context).apply {
            containerPadding?.let { setPadding(it[3], it[0], it[1], it[2]) }
            addView(child, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        return wrapper
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildImage(
        context: Context,
        values: Map<String, Any?>,
        onLinkTap: ((String) -> Unit)?
    ): View {
        val src = values["src"] as? Map<String, Any?> ?: emptyMap()
        val url = src["url"] as? String ?: ""
        val action = values["action"] as? Map<String, Any?>
        val actionValues = action?.get("values") as? Map<String, Any?> ?: emptyMap()
        val href = actionValues["href"] as? String ?: ""

        if (url.isEmpty()) return View(context)

        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        loadImageAsync(url, imageView)

        if (href.isNotEmpty() && onLinkTap != null) {
            imageView.setOnClickListener { onLinkTap(href) }
        }

        return imageView
    }

    private fun buildText(
        context: Context,
        values: Map<String, Any?>,
        defaultTextColor: Int,
        fontFamily: String
    ): View {
        val htmlText = values["text"] as? String ?: ""
        val spanned = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_COMPACT)
        if (spanned.toString().trim().isEmpty()) return View(context)

        val fontSize = parseDimensionRaw(values["fontSize"])
        val textAlign = parseTextAlign(values["textAlign"])
        val color = parseColor(values["color"]) ?: parseColor(values["textColor"]) ?: defaultTextColor
        val lineHeight = parseLineHeight(values["lineHeight"])

        return TextView(context).apply {
            this.text = spanned
            setTextColor(color)
            gravity = textAlign
            fontSize?.let { setTextSize(TypedValue.COMPLEX_UNIT_PX, it * resources.displayMetrics.density) }
            lineHeight?.let { setLineSpacing(0f, it) }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun buildHeading(
        context: Context,
        values: Map<String, Any?>,
        defaultTextColor: Int,
        fontFamily: String
    ): View {
        val htmlText = values["text"] as? String ?: ""
        val spanned = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_COMPACT)
        if (spanned.toString().trim().isEmpty()) return View(context)

        val headingType = values["headingType"] as? String ?: "h1"
        val fontSize = parseDimensionRaw(values["fontSize"]) ?: getHeadingFontSize(headingType)
        val textAlign = parseTextAlign(values["textAlign"])
        val color = parseColor(values["color"]) ?: parseColor(values["textColor"]) ?: defaultTextColor
        val lineHeight = parseLineHeight(values["lineHeight"])

        return TextView(context).apply {
            this.text = spanned
            setTextColor(color)
            gravity = textAlign
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * resources.displayMetrics.density)
            setTypeface(null, Typeface.BOLD)
            lineHeight?.let { setLineSpacing(0f, it) }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildButton(
        context: Context,
        values: Map<String, Any?>,
        fontFamily: String,
        onLinkTap: ((String) -> Unit)?
    ): View {
        val htmlText = values["text"] as? String ?: ""
        val text = stripHtml(htmlText)
        if (text.isEmpty()) return View(context)

        val href = values["href"] as? Map<String, Any?>
        val hrefValues = href?.get("values") as? Map<String, Any?> ?: emptyMap()
        val url = hrefValues["href"] as? String ?: ""

        val buttonColors = values["buttonColors"] as? Map<String, Any?> ?: emptyMap()
        val bgColor = parseColor(buttonColors["backgroundColor"]) ?: Color.parseColor("#3AAEE0")
        val textColor = parseColor(buttonColors["color"]) ?: Color.WHITE

        val fontSize = parseDimensionRaw(values["fontSize"]) ?: 14f
        val padding = parseEdgeInsets(values["padding"])
        val borderRadius = parseDimensionRaw(values["borderRadius"]) ?: 0f
        val textAlign = parseTextAlign(values["textAlign"])

        val size = values["size"] as? Map<String, Any?> ?: emptyMap()
        val autoWidth = size["autoWidth"] as? Boolean ?: true

        val dp = context.resources.displayMetrics.density

        val textView = TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * dp)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        // Container with background and padding — click target (like Flutter's GestureDetector + Container)
        val buttonContainer = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = borderRadius * dp
            }
            if (padding != null) {
                setPadding(
                    (padding[3] * dp).toInt(),
                    (padding[0] * dp).toInt(),
                    (padding[1] * dp).toInt(),
                    (padding[2] * dp).toInt()
                )
            } else {
                setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
            }
            addView(textView)
            if (url.isNotEmpty() && onLinkTap != null) {
                setOnClickListener { onLinkTap(url) }
            }
        }

        if (autoWidth) {
            val wrapper = FrameLayout(context).apply {
                val gravity = when (textAlign) {
                    Gravity.CENTER -> Gravity.CENTER_HORIZONTAL
                    Gravity.END -> Gravity.END
                    else -> Gravity.START
                }
                addView(buttonContainer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    gravity
                ))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return wrapper
        }

        buttonContainer.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return buttonContainer
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDivider(context: Context, values: Map<String, Any?>): View {
        val border = values["border"] as? Map<String, Any?> ?: emptyMap()
        val borderTopWidth = parseDimensionRaw(border["borderTopWidth"]) ?: 1f
        val borderTopColor = parseColor(border["borderTopColor"]) ?: Color.parseColor("#BBBBBB")

        return View(context).apply {
            setBackgroundColor(borderTopColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (borderTopWidth * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildCarousel(
        context: Context,
        content: Map<String, Any?>,
        onLinkTap: ((String) -> Unit)?
    ): View {
        val values = content["values"] as? Map<String, Any?> ?: emptyMap()
        val embedded = content["embedded"] as? Map<String, Any?> ?: emptyMap()
        val imagesMap = embedded["images"] as? Map<String, Any?> ?: emptyMap()
        val imagesList = imagesMap["values"] as? List<Map<String, Any?>> ?: emptyList()

        if (imagesList.isEmpty()) return View(context)

        val autoplay = values["autoplay"] as? Boolean ?: false
        val loop = values["loop"] as? Boolean ?: false
        val showPreviews = values["showPreviews"] as? Boolean ?: false
        val previewWidth = parseDimensionRaw(values["previewWidth"])?.toInt() ?: 100

        val dp = context.resources.displayMetrics.density

        // Calculate aspect ratio from first image
        val firstSrc = imagesList.first()["src"] as? Map<String, Any?> ?: emptyMap()
        val imgWidth = (firstSrc["width"] as? Number)?.toFloat() ?: 16f
        val imgHeight = (firstSrc["height"] as? Number)?.toFloat() ?: 9f
        val aspectRatio = imgWidth / imgHeight

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ViewFlipper for images
        val flipper = ViewFlipper(context).apply {
            tag = "releva_interactive"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Track current page for indicators
        var currentPage = 0

        // Add image views
        for (image in imagesList) {
            val src = image["src"] as? Map<String, Any?> ?: emptyMap()
            val url = src["url"] as? String ?: ""
            val action = image["action"] as? Map<String, Any?>
            val actionValues = action?.get("values") as? Map<String, Any?> ?: emptyMap()
            val href = actionValues["href"] as? String ?: ""

            val imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            if (url.isNotEmpty()) loadImageAsync(url, imageView)

            if (href.isNotEmpty() && onLinkTap != null) {
                imageView.setOnClickListener { onLinkTap(href) }
            }

            flipper.addView(imageView)
        }

        // Wrap flipper in a fixed aspect ratio container
        val screenWidth = context.resources.displayMetrics.widthPixels
        val flipperHeight = (screenWidth / aspectRatio).toInt()
        val flipperWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                flipperHeight
            )
        }

        // Indicator callback — assigned after indicators are created
        var updateIndicators: ((Int) -> Unit)? = null

        // Animations
        val animDuration = 300L
        fun slideInFromRight(): android.view.animation.Animation =
            android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, 1f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f
            ).apply { duration = animDuration }

        fun slideOutToLeft(): android.view.animation.Animation =
            android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, -1f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f
            ).apply { duration = animDuration }

        fun slideInFromLeft(): android.view.animation.Animation =
            android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, -1f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f
            ).apply { duration = animDuration }

        fun slideOutToRight(): android.view.animation.Animation =
            android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 1f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f
            ).apply { duration = animDuration }

        fun goNext() {
            if (flipper.displayedChild < imagesList.size - 1) {
                flipper.inAnimation = slideInFromRight()
                flipper.outAnimation = slideOutToLeft()
                flipper.showNext()
                currentPage = flipper.displayedChild
                updateIndicators?.invoke(currentPage)
            } else if (loop) {
                flipper.inAnimation = slideInFromRight()
                flipper.outAnimation = slideOutToLeft()
                flipper.displayedChild = 0
                currentPage = 0
                updateIndicators?.invoke(currentPage)
            }
        }

        fun goPrevious() {
            if (flipper.displayedChild > 0) {
                flipper.inAnimation = slideInFromLeft()
                flipper.outAnimation = slideOutToRight()
                flipper.showPrevious()
                currentPage = flipper.displayedChild
                updateIndicators?.invoke(currentPage)
            } else if (loop) {
                flipper.inAnimation = slideInFromLeft()
                flipper.outAnimation = slideOutToRight()
                flipper.displayedChild = imagesList.size - 1
                currentPage = imagesList.size - 1
                updateIndicators?.invoke(currentPage)
            }
        }

        // Swipe and tap gesture detection
        flipper.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.x - startX
                        if (dx < -50) {
                            // Swipe left → next
                            goNext()
                        } else if (dx > 50) {
                            // Swipe right → previous
                            goPrevious()
                        } else {
                            // Tap — left half goes back, right half goes forward
                            val halfWidth = v.width / 2
                            if (event.x < halfWidth) {
                                goPrevious()
                            } else {
                                goNext()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        flipperWrapper.addView(flipper)
        rootLayout.addView(flipperWrapper)

        if (imagesList.size > 1) {
            if (showPreviews) {
                // Preview strip
                val previewContainer = HorizontalScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * dp).toInt() }
                    isHorizontalScrollBarEnabled = false
                }

                val previewRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                val previewViews = mutableListOf<View>()
                for ((index, image) in imagesList.withIndex()) {
                    val src = image["src"] as? Map<String, Any?> ?: emptyMap()
                    val url = src["url"] as? String ?: ""
                    val pw = (previewWidth * dp).toInt()
                    val ph = (previewWidth * 0.75 * dp).toInt()

                    val border = GradientDrawable().apply {
                        setStroke((2 * dp).toInt(), if (index == 0) Color.DKGRAY else Color.TRANSPARENT)
                        cornerRadius = 4 * dp
                    }

                    val previewImage = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(pw, ph).apply {
                            setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                        }
                        background = border
                        setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                        setOnClickListener {
                            flipper.displayedChild = index
                            currentPage = index
                            updateIndicators?.invoke(index)
                        }
                    }

                    if (url.isNotEmpty()) loadImageAsync(url, previewImage)
                    previewRow.addView(previewImage)
                    previewViews.add(previewImage)
                }

                updateIndicators = { page ->
                    for ((i, pv) in previewViews.withIndex()) {
                        (pv.background as? GradientDrawable)?.setStroke(
                            (2 * dp).toInt(),
                            if (i == page) Color.DKGRAY else Color.TRANSPARENT
                        )
                    }
                }

                previewContainer.addView(previewRow)
                rootLayout.addView(previewContainer)
            } else {
                // Dot indicators
                val dotContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * dp).toInt() }
                }

                val dots = mutableListOf<View>()
                for (i in imagesList.indices) {
                    val dot = View(context).apply {
                        val size = (8 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (i == 0) Color.DKGRAY else Color.LTGRAY)
                        }
                    }
                    dotContainer.addView(dot)
                    dots.add(dot)
                }

                updateIndicators = { page ->
                    for ((i, dot) in dots.withIndex()) {
                        (dot.background as? GradientDrawable)?.setColor(
                            if (i == page) Color.DKGRAY else Color.LTGRAY
                        )
                    }
                }

                rootLayout.addView(dotContainer)
            }
        }

        // Autoplay
        if (autoplay && imagesList.size > 1) {
            val handler = Handler(Looper.getMainLooper())
            val autoplayRunnable = object : Runnable {
                override fun run() {
                    val next = flipper.displayedChild + 1
                    if (next < imagesList.size) {
                        goNext()
                        handler.postDelayed(this, 3000)
                    } else if (loop) {
                        goNext()
                        handler.postDelayed(this, 3000)
                    }
                }
            }
            handler.postDelayed(autoplayRunnable, 3000)
            // Cancel autoplay when the view is detached to prevent leaks and null-pointer crashes
            flipper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    handler.removeCallbacks(autoplayRunnable)
                }
            })
        }

        return rootLayout
    }

    /**
     * Wraps a view in a FrameLayout with an ImageView behind it showing a background image.
     * The content view's background is made transparent so the image shows through.
     */
    @Suppress("UNCHECKED_CAST")
    fun wrapWithBackgroundImage(
        context: Context,
        contentView: View,
        bgImageMap: Map<String, Any?>,
        forceCover: Boolean = false
    ): View {
        val url = bgImageMap["url"] as? String ?: return contentView
        if (url.isEmpty()) return contentView

        val scaleType = if (forceCover) ImageView.ScaleType.CENTER_CROP else {
            when (bgImageMap["size"] as? String ?: "cover") {
                "contain" -> ImageView.ScaleType.FIT_CENTER
                "custom" -> ImageView.ScaleType.FIT_CENTER
                else -> ImageView.ScaleType.CENTER_CROP
            }
        }

        val bgImageView = ImageView(context).apply {
            this.scaleType = scaleType
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val wrapper = FrameLayout(context).apply {
            layoutParams = contentView.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        contentView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        wrapper.addView(bgImageView)
        wrapper.addView(contentView)

        loadImageAsync(url, bgImageView)

        return wrapper
    }

    // --- Utility functions ---

    fun parseColor(value: Any?): Int? {
        if (value == null) return null
        val str = value.toString().trim()
        if (str.isEmpty()) return null

        // rgba(r, g, b, a)
        val rgbaMatch = RGBA_REGEX.find(str)
        if (rgbaMatch != null) {
            val r = rgbaMatch.groupValues[1].toInt()
            val g = rgbaMatch.groupValues[2].toInt()
            val b = rgbaMatch.groupValues[3].toInt()
            val a = (rgbaMatch.groupValues[4].toFloat() * 255).toInt()
            return Color.argb(a, r, g, b)
        }

        // rgb(r, g, b)
        val rgbMatch = RGB_REGEX.find(str)
        if (rgbMatch != null) {
            val r = rgbMatch.groupValues[1].toInt()
            val g = rgbMatch.groupValues[2].toInt()
            val b = rgbMatch.groupValues[3].toInt()
            return Color.argb(255, r, g, b)
        }

        // hex color
        if (str.startsWith("#")) {
            return try {
                Color.parseColor(str)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun parseDimensionRaw(value: Any?): Float? {
        if (value == null) return null
        val str = value.toString().replace(Regex("[a-zA-Z%]"), "").trim()
        return str.toFloatOrNull()
    }

    private fun parseDimension(value: Any?, context: Context): Float? {
        val raw = parseDimensionRaw(value) ?: return null
        return raw * context.resources.displayMetrics.density
    }

    /**
     * Parses a CSS padding string into [top, right, bottom, left] as raw values.
     */
    private fun parseEdgeInsets(value: Any?): IntArray? {
        if (value == null) return null
        val str = value.toString().trim()
        if (str.isEmpty()) return null

        val parts = str.split(Regex("\\s+")).mapNotNull { parseDimensionRaw(it)?.toInt() }
        return when (parts.size) {
            1 -> intArrayOf(parts[0], parts[0], parts[0], parts[0])
            2 -> intArrayOf(parts[0], parts[1], parts[0], parts[1])
            3 -> intArrayOf(parts[0], parts[1], parts[2], parts[1])
            4 -> intArrayOf(parts[0], parts[1], parts[2], parts[3])
            else -> null
        }
    }

    private fun parseTextAlign(value: Any?): Int {
        return when (value?.toString()) {
            "center" -> Gravity.CENTER
            "right" -> Gravity.END
            "justify" -> Gravity.START // Android doesn't have justify for plain text
            else -> Gravity.START
        }
    }

    private fun parseLineHeight(value: Any?): Float? {
        if (value == null) return null
        val str = value.toString()
        if (str.endsWith("%")) {
            val parsed = str.replace("%", "").toFloatOrNull()
            if (parsed != null) return parsed / 100f
        }
        return parseDimensionRaw(value)
    }

    private fun getHeadingFontSize(headingType: String): Float {
        return when (headingType) {
            "h1" -> 32f
            "h2" -> 28f
            "h3" -> 24f
            "h4" -> 20f
            "h5" -> 18f
            "h6" -> 16f
            else -> 32f
        }
    }

    private fun stripHtml(html: String): String {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }
}
