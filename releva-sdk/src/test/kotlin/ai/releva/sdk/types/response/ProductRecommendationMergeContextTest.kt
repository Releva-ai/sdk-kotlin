package ai.releva.sdk.types.response

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
    fun `a parsed mergeContext round-trips into the click attribution`() {
        val p = product(mapOf("rid" to "0233781a-f836-40a9-a67a-2220814b5db4", "rpid" to "sku-1", "rpos" to 3, "pfid" to 30202.0))
        val fields = p.clickAttribution!!.fields
        assertEquals(3, fields["rpos"])
        assertEquals(30202L, fields["pfid"])
    }

    @Test
    fun `the product url fills in what the mergeContext does not carry`() {
        val p = ProductRecommendation.fromMap(
            mapOf(
                "id" to "sku-1", "name" to "Sandal", "price" to 10.0, "available" to true,
                "mergeContext" to mapOf("rid" to "0233781a-f836-40a9-a67a-2220814b5db4"),
                "url" to "https://shop.example.com/p/sku-1" +
                    "?rlv_rid=1f0a0b2c-3d4e-4f50-8617-2a3b4c5d6e7f&rlv_aid=1&rlv_apid=sku-1",
            )
        )
        assertEquals(
            mapOf(
                "rid" to "0233781a-f836-40a9-a67a-2220814b5db4",
                "aid" to "1",
                "apid" to "sku-1",
            ),
            p.clickAttribution!!.fields,
        )
    }

    @Test
    fun `a product with no mergeContext is credited from its url alone`() {
        val p = ProductRecommendation.fromMap(
            mapOf(
                "id" to "sku-1", "name" to "Sandal", "price" to 10.0, "available" to true,
                "url" to "https://shop.example.com/p/sku-1?rlv_aid=1&rlv_apid=sku-1",
            )
        )
        assertEquals(mapOf("aid" to "1", "apid" to "sku-1"), p.clickAttribution!!.fields)
    }
}
