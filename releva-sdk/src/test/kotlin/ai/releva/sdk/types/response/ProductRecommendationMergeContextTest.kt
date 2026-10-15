package ai.releva.sdk.types.response

import ai.releva.sdk.types.tracking.PushRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The API sends a product's mergeContext with rpos and pfid as JSON numbers. They used to be
 * parsed as "", so a click sent back from the app lost its position and feed id.
 */
class ProductRecommendationMergeContextTest {

    private fun product(mergeContext: Map<String, Any?>) = ProductRecommendation.fromMap(
        mapOf("id" to "sku-1", "name" to "Sandal", "price" to 10.0, "available" to true, "mergeContext" to mergeContext)
    )

    @Test
    fun `numeric values survive parsing as their text`() {
        val p = product(mapOf("rid" to "0233781a-f836-40a9-a67a-2220814b5db4", "rpid" to "sku-1", "rpos" to 1, "pfid" to 30202))
        assertEquals(
            mapOf("rid" to "0233781a-f836-40a9-a67a-2220814b5db4", "rpid" to "sku-1", "rpos" to "1", "pfid" to "30202"),
            p.mergeContext,
        )
    }

    @Test
    fun `whole doubles print without a fraction`() {
        assertEquals("30202", ProductRecommendation.mergeContextValue(30202.0))
        assertEquals("", ProductRecommendation.mergeContextValue(null))
    }

    @Test
    fun `a parsed mergeContext round-trips into the click context`() {
        val p = product(mapOf("rid" to "0233781a-f836-40a9-a67a-2220814b5db4", "rpid" to "sku-1", "rpos" to 3, "pfid" to 30202.0))
        val ctx = PushRequest.recommenderClickContext(p.mergeContext!!)!!
        assertEquals(3, ctx["rpos"])
        assertEquals(30202L, ctx["pfid"])
    }
}
