package ai.releva.sdk.types.response

import org.junit.Assert.*
import org.junit.Test

class StoryResponseTest {

    @Test
    fun `fromMap parses full story response`() {
        val map = mapOf<String, Any?>(
            "token" to "story-1",
            "storyId" to 42,
            "name" to "Welcome Story",
            "trigger" to "immediately",
            "delaySeconds" to 3,
            "scrollPercentage" to 50,
            "endBehavior" to "loop",
            "progressIndicatorColor" to "#FF0000",
            "progressIndicatorInactiveColor" to "#00FF0080",
            "tags" to listOf("welcome", "onboarding"),
            "slides" to listOf(
                mapOf<String, Any?>(
                    "id" to 1,
                    "html" to "<p>Hello</p>",
                    "design" to mapOf("body" to mapOf("values" to emptyMap<String, Any?>())),
                    "durationSeconds" to 10,
                    "actionType" to "dismiss",
                    "actionUrl" to "https://example.com",
                    "actionLabel" to "Learn More"
                )
            ),
            "mergeContext" to mapOf("key" to "value")
        )

        val story = StoryResponse.fromMap(map)

        assertEquals("story-1", story.token)
        assertEquals(42, story.storyId)
        assertEquals("Welcome Story", story.name)
        assertEquals("immediately", story.trigger)
        assertEquals(3, story.delaySeconds)
        assertEquals(50, story.scrollPercentage)
        assertEquals("loop", story.endBehavior)
        assertEquals("#FF0000", story.progressIndicatorColor)
        assertEquals(listOf("welcome", "onboarding"), story.tags)
        assertEquals(1, story.slides.size)
        assertEquals(10, story.slides[0].durationSeconds)
        assertEquals("dismiss", story.slides[0].actionType)
        assertEquals("Learn More", story.slides[0].actionLabel)
    }

    @Test
    fun `fromMap with minimal data uses defaults`() {
        val story = StoryResponse.fromMap(mapOf("token" to "s1"))
        assertEquals("s1", story.token)
        assertEquals("dismiss", story.endBehavior)
        assertEquals("#FFFFFF", story.progressIndicatorColor)
        assertEquals("#FFFFFF4D", story.progressIndicatorInactiveColor)
        assertTrue(story.slides.isEmpty())
        assertNull(story.tags)
    }

    @Test
    fun `StorySlideResponse defaults durationSeconds to 5`() {
        val slide = StorySlideResponse.fromMap(emptyMap())
        assertEquals(5, slide.durationSeconds)
        assertNull(slide.html)
        assertNull(slide.design)
        assertNull(slide.actionType)
    }

    @Test
    fun `toMap roundtrips correctly`() {
        val original = StoryResponse(
            token = "s1",
            storyId = 99,
            trigger = "delaySeconds",
            delaySeconds = 5,
            endBehavior = "stayOnLast",
            slides = listOf(
                StorySlideResponse(id = 1, durationSeconds = 8, actionLabel = "Click")
            )
        )

        val restored = StoryResponse.fromMap(original.toMap())
        assertEquals(original.token, restored.token)
        assertEquals(original.trigger, restored.trigger)
        assertEquals(original.delaySeconds, restored.delaySeconds)
        assertEquals(original.endBehavior, restored.endBehavior)
        assertEquals(1, restored.slides.size)
        assertEquals(8, restored.slides[0].durationSeconds)
    }
}
