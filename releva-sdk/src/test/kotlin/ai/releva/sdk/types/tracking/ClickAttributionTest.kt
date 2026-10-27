package ai.releva.sdk.types.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ClickAttributionTest {

    @Test
    fun `every field in the table is read from a url with the type the API validates`() {
        val url = "https://shop.example.com/p/sku-1" +
            "?rlv_rid=0233781a-f836-40a9-a67a-2220814b5db4&rlv_rpid=sku-1&rlv_rpos=3" +
            "&rlv_aid=search-1&rlv_apid=sku-1&rlv_apos=0" +
            "&rlv_bid=1f0a0b2c-3d4e-4f50-8617-2a3b4c5d6e7f&rlv_odid=order-1" +
            "&rlv_cid=42&rlv_mid=7&rlv_sid=9&rlv_stid=story-1&rlv_pfid=30202" +
            "&rlv_ctid=campaign-1&rlv_fsid=flow-1&rlv_said=assistant-1&rlv_sapid=sku-2" +
            "&fbclid=fb-1"

        assertEquals(
            mapOf(
                "rid" to "0233781a-f836-40a9-a67a-2220814b5db4",
                "rpid" to "sku-1",
                "rpos" to 3,
                "aid" to "search-1",
                "apid" to "sku-1",
                "apos" to 0,
                "bid" to "1f0a0b2c-3d4e-4f50-8617-2a3b4c5d6e7f",
                "odid" to "order-1",
                "cid" to 42L,
                "mid" to 7L,
                "sid" to 9L,
                "stid" to "story-1",
                "pfid" to 30202L,
                "ctid" to "campaign-1",
                "fsid" to "flow-1",
                "said" to "assistant-1",
                "sapid" to "sku-2",
                "fbclid" to "fb-1",
            ),
            ClickAttribution.fromUrl(url)!!.fields,
        )
    }

    @Test
    fun `a value that fails its type is dropped rather than sent`() {
        val attribution = ClickAttribution.fromUrl(
            "https://shop.example.com/p?rlv_rid=block-1&rlv_bid=banner-1&rlv_rpos=0" +
                "&rlv_apos=-1&rlv_cid=x&rlv_rpid=%20&rlv_aid=search-1"
        )!!

        assertEquals(mapOf<String, Any>("aid" to "search-1"), attribution.fields)
        assertFalse(attribution.isEmpty)
    }

    @Test
    fun `a uuid the API's rule rejects is dropped`() {
        val base = "https://shop.example.com/p?rlv_aid=1&rlv_rid="
        listOf(
            "0233781a-f836-40a9-c67a-2220814b5db4",
            "1f0a0b2c-3d4e-0f50-8617-2a3b4c5d6e7f",
            "1f0a0b2c-3d4e-9f50-8617-2a3b4c5d6e7f",
        ).forEach { assertNull(it, ClickAttribution.fromUrl(base + it)!!.fields["rid"]) }
        listOf(
            "0233781A-F836-40A9-A67A-2220814B5DB4",
            "00000000-0000-0000-0000-000000000000",
            "1f0a0b2c-3d4e-1f50-c617-2a3b4c5d6e7f",
        ).forEach { assertEquals(it, ClickAttribution.fromUrl(base + it)!!.fields["rid"]) }
    }

    @Test
    fun `an fbclid past the length the schema allows is dropped`() {
        val url = "https://shop.example.com/p?rlv_aid=1&fbclid="
        assertEquals("x".repeat(256), ClickAttribution.fromUrl(url + "x".repeat(256))!!.fields["fbclid"])
        assertNull(ClickAttribution.fromUrl(url + "x".repeat(257))!!.fields["fbclid"])
    }

    @Test
    fun `encoded values are decoded, a plus is a space and an escaped plus stays a plus`() {
        val fields = ClickAttribution.fromUrl(
            "https://shop.example.com/p?rlv_rpid=sku%2F1&rlv_apid=sku%231" +
                "&rlv_sapid=sku%2B1&rlv_odid=order+1"
        )!!.fields

        assertEquals("sku/1", fields["rpid"])
        assertEquals("sku#1", fields["apid"])
        assertEquals("sku+1", fields["sapid"])
        assertEquals("order 1", fields["odid"])
    }

    @Test
    fun `a search url carries the search click context`() {
        assertEquals(
            mapOf("aid" to "1", "apid" to "sku-1", "apos" to 0, "pfid" to 30202L),
            ClickAttribution.fromUrl(
                "https://shop.example.com/p/sku-1?rlv_aid=1&rlv_apid=sku-1&rlv_apos=0&rlv_pfid=30202"
            )!!.fields,
        )
    }

    @Test
    fun `an assistant url with its escapes already decoded still credits the click`() {
        assertEquals(
            mapOf<String, Any>("said" to "1", "sapid" to "sku-1"),
            ClickAttribution.fromUrl(
                "https://shop.example.com/products/red dress?discount=10%&releva_chat=product" +
                    "&rlv_said=1&rlv_sapid=sku-1#top"
            )!!.fields,
        )
    }

    @Test
    fun `a decoded hash in a merchant parameter does not hide the attribution`() {
        assertEquals(
            mapOf<String, Any>("said" to "1", "sapid" to "sku-1"),
            ClickAttribution.fromUrl(
                "https://shop.example.com/p/sku-1?color=#ff0000&releva_chat=product" +
                    "&rlv_said=1&rlv_sapid=sku-1"
            )!!.fields,
        )
    }

    @Test
    fun `fromFields reads a product block's mergeContext and ignores unknown keys`() {
        assertEquals(
            mapOf(
                "rid" to "0233781a-f836-40a9-a67a-2220814b5db4",
                "rpid" to "sku-1",
                "rpos" to 3,
                "pfid" to 30202L,
            ),
            ClickAttribution.fromFields(
                mapOf(
                    "rid" to "0233781a-f836-40a9-a67a-2220814b5db4",
                    "rpid" to "sku-1",
                    "rpos" to "3",
                    "pfid" to "30202",
                    "rlv_aid" to "not a context field name",
                )
            )!!.fields,
        )
    }

    @Test
    fun `plus lets the other side's fields win`() {
        val fromUrl = ClickAttribution.fromUrl("https://shop.example.com/p?rlv_aid=1&rlv_apid=sku-1")!!
        val fromFields = ClickAttribution.fromFields(mapOf("apid" to "sku-2", "rpid" to "sku-3"))!!

        assertEquals(
            mapOf("aid" to "1", "apid" to "sku-2", "rpid" to "sku-3"),
            (fromUrl + fromFields).fields,
        )
        assertEquals(fromUrl.fields, (fromUrl + null).fields)
    }

    @Test
    fun `nothing to credit is null rather than an empty attribution`() {
        assertNull(ClickAttribution.fromUrl(null))
        assertNull(ClickAttribution.fromFields(null))
        assertNull(ClickAttribution.fromFields(emptyMap()))
        assertNull(ClickAttribution.fromUrl("https://shop.example.com/p/sku-1?utm_source=newsletter"))
        assertNull(ClickAttribution.fromUrl("not a url"))
    }
}
