package ai.releva.sdk.ui.banner

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Padding in a design is expressed in CSS pixels, which are density-independent: the
 * same units Unlayer previews it in, and the points SwiftUI's `.padding` takes on iOS.
 * Android's `setPadding` takes device pixels, so a design's values have to be scaled by
 * the display density on the way in.
 *
 * Three of the four call sites used to pass the parsed values straight through, so on a
 * 3x screen every design rendered at a third of its intended padding — enough to read as
 * "the text is flush against the edge" while the design itself was correct.
 *
 * `qualifiers = "xxhdpi"` pins density to 3.0 so the assertions can be exact; at the
 * default 1.0 a missing multiply is invisible, which is the whole reason this needs a
 * density that isn't 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "xxhdpi")
class DesignRendererPaddingTest {

    private lateinit var context: Context
    private val density get() = context.resources.displayMetrics.density

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun designWith(content: Map<String, Any?>, rowValues: Map<String, Any?> = emptyMap()) =
        mapOf(
            "body" to mapOf(
                "rows" to listOf(
                    mapOf(
                        "columns" to listOf(mapOf("contents" to listOf(content))),
                        "values" to rowValues
                    )
                ),
                "values" to mapOf<String, Any?>()
            )
        )

    /** Every view in the tree, so a test can find the one that carries the padding. */
    private fun flatten(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) }
        } else {
            emptyList()
        }

    @Test
    fun `containerPadding is scaled by display density`() {
        assertEquals("fixture assumes a 3x screen", 3.0f, density, 0.01f)

        val view = DesignRenderer.render(
            context,
            designWith(
                mapOf(
                    "type" to "text",
                    "values" to mapOf("text" to "<p>hello</p>", "containerPadding" to "10px")
                )
            )
        )

        val expected = (10 * density).toInt()  // 30px, not 10
        val padded = flatten(view).filter { it.paddingLeft == expected && it.paddingTop == expected }
        assertTrue(
            "no view padded to ${expected}px; found " +
                flatten(view).map { it.paddingLeft }.filter { it != 0 },
            padded.isNotEmpty()
        )
    }

    @Test
    fun `row padding is scaled by display density`() {
        val view = DesignRenderer.render(
            context,
            designWith(
                mapOf("type" to "text", "values" to mapOf("text" to "<p>hello</p>")),
                rowValues = mapOf("padding" to "8px")
            )
        )

        val expected = (8 * density).toInt()
        assertTrue(
            "no view padded to ${expected}px",
            flatten(view).any { it.paddingLeft == expected }
        )
    }

    @Test
    fun `button padding is scaled exactly once`() {
        val view = DesignRenderer.render(
            context,
            designWith(
                mapOf(
                    "type" to "button",
                    "values" to mapOf(
                        "text" to "Open",
                        "padding" to "12px",
                        "href" to mapOf("values" to mapOf("href" to "myapp://x"))
                    )
                )
            )
        )

        val expected = (12 * density).toInt()  // 36px — not 12, and not 108 from a double multiply
        val paddings = flatten(view).map { it.paddingLeft }.filter { it != 0 }
        assertTrue("expected a view padded to ${expected}px, got $paddings", paddings.contains(expected))
        assertTrue("padding was scaled twice: $paddings", paddings.none { it > expected })
    }

    @Test
    fun `a design without padding renders without crashing`() {
        val view = DesignRenderer.render(
            context,
            designWith(mapOf("type" to "text", "values" to mapOf("text" to "<p>hello</p>")))
        )
        assertTrue(flatten(view).all { it.paddingLeft == 0 })
    }
}
