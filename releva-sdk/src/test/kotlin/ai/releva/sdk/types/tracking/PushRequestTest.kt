package ai.releva.sdk.types.tracking

import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.cart.CartProduct
import ai.releva.sdk.types.customfield.CustomFields
import ai.releva.sdk.types.customfield.StringField
import ai.releva.sdk.types.event.CustomEvent
import ai.releva.sdk.types.filter.FilterAction
import ai.releva.sdk.types.filter.FilterOperator
import ai.releva.sdk.types.filter.SimpleFilter
import ai.releva.sdk.types.product.ViewedProduct
import org.junit.Assert.*
import org.junit.Test

class PushRequestTest {

    @Test
    fun `create empty push request`() {
        val request = PushRequest()

        assertNull(request.getScreenToken())
        assertNull(request.getCustomEvents())
        assertNull(request.pageUrl)
        assertNull(request.pageProductIds)
        assertNull(request.pageCategories)
        assertNull(request.pageFilter)
        assertNull(request.pageQuery)
        assertNull(request.pageLocale)
        assertNull(request.pageCurrency)
        assertNull(request.cart)
        assertNull(request.viewedProduct)
    }

    @Test
    fun `set screen token with fluent API`() {
        val request = PushRequest()
            .screenToken("home_screen")

        assertEquals("home_screen", request.getScreenToken())
    }

    @Test
    fun `set url with fluent API`() {
        val request = PushRequest()
            .url("https://example.com/products")

        assertEquals("https://example.com/products", request.pageUrl)
    }

    @Test
    fun `set page categories with fluent API`() {
        val categories = listOf("electronics", "smartphones")
        val request = PushRequest()
            .pageCategories(categories)

        assertEquals(categories, request.pageCategories)
    }

    @Test
    fun `set page product ids with fluent API`() {
        val productIds = listOf("prod-1", "prod-2", "prod-3")
        val request = PushRequest()
            .pageProductIds(productIds)

        assertEquals(productIds, request.pageProductIds)
    }

    @Test
    fun `set cart with fluent API`() {
        val cart = Cart.active(
            listOf(
                CartProduct(id = "prod-1", price = 99.99, quantity = 1.0)
            )
        )
        val request = PushRequest()
            .cart(cart)

        assertEquals(cart, request.cart)
        assertEquals(1, request.cart?.products?.size)
    }

    @Test
    fun `set page filter with fluent API`() {
        val filter = SimpleFilter.priceRange(minPrice = 50.0, maxPrice = 200.0)
        val request = PushRequest()
            .pageFilter(filter)

        assertEquals(filter, request.pageFilter)
    }

    @Test
    fun `set page query with fluent API`() {
        val request = PushRequest()
            .pageQuery("laptop computer")

        assertEquals("laptop computer", request.pageQuery)
    }

    @Test
    fun `set locale with fluent API`() {
        val request = PushRequest()
            .locale("en_US")

        assertEquals("en_US", request.pageLocale)
    }

    @Test
    fun `set currency with fluent API`() {
        val request = PushRequest()
            .currency("USD")

        assertEquals("USD", request.pageCurrency)
    }

    @Test
    fun `set custom events with fluent API`() {
        val events = listOf(
            CustomEvent(action = "user_logged_in", tags = listOf("auth")),
            CustomEvent(action = "product_favorited", tags = listOf("wishlist"))
        )
        val request = PushRequest()
            .customEvents(events)

        assertEquals(events, request.getCustomEvents())
        assertEquals(2, request.getCustomEvents()?.size)
    }

    @Test
    fun `set empty custom events list returns null`() {
        val request = PushRequest()
            .customEvents(emptyList())

        assertNull(request.getCustomEvents())
    }

    @Test
    fun `set viewed product with fluent API`() {
        val product = ViewedProduct(
            productId = "prod-123",
            custom = CustomFields(
                string = listOf(StringField("brand", listOf("Nike")))
            )
        )
        val request = PushRequest()
            .productView(product)

        assertEquals(product, request.viewedProduct)
        assertEquals("prod-123", request.viewedProduct?.productId)
    }

    @Test
    fun `chain multiple builder methods`() {
        val cart = Cart.active(listOf(CartProduct(id = "p1", price = 50.0, quantity = 2.0)))
        val events = listOf(CustomEvent(action = "page_view"))
        val filter = SimpleFilter.brand(brand = "Apple")

        val request = PushRequest()
            .screenToken("product_list")
            .url("https://store.com/products")
            .pageCategories(listOf("electronics"))
            .pageProductIds(listOf("p1", "p2"))
            .cart(cart)
            .pageFilter(filter)
            .pageQuery("macbook pro")
            .locale("en_US")
            .currency("USD")
            .customEvents(events)

        assertEquals("product_list", request.getScreenToken())
        assertEquals("https://store.com/products", request.pageUrl)
        assertEquals(1, request.pageCategories?.size)
        assertEquals(2, request.pageProductIds?.size)
        assertNotNull(request.cart)
        assertNotNull(request.pageFilter)
        assertEquals("macbook pro", request.pageQuery)
        assertEquals("en_US", request.pageLocale)
        assertEquals("USD", request.pageCurrency)
        assertEquals(1, request.getCustomEvents()?.size)
    }

    @Test
    fun `builder methods return request instance for chaining`() {
        val request = PushRequest()

        val result1 = request.screenToken("test")
        val result2 = request.url("http://test.com")
        val result3 = request.pageCategories(listOf("cat"))
        val result4 = request.pageProductIds(listOf("p1"))
        val result5 = request.pageFilter(SimpleFilter.brand("Test"))
        val result6 = request.pageQuery("query")
        val result7 = request.locale("en")
        val result8 = request.currency("EUR")
        val result9 = request.customEvents(listOf(CustomEvent("event")))
        val result10 = request.cart(Cart.active(emptyList()))

        assertSame(request, result1)
        assertSame(request, result2)
        assertSame(request, result3)
        assertSame(request, result4)
        assertSame(request, result5)
        assertSame(request, result6)
        assertSame(request, result7)
        assertSame(request, result8)
        assertSame(request, result9)
        assertSame(request, result10)
    }

    @Test
    fun `overwrite values using fluent API`() {
        val request = PushRequest()
            .screenToken("first_token")
            .screenToken("second_token")
            .url("first_url")
            .url("second_url")

        assertEquals("second_token", request.getScreenToken())
        assertEquals("second_url", request.pageUrl)
    }

    @Test
    fun `set all tracking metadata`() {
        val request = PushRequest()
            .pageQuery("search term")
            .locale("bg_BG")
            .currency("BGN")
            .pageCategories(listOf("category1", "category2"))
            .pageProductIds(listOf("id1", "id2", "id3"))

        assertEquals("search term", request.pageQuery)
        assertEquals("bg_BG", request.pageLocale)
        assertEquals("BGN", request.pageCurrency)
        assertEquals(2, request.pageCategories?.size)
        assertEquals(3, request.pageProductIds?.size)
    }

    @Test
    fun `combine cart and viewed product`() {
        val cart = Cart.paid(
            products = listOf(CartProduct(id = "p1", price = 100.0, quantity = 1.0)),
            orderId = "order-123"
        )
        val viewedProduct = ViewedProduct(
            productId = "p2",
            custom = CustomFields.empty()
        )

        val request = PushRequest()
            .cart(cart)
            .productView(viewedProduct)

        assertNotNull(request.cart)
        assertTrue(request.cart!!.cartPaid)
        assertEquals("order-123", request.cart!!.orderId)
        assertNotNull(request.viewedProduct)
        assertEquals("p2", request.viewedProduct!!.productId)
    }
}
