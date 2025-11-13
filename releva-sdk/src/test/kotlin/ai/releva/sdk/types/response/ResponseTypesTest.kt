package ai.releva.sdk.types.response

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
class ResponseTypesTest {

    // RelevaResponse Tests

    @Test
    fun `fromJson parses valid JSON with recommenders and banners`() {
        val json = """
            {
                "recommenders": [
                    {
                        "token": "rec-1",
                        "name": "Popular Products",
                        "response": []
                    }
                ],
                "banners": [
                    {
                        "token": "ban-1",
                        "content": "Sale!"
                    }
                ]
            }
        """.trimIndent()

        val response = RelevaResponse.fromJson(json)

        assertEquals(1, response.recommenders.size)
        assertEquals(1, response.banners.size)
        assertEquals("rec-1", response.recommenders[0].token)
        assertEquals("ban-1", response.banners[0].token)
    }

    @Test
    fun `fromJson handles empty recommenders and banners`() {
        val json = """
            {
                "recommenders": [],
                "banners": []
            }
        """.trimIndent()

        val response = RelevaResponse.fromJson(json)

        assertTrue(response.recommenders.isEmpty())
        assertTrue(response.banners.isEmpty())
    }

    @Test
    fun `fromJson handles missing recommenders and banners`() {
        val json = "{}"

        val response = RelevaResponse.fromJson(json)

        assertTrue(response.recommenders.isEmpty())
        assertTrue(response.banners.isEmpty())
    }

    @Test
    fun `fromJson parses push info`() {
        val json = """
            {
                "recommenders": [],
                "banners": [],
                "push": {
                    "vapidPublicKey": "test-key-123"
                }
            }
        """.trimIndent()

        val response = RelevaResponse.fromJson(json)

        assertNotNull(response.push)
        assertEquals("test-key-123", response.push?.vapidPublicKey)
    }

