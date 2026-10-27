package ai.releva.sdk.types.tracking

import java.net.URLDecoder

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * The attribution a Releva click carries, so the click — and any purchase after it — is
 * credited to the placement the shopper came from: a product block, a search result, a banner,
 * an email or campaign link, a story, the shopping assistant.
 *
 * Build one with [fromUrl] from a Releva URL the shopper tapped, or with [fromFields] when the
 * placement handed the values over directly, then pass it to the tracking call for the screen
 * that tap opened.
 *
 * Only the fields listed in this class's table are read; anything else on the URL or in the map
 * is ignored, because the push context schema is strict and an unknown key would make the API
 * reject the whole request. A new attribution source therefore arrives as a row in that table —
 * a minor release — never as a change to the signatures here.
 */
class ClickAttribution private constructor(internal val fields: Map<String, Any>) {

    /** True when nothing is credited; the factories return null rather than an empty one. */
    val isEmpty: Boolean
        get() = fields.isEmpty()

    /** Fields present in [other] override this one's. */
    operator fun plus(other: ClickAttribution?): ClickAttribution =
        if (other == null) this else ClickAttribution(fields + other.fields)

    internal class FieldSpec(
        val contextName: String,
        val urlParam: String,
        val type: FieldType,
        /** The schema's length bound, for the fields that carry one. */
        val maxLength: Int? = null,
    )

    /**
     * How a field's text is read. The API validates every one of these and rejects the whole
     * push on a mismatch, so a value that fails its type is dropped rather than sent.
     */
    internal enum class FieldType {
        UUID,
        STRING,

        /** A 1-based position. */
        POSITION,

        /** A 0-based position. */
        OFFSET,
        NUMBER;

        fun parse(value: String): Any? = when (this) {
            UUID -> value.takeIf { UUID_PATTERN.matches(it) }
            STRING -> value.takeIf { it.isNotBlank() }
            POSITION -> value.toIntOrNull()?.takeIf { it >= 1 }
            OFFSET -> value.toIntOrNull()?.takeIf { it >= 0 }
            NUMBER -> value.toLongOrNull() ?: value.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }

    companion object {
        /**
         * Every attribution field Releva defines, each with the URL parameter it rides on and
         * the type the push context schema validates it as. Mirrors the web SDK's table.
         */
        internal val SPECS = listOf(
            FieldSpec("rid", "rlv_rid", FieldType.UUID),
            FieldSpec("rpid", "rlv_rpid", FieldType.STRING),
            FieldSpec("rpos", "rlv_rpos", FieldType.POSITION),
            FieldSpec("aid", "rlv_aid", FieldType.STRING),
            FieldSpec("apid", "rlv_apid", FieldType.STRING),
            FieldSpec("apos", "rlv_apos", FieldType.OFFSET),
            FieldSpec("bid", "rlv_bid", FieldType.UUID),
            FieldSpec("odid", "rlv_odid", FieldType.STRING),
            FieldSpec("cid", "rlv_cid", FieldType.NUMBER),
            FieldSpec("mid", "rlv_mid", FieldType.NUMBER),
            FieldSpec("sid", "rlv_sid", FieldType.NUMBER),
            FieldSpec("stid", "rlv_stid", FieldType.STRING),
            FieldSpec("pfid", "rlv_pfid", FieldType.NUMBER),
            FieldSpec("ctid", "rlv_ctid", FieldType.STRING),
            FieldSpec("fsid", "rlv_fsid", FieldType.STRING),
            FieldSpec("said", "rlv_said", FieldType.STRING),
            FieldSpec("sapid", "rlv_sapid", FieldType.STRING),
            FieldSpec("fbclid", "fbclid", FieldType.STRING, maxLength = 256),
        )

        /**
         * The attribution on a Releva URL the shopper tapped — a product block link, a search
         * result, a banner link, an email or campaign link, a story, a shopping-assistant link.
         * Pass the URL exactly as Releva returned it. Null when it carries no valid field.
         */
        fun fromUrl(url: String?): ClickAttribution? {
            val parameters = url?.let { queryParameters(it) } ?: return null
            return from { parameters[it.urlParam] }
        }

        /**
         * The attribution in a map keyed by context field name (`rid`, `aid`, `said`…), such as
         * a [ai.releva.sdk.types.response.ProductRecommendation.mergeContext]. Unknown keys are
         * ignored. Null when no valid field remains.
         */
        fun fromFields(fields: Map<String, String>?): ClickAttribution? {
            if (fields == null) return null
            return from { fields[it.contextName] }
        }

        private fun from(read: (FieldSpec) -> String?): ClickAttribution? {
            val fields = SPECS.mapNotNull { spec ->
                val raw = read(spec) ?: return@mapNotNull null
                val maxLength = spec.maxLength
                if (maxLength != null && raw.length > maxLength) return@mapNotNull null
                spec.type.parse(raw)?.let { spec.contextName to it }
            }.toMap()
            return if (fields.isEmpty()) null else ClickAttribution(fields)
        }

        private fun queryParameters(url: String): Map<String, String> {
            // Read leniently rather than through java.net.URI, which rejects the real URLs
            // Releva returns. The fragment is cut per value, not off the whole query: a decoded
            // `%23` in a merchant parameter leaves a raw `#` ahead of the appended pairs.
            val query = url.substringAfter('?', "")
            return query.split("&").mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    try {
                        decode(pair.substring(0, separator)) to
                            decode(pair.substring(separator + 1).substringBefore('#'))
                    } catch (e: IllegalArgumentException) {
                        // A merchant's own parameter may carry a bare `%`; it must not take the
                        // attribution pairs down with it.
                        null
                    }
                }
            }.toMap()
        }

        // URLDecoder is form decoding and would turn a literal `+` into a space; the web SDK's
        // decodeURIComponent does not, and an assistant URL arrives with its escapes already
        // decoded, so a `%2B` in an id reaches us as a bare `+`.
        private fun decode(value: String): String =
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }
}
