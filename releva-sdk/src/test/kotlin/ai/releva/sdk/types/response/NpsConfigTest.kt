package ai.releva.sdk.types.response

import org.junit.Assert.*
import org.junit.Test

class NpsConfigTest {

    @Test
    fun `fromMap parses full NPS config`() {
        val map = mapOf<String, Any?>(
            "token" to "nps-123",
            "question" to "How likely are you to recommend us?",
            "scaleLowLabel" to "Not likely",
            "scaleHighLabel" to "Very likely",
            "followUp" to mapOf("promoter" to "What do you love?", "passive" to "How can we improve?", "detractor" to "What went wrong?"),
            "followUpRequired" to true,
            "submitLabel" to "Send",
            "skipLabel" to "Maybe later",
            "thankYou" to mapOf("promoter" to "Awesome!", "passive" to "Thanks!", "detractor" to "Sorry to hear that."),
            "appearance" to mapOf("primaryColor" to "#FF0000", "backgroundColor" to "#000000", "textColor" to "#FFFFFF", "buttonStyle" to "rounded", "position" to "modal"),
            "triggers" to listOf(mapOf<String, Any?>("type" to "customEvent", "eventName" to "purchase")),
            "triggerDelaySeconds" to 5,
            "cancelOnEvents" to listOf("checkout_started")
        )

        val config = NpsConfig.fromMap(map)

        assertEquals("nps-123", config.token)
        assertEquals("How likely are you to recommend us?", config.question)
        assertEquals("Not likely", config.scaleLowLabel)
        assertEquals("Very likely", config.scaleHighLabel)
        assertTrue(config.followUpRequired)
        assertEquals("Send", config.submitLabel)
        assertEquals("Maybe later", config.skipLabel)
        assertEquals("#FF0000", config.appearance.primaryColor)
        assertEquals("rounded", config.appearance.buttonStyle)
        assertEquals("modal", config.appearance.position)
        assertEquals(1, config.triggers.size)
        assertEquals("customEvent", config.triggers[0].type)
        assertEquals("purchase", config.triggers[0].eventName)
        assertEquals(5, config.triggerDelaySeconds)
        assertEquals(listOf("checkout_started"), config.cancelOnEvents)
    }

    @Test
    fun `fromMap with minimal data uses defaults`() {
        val map = mapOf<String, Any?>("token" to "t1", "question" to "Rate us")
        val config = NpsConfig.fromMap(map)

        assertEquals("t1", config.token)
        assertEquals("Rate us", config.question)
        assertNull(config.scaleLowLabel)
        assertNull(config.followUp)
        assertFalse(config.followUpRequired)
        assertEquals("Submit", config.submitLabel)
        assertEquals("#6C3FC4", config.appearance.primaryColor)
        assertEquals("pill", config.appearance.buttonStyle)
        assertEquals("bottomSheet", config.appearance.position)
        assertEquals(0, config.triggerDelaySeconds)
        assertTrue(config.cancelOnEvents.isEmpty())
    }

    @Test
    fun `fromMap with empty map returns safe defaults`() {
        val config = NpsConfig.fromMap(emptyMap())
        assertEquals("", config.token)
        assertEquals("", config.question)
    }

    @Test
    fun `toMap roundtrips correctly`() {
        val original = NpsConfig(
            token = "t1",
            question = "Q?",
            scaleLowLabel = "Low",
            scaleHighLabel = "High",
            followUp = NpsFollowUp(promoter = "P", passive = "Pa", detractor = "D"),
            triggerDelaySeconds = 3,
            cancelOnEvents = listOf("evt1")
        )

        val restored = NpsConfig.fromMap(original.toMap())
        assertEquals(original.token, restored.token)
        assertEquals(original.question, restored.question)
        assertEquals(original.scaleLowLabel, restored.scaleLowLabel)
        assertEquals(original.triggerDelaySeconds, restored.triggerDelaySeconds)
        assertEquals(original.cancelOnEvents, restored.cancelOnEvents)
    }

    @Test
    fun `NpsFollowUp forScore returns correct category`() {
        val followUp = NpsFollowUp(promoter = "P", passive = "Pa", detractor = "D")
        assertEquals("P", followUp.forScore(10))
        assertEquals("P", followUp.forScore(9))
        assertEquals("Pa", followUp.forScore(8))
        assertEquals("Pa", followUp.forScore(7))
        assertEquals("D", followUp.forScore(6))
        assertEquals("D", followUp.forScore(0))
    }

    @Test
    fun `NpsThankYou forScore returns correct message with defaults`() {
        val thankYou = NpsThankYou()
        assertEquals("Thank you for your feedback!", thankYou.forScore(10))
        assertEquals("Thank you for your feedback.", thankYou.forScore(7))
        assertEquals("Thank you. We'll work on it.", thankYou.forScore(0))
    }

    @Test
    fun `NpsAppearance dark mode parsing`() {
        val map = mapOf<String, Any?>(
            "primaryColor" to "#FF0000",
            "backgroundColor" to "#FFFFFF",
            "textColor" to "#000000",
            "buttonStyle" to "square",
            "position" to "bottomSheet",
            "dark" to mapOf("primaryColor" to "#00FF00", "backgroundColor" to "#111111")
        )
        val appearance = NpsAppearance.fromMap(map)
        assertNotNull(appearance.dark)
        assertEquals("#00FF00", appearance.dark!!.primaryColor)
        assertEquals("#111111", appearance.dark!!.backgroundColor)
        assertNull(appearance.dark!!.textColor)
    }

    @Test
    fun `NpsTrigger fromMap`() {
        val trigger = NpsTrigger.fromMap(mapOf("type" to "sessionCount", "minSessions" to 5))
        assertEquals("sessionCount", trigger.type)
        assertEquals(5, trigger.minSessions)
        assertNull(trigger.eventName)
    }
}
