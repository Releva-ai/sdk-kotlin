package ai.releva.sdk.types.response

/**
 * NPS survey configuration returned by the server
 */
data class NpsConfig(
    val token: String,
    val question: String,
    val scaleLowLabel: String? = null,
    val scaleHighLabel: String? = null,
    val followUp: NpsFollowUp? = null,
    val followUpRequired: Boolean = false,
    val submitLabel: String = "Submit",
    val skipLabel: String? = null,
    val thankYou: NpsThankYou? = null,
    val appearance: NpsAppearance = NpsAppearance.defaults(),
    val triggers: List<NpsTrigger> = emptyList(),
    val triggerDelaySeconds: Int = 0,
    val cancelOnEvents: List<String> = emptyList()
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): NpsConfig {
            @Suppress("UNCHECKED_CAST")
            return NpsConfig(
                token = map["token"] as? String ?: "",
                question = map["question"] as? String ?: "",
                scaleLowLabel = map["scaleLowLabel"] as? String,
                scaleHighLabel = map["scaleHighLabel"] as? String,
                followUp = (map["followUp"] as? Map<String, Any?>)?.let { NpsFollowUp.fromMap(it) },
                followUpRequired = map["followUpRequired"] as? Boolean ?: false,
                submitLabel = map["submitLabel"] as? String ?: "Submit",
                skipLabel = map["skipLabel"] as? String,
                thankYou = (map["thankYou"] as? Map<String, Any?>)?.let { NpsThankYou.fromMap(it) },
                appearance = (map["appearance"] as? Map<String, Any?>)?.let { NpsAppearance.fromMap(it) }
                    ?: NpsAppearance.defaults(),
                triggers = (map["triggers"] as? List<Map<String, Any?>>)
                    ?.map { NpsTrigger.fromMap(it) } ?: emptyList(),
                triggerDelaySeconds = (map["triggerDelaySeconds"] as? Number)?.toInt() ?: 0,
                cancelOnEvents = (map["cancelOnEvents"] as? List<String>) ?: emptyList()
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "token" to token,
        "question" to question,
        "scaleLowLabel" to scaleLowLabel,
        "scaleHighLabel" to scaleHighLabel,
        "followUp" to followUp?.toMap(),
        "followUpRequired" to followUpRequired,
        "submitLabel" to submitLabel,
        "skipLabel" to skipLabel,
        "thankYou" to thankYou?.toMap(),
        "appearance" to appearance.toMap(),
        "triggers" to triggers.map { it.toMap() },
        "triggerDelaySeconds" to triggerDelaySeconds,
        "cancelOnEvents" to cancelOnEvents
    )
}

data class NpsFollowUp(
    val promoter: String? = null,
    val passive: String? = null,
    val detractor: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): NpsFollowUp {
            return NpsFollowUp(
                promoter = map["promoter"] as? String,
                passive = map["passive"] as? String,
                detractor = map["detractor"] as? String
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "promoter" to promoter,
        "passive" to passive,
        "detractor" to detractor
    )

    fun forScore(score: Int): String? = when {
        score >= 9 -> promoter
        score >= 7 -> passive
        else -> detractor
    }
}

data class NpsThankYou(
    val promoter: String? = null,
    val passive: String? = null,
    val detractor: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): NpsThankYou {
            return NpsThankYou(
                promoter = map["promoter"] as? String,
                passive = map["passive"] as? String,
                detractor = map["detractor"] as? String
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "promoter" to promoter,
        "passive" to passive,
        "detractor" to detractor
    )

    fun forScore(score: Int): String = when {
        score >= 9 -> promoter ?: "Thank you for your feedback!"
        score >= 7 -> passive ?: "Thank you for your feedback."
        else -> detractor ?: "Thank you. We'll work on it."
    }
}

data class NpsAppearance(
    val primaryColor: String = "#6C3FC4",
    val backgroundColor: String = "#FFFFFF",
    val textColor: String = "#1A1A1A",
    val buttonStyle: String = "pill",  // pill, rounded, square
    val position: String = "bottomSheet",  // bottomSheet, modal
    val logoUrl: String? = null,
    val dark: NpsAppearanceDark? = null
) {
    companion object {
        fun defaults() = NpsAppearance()

        fun fromMap(map: Map<String, Any?>): NpsAppearance {
            @Suppress("UNCHECKED_CAST")
            return NpsAppearance(
                primaryColor = map["primaryColor"] as? String ?: "#6C3FC4",
                backgroundColor = map["backgroundColor"] as? String ?: "#FFFFFF",
                textColor = map["textColor"] as? String ?: "#1A1A1A",
                buttonStyle = map["buttonStyle"] as? String ?: "pill",
                position = map["position"] as? String ?: "bottomSheet",
                logoUrl = map["logoUrl"] as? String,
                dark = (map["dark"] as? Map<String, Any?>)?.let { NpsAppearanceDark.fromMap(it) }
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "primaryColor" to primaryColor,
        "backgroundColor" to backgroundColor,
        "textColor" to textColor,
        "buttonStyle" to buttonStyle,
        "position" to position,
        "logoUrl" to logoUrl,
        "dark" to dark?.toMap()
    )
}

data class NpsAppearanceDark(
    val primaryColor: String? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): NpsAppearanceDark {
            return NpsAppearanceDark(
                primaryColor = map["primaryColor"] as? String,
                backgroundColor = map["backgroundColor"] as? String,
                textColor = map["textColor"] as? String
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "primaryColor" to primaryColor,
        "backgroundColor" to backgroundColor,
        "textColor" to textColor
    )
}

data class NpsTrigger(
    val type: String,  // appOpen, customEvent, sessionCount, screenView
    val eventName: String? = null,  // for customEvent
    val minSessions: Int? = null  // for sessionCount
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): NpsTrigger {
            return NpsTrigger(
                type = map["type"] as? String ?: "",
                eventName = map["eventName"] as? String,
                minSessions = (map["minSessions"] as? Number)?.toInt()
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "type" to type,
        "eventName" to eventName,
        "minSessions" to minSessions
    )
}
