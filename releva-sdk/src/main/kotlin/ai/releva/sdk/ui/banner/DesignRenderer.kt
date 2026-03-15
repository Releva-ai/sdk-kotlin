package ai.releva.sdk.ui.banner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
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

    private val imageExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadImageAsync(url: String, imageView: ImageView) {
        imageExecutor.execute {
            try {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val inputStream = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap != null) {
                    mainHandler.post { imageView.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {
                // Image loading failed - leave imageView empty
            }
        }
    }

    fun render(
        context: Context,
        design: Map<String, Any?>,
        maxWidthPx: Int = context.resources.displayMetrics.widthPixels,
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

        val outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
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
        return outerLayout
    }

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
            (bgColor ?: columnsBgColor)?.let { setBackgroundColor(it) }
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

        return wrapper
    }

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
        val text = stripHtml(htmlText)
        if (text.isEmpty()) return View(context)

        val fontSize = parseDimensionRaw(values["fontSize"])
        val textAlign = parseTextAlign(values["textAlign"])
        val color = parseColor(values["textColor"]) ?: defaultTextColor
        val lineHeight = parseLineHeight(values["lineHeight"])

        return TextView(context).apply {
            this.text = text
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
        val text = stripHtml(htmlText)
        if (text.isEmpty()) return View(context)

        val headingType = values["headingType"] as? String ?: "h1"
        val fontSize = parseDimensionRaw(values["fontSize"]) ?: getHeadingFontSize(headingType)
        val textAlign = parseTextAlign(values["textAlign"])
        val color = parseColor(values["textColor"]) ?: defaultTextColor
        val lineHeight = parseLineHeight(values["lineHeight"])

        return TextView(context).apply {
            this.text = text
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
            Log.d("DesignRenderer", "Button '$text': url='$url', onLinkTap=${onLinkTap != null}")
            if (url.isNotEmpty() && onLinkTap != null) {
                setOnClickListener {
                    Log.d("DesignRenderer", "Button clicked! url=$url")
                    onLinkTap(url)
                }
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

    // --- Utility functions ---

    fun parseColor(value: Any?): Int? {
        if (value == null) return null
        val str = value.toString().trim()
        if (str.isEmpty()) return null

        // rgba(r, g, b, a)
        val rgbaMatch = Regex("""rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)""").find(str)
        if (rgbaMatch != null) {
            val r = rgbaMatch.groupValues[1].toInt()
            val g = rgbaMatch.groupValues[2].toInt()
            val b = rgbaMatch.groupValues[3].toInt()
            val a = (rgbaMatch.groupValues[4].toFloat() * 255).toInt()
            return Color.argb(a, r, g, b)
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