    @Test
    fun `hasRecommenders returns true when recommenders exist`() {
        val response = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(
                    token = "rec-1",
                    name = "Test",
                    response = emptyList()
                )
            ),
            banners = emptyList()
        )

        assertTrue(response.hasRecommenders)
    }

    @Test
    fun `hasRecommenders returns false when no recommenders`() {
        val response = RelevaResponse(
            recommenders = emptyList(),
            banners = emptyList()
        )

        assertFalse(response.hasRecommenders)
    }

    @Test
    fun `hasBanners returns true when banners exist`() {
        val response = RelevaResponse(
            recommenders = emptyList(),
            banners = listOf(BannerResponse(token = "ban-1"))
        )

        assertTrue(response.hasBanners)
    }

    @Test
    fun `hasBanners returns false when no banners`() {
        val response = RelevaResponse(
            recommenders = emptyList(),
            banners = emptyList()
        )

        assertFalse(response.hasBanners)
    }

    @Test
    fun `getRecommendersByTag filters by tag`() {
        val response = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(
                    token = "rec-1",
                    name = "Test 1",
                    tags = listOf("homepage", "featured"),
                    response = emptyList()
                ),
                RecommenderResponse(
                    token = "rec-2",
                    name = "Test 2",
                    tags = listOf("category"),
                    response = emptyList()
                ),
                RecommenderResponse(
                    token = "rec-3",
                    name = "Test 3",
                    tags = listOf("homepage"),
                    response = emptyList()
                )
            ),
            banners = emptyList()
        )

        val homepageRecs = response.getRecommendersByTag("homepage")

        assertEquals(2, homepageRecs.size)
        assertTrue(homepageRecs.any { it.token == "rec-1" })
        assertTrue(homepageRecs.any { it.token == "rec-3" })
    }

    @Test
    fun `getRecommendersByTag returns empty list for non-existent tag`() {
        val response = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(
                    token = "rec-1",
                    name = "Test",
                    tags = listOf("homepage"),
                    response = emptyList()
                )
            ),
            banners = emptyList()
        )

        val result = response.getRecommendersByTag("non-existent")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getBannersByTag filters by tag`() {
        val response = RelevaResponse(
            recommenders = emptyList(),
            banners = listOf(
                BannerResponse(token = "ban-1", tags = listOf("top", "promo")),
                BannerResponse(token = "ban-2", tags = listOf("sidebar")),
                BannerResponse(token = "ban-3", tags = listOf("top"))
            )
        )

        val topBanners = response.getBannersByTag("top")

        assertEquals(2, topBanners.size)
        assertTrue(topBanners.any { it.token == "ban-1" })
        assertTrue(topBanners.any { it.token == "ban-3" })
    }

    @Test
    fun `getRecommenderByToken finds by token`() {
        val response = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(token = "rec-1", name = "First", response = emptyList()),
                RecommenderResponse(token = "rec-2", name = "Second", response = emptyList())
            ),
            banners = emptyList()
        )

        val recommender = response.getRecommenderByToken("rec-2")

        assertNotNull(recommender)
        assertEquals("Second", recommender?.name)
    }

    @Test
    fun `getRecommenderByToken returns null for non-existent token`() {
        val response = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(token = "rec-1", name = "First", response = emptyList())
            ),
            banners = emptyList()
        )

        val recommender = response.getRecommenderByToken("non-existent")

        assertNull(recommender)
    }

    @Test
    fun `getBannerByToken finds by token`() {
        val response = RelevaResponse(
            recommenders = emptyList(),
            banners = listOf(
                BannerResponse(token = "ban-1", content = "First"),
                BannerResponse(token = "ban-2", content = "Second")
            )
        )

        val banner = response.getBannerByToken("ban-2")

        assertNotNull(banner)
        assertEquals("Second", banner?.content)
    }

    @Test
    fun `getBannerByToken returns null for non-existent token`() {
        val response = RelevaResponse(
            recommenders = emptyList(),
            banners = listOf(
                BannerResponse(token = "ban-1")
            )
        )

        val banner = response.getBannerByToken("non-existent")

        assertNull(banner)
    }

    @Test
    fun `toMap converts response to map`() {
        val response = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(token = "rec-1", name = "Test", response = emptyList())
            ),
            banners = listOf(
                BannerResponse(token = "ban-1")
            ),
            push = PushInfo("key-123")
        )

        val map = response.toMap()

        assertTrue(map.containsKey("recommenders"))
        assertTrue(map.containsKey("banners"))
        assertTrue(map.containsKey("push"))
    }

    // RecommenderResponse Tests

    @Test
    fun `RecommenderResponse fromMap parses all fields`() {
        val map = mapOf(
            "token" to "rec-token",
            "name" to "Popular Items",
            "tags" to listOf("homepage", "featured"),
            "cssSelector" to ".recommender",
            "displayStrategy" to "carousel",
            "meta" to mapOf("key" to "value"),
            "template" to mapOf("id" to 1, "body" to "<div></div>"),
            "response" to listOf<Map<String, Any?>>()
        )

        val recommender = RecommenderResponse.fromMap(map)

        assertEquals("rec-token", recommender.token)
        assertEquals("Popular Items", recommender.name)
        assertEquals(2, recommender.tags?.size)
        assertEquals(".recommender", recommender.cssSelector)
        assertEquals("carousel", recommender.displayStrategy)
        assertNotNull(recommender.meta)
        assertNotNull(recommender.template)
    }

    @Test
    fun `RecommenderResponse fromMap handles missing optional fields`() {
        val map = mapOf(
            "token" to "rec-token",
            "name" to "Test",
            "response" to listOf<Map<String, Any?>>()
        )

        val recommender = RecommenderResponse.fromMap(map)

        assertEquals("rec-token", recommender.token)
        assertEquals("Test", recommender.name)
        assertNull(recommender.tags)
        assertNull(recommender.cssSelector)
        assertNull(recommender.displayStrategy)
        assertNull(recommender.meta)
        assertNull(recommender.template)
    }

    @Test
    fun `RecommenderResponse fromMap handles missing required fields`() {
        val map = mapOf(
            "response" to listOf<Map<String, Any?>>()
        )

        val recommender = RecommenderResponse.fromMap(map)

        assertEquals("", recommender.token)
        assertEquals("", recommender.name)
    }

    // ProductRecommendation Tests

    @Test
    fun `ProductRecommendation fromMap parses all fields`() {
        val map = mapOf(
            "id" to "prod-123",
            "name" to "Test Product",
            "price" to 99.99,
            "available" to true,
            "categories" to listOf("Electronics", "Phones"),
            "currency" to "USD",
            "description" to "A great product",
            "discount" to 10.0,
            "discountPercent" to 10.0,
            "discountPrice" to 89.99,
            "imageUrl" to "https://example.com/image.jpg",
            "listPrice" to 109.99,
            "locale" to "en_US",
            "url" to "https://example.com/product",
            "custom" to mapOf("brand" to "TestBrand"),
            "data" to mapOf("extra" to "info"),
            "createdAt" to "2025-01-01T10:00:00.000Z",
            "publishedAt" to "2025-01-02T10:00:00.000Z",
            "updatedAt" to "2025-01-03T10:00:00.000Z",
            "mergeContext" to mapOf("key1" to "value1")
        )

        val product = ProductRecommendation.fromMap(map)

        assertEquals("prod-123", product.id)
        assertEquals("Test Product", product.name)
        assertEquals(99.99, product.price, 0.001)
        assertTrue(product.available)
        assertEquals(2, product.categories?.size)
        assertEquals("USD", product.currency)
        assertEquals("A great product", product.description)
        assertEquals(10.0, product.discount!!, 0.001)
        assertEquals(89.99, product.discountPrice!!, 0.001)
        assertNotNull(product.createdAt)
        assertNotNull(product.publishedAt)
        assertNotNull(product.updatedAt)
    }

    @Test
    fun `ProductRecommendation fromMap handles number type conversions`() {
        val map = mapOf(
            "id" to "prod-123",
            "name" to "Test",
            "price" to 50,  // Integer instead of Double
            "available" to true,
            "discount" to 5,
            "discountPercent" to 10,
            "discountPrice" to 45,
            "listPrice" to 60
        )

        val product = ProductRecommendation.fromMap(map)

        assertEquals(50.0, product.price, 0.001)
        assertEquals(5.0, product.discount!!, 0.001)
        assertEquals(45.0, product.discountPrice!!, 0.001)
        assertEquals(60.0, product.listPrice!!, 0.001)
    }

    @Test
    fun `ProductRecommendation fromMap handles invalid date format`() {
        val map = mapOf(
            "id" to "prod-123",
            "name" to "Test",
            "price" to 99.99,
            "available" to true,
            "createdAt" to "invalid-date-format"
        )

        val product = ProductRecommendation.fromMap(map)

        assertNull(product.createdAt)
    }

    @Test
    fun `ProductRecommendation fromMap parses valid ISO 8601 date`() {
        val map = mapOf(
            "id" to "prod-123",
            "name" to "Test",
            "price" to 99.99,
            "available" to true,
            "createdAt" to "2025-10-16T15:30:45.123Z"
        )

        val product = ProductRecommendation.fromMap(map)

        assertNotNull(product.createdAt)

        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        val expectedDate = formatter.parse("2025-10-16T15:30:45.123Z")

        assertEquals(expectedDate?.time, product.createdAt?.time)
    }

    @Test
    fun `ProductRecommendation toMap converts dates to ISO 8601`() {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        val testDate = formatter.parse("2025-01-01T12:00:00.000Z")

        val product = ProductRecommendation(
            id = "prod-123",
            name = "Test",
            price = 99.99,
            available = true,
            createdAt = testDate
        )

        val map = product.toMap()

        assertEquals("2025-01-01T12:00:00.000Z", map["createdAt"])
    }

    @Test
    fun `ProductRecommendation fromMap handles missing required fields`() {
        val map = mapOf(
            "price" to 99.99
        )

        val product = ProductRecommendation.fromMap(map)

        assertEquals("", product.id)
        assertEquals("", product.name)
        assertEquals(99.99, product.price, 0.001)
        assertFalse(product.available)
    }

    @Test
    fun `ProductRecommendation fromMap defaults price to 0 when missing`() {
        val map = mapOf(
            "id" to "prod-123",
            "name" to "Test",
            "available" to true
        )

        val product = ProductRecommendation.fromMap(map)

        assertEquals(0.0, product.price, 0.001)
    }

    // Template Tests

    @Test
    fun `Template fromMap parses fields`() {
        val map = mapOf(
            "id" to 42,
            "body" to "<div>Template HTML</div>"
        )

        val template = Template.fromMap(map)

        assertEquals(42, template.id)
        assertEquals("<div>Template HTML</div>", template.body)
    }

    @Test
    fun `Template fromMap handles missing fields`() {
        val map = emptyMap<String, Any?>()

        val template = Template.fromMap(map)

        assertEquals(0, template.id)
        assertEquals("", template.body)
    }

    @Test
    fun `Template toMap converts to map`() {
        val template = Template(id = 10, body = "<p>Test</p>")

        val map = template.toMap()

        assertEquals(10, map["id"])
        assertEquals("<p>Test</p>", map["body"])
    }

    // BannerResponse Tests

    @Test
    fun `BannerResponse fromMap parses all fields`() {
        val map = mapOf(
            "token" to "banner-token",
            "content" to "Sale Now!",
            "imageUrl" to "https://example.com/banner.jpg",
            "targetUrl" to "https://example.com/sale",
            "tags" to listOf("homepage", "promo"),
            "meta" to mapOf("priority" to "high")
        )

        val banner = BannerResponse.fromMap(map)

        assertEquals("banner-token", banner.token)
        assertEquals("Sale Now!", banner.content)
        assertEquals("https://example.com/banner.jpg", banner.imageUrl)
        assertEquals("https://example.com/sale", banner.targetUrl)
        assertEquals(2, banner.tags?.size)
        assertNotNull(banner.meta)
    }

    @Test
    fun `BannerResponse fromMap handles missing optional fields`() {
        val map = mapOf(
            "token" to "banner-token"
        )

        val banner = BannerResponse.fromMap(map)

        assertEquals("banner-token", banner.token)
        assertNull(banner.content)
        assertNull(banner.imageUrl)
        assertNull(banner.targetUrl)
        assertNull(banner.tags)
        assertNull(banner.meta)
    }

    @Test
    fun `BannerResponse fromMap handles missing token`() {
        val map = emptyMap<String, Any?>()

        val banner = BannerResponse.fromMap(map)

        assertEquals("", banner.token)
    }

    @Test
    fun `BannerResponse toMap converts to map`() {
        val banner = BannerResponse(
            token = "ban-1",
            content = "Test",
            imageUrl = "https://example.com/img.jpg",
            targetUrl = "https://example.com/target",
            tags = listOf("tag1", "tag2"),
            meta = mapOf("key" to "value")
        )

        val map = banner.toMap()

        assertEquals("ban-1", map["token"])
        assertEquals("Test", map["content"])
        assertEquals("https://example.com/img.jpg", map["imageUrl"])
        assertEquals("https://example.com/target", map["targetUrl"])
        assertEquals(listOf("tag1", "tag2"), map["tags"])
        assertNotNull(map["meta"])
    }

    // PushInfo Tests

    @Test
    fun `PushInfo fromMap parses vapid key`() {
        val map = mapOf("vapidPublicKey" to "test-vapid-key")

        val pushInfo = PushInfo.fromMap(map)

        assertEquals("test-vapid-key", pushInfo.vapidPublicKey)
    }

    @Test
    fun `PushInfo fromMap handles missing vapid key`() {
        val map = emptyMap<String, Any?>()

        val pushInfo = PushInfo.fromMap(map)

        assertNull(pushInfo.vapidPublicKey)
    }

    @Test
    fun `PushInfo toMap converts to map`() {
        val pushInfo = PushInfo("my-vapid-key")

        val map = pushInfo.toMap()

        assertEquals("my-vapid-key", map["vapidPublicKey"])
    }

    // Integration Tests

    @Test
    fun `full response with nested data parses correctly`() {
        val json = """
            {
                "recommenders": [
                    {
                        "token": "rec-1",
                        "name": "Popular",
                        "tags": ["homepage"],
                        "template": {
                            "id": 1,
                            "body": "<div></div>"
                        },
                        "response": [
                            {
                                "id": "p1",
                                "name": "Product 1",
                                "price": 49.99,
                                "available": true
                            }
                        ]
                    }
                ],
                "banners": [
                    {
                        "token": "ban-1",
                        "content": "Sale!",
                        "tags": ["top"]
                    }
                ],
                "push": {
                    "vapidPublicKey": "key-123"
                }
            }
        """.trimIndent()

        val response = RelevaResponse.fromJson(json)

        assertEquals(1, response.recommenders.size)
        assertEquals(1, response.recommenders[0].response.size)
        assertEquals("Product 1", response.recommenders[0].response[0].name)
        assertEquals(1, response.banners.size)
        assertEquals("key-123", response.push?.vapidPublicKey)
    }

    @Test
    fun `response can be converted to map and back`() {
        val original = RelevaResponse(
            recommenders = listOf(
                RecommenderResponse(
                    token = "rec-1",
                    name = "Test",
                    tags = listOf("tag1"),
                    response = listOf(
                        ProductRecommendation(
                            id = "p1",
                            name = "Product",
                            price = 99.99,
                            available = true
                        )
                    )
                )
            ),
            banners = listOf(
                BannerResponse(token = "ban-1", content = "Banner")
            ),
            push = PushInfo("vapid-key")
        )

        val map = original.toMap()
        val restored = RelevaResponse.fromMap(map)

        assertEquals(original.recommenders.size, restored.recommenders.size)
        assertEquals(original.banners.size, restored.banners.size)
        assertEquals(original.push?.vapidPublicKey, restored.push?.vapidPublicKey)
    }
}
