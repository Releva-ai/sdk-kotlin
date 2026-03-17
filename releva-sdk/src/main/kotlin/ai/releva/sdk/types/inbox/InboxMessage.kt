package ai.releva.sdk.types.inbox

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Represents an inbox message
 */
data class InboxMessage(
    val id: String,
    val title: String,
    val design: Map<String, Any?>,
    var read: Boolean,
    val createdAt: Date,
    val inboxMessageId: Int
) {
    companion object {
        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val isoFormatNoMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private fun parseDate(dateStr: String): Date {
            return try {
                isoFormat.parse(dateStr) ?: Date()
            } catch (e: Exception) {
                try {
                    isoFormatNoMs.parse(dateStr) ?: Date()
                } catch (e2: Exception) {
                    Date()
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): InboxMessage {
            return InboxMessage(
                id = map["id"] as? String ?: "",
                title = map["title"] as? String ?: "",
                design = map["design"] as? Map<String, Any?> ?: emptyMap(),
                read = map["read"] as? Boolean ?: false,
                createdAt = parseDate(map["createdAt"] as? String ?: ""),
                inboxMessageId = (map["inboxMessageId"] as? Number)?.toInt() ?: 0
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "design" to design,
        "read" to read,
        "createdAt" to isoFormat.format(createdAt),
        "inboxMessageId" to inboxMessageId
    )
}
