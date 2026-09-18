package io.github.kurue.bram.core.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Checks a call's arguments against the tool's own JSON schema before anything runs.
 *
 * The schemas are hand-written and small, so this understands the subset they use — object roots,
 * `required`, `type`, and `additionalProperties: false` — rather than pulling in a schema library.
 * The point is not academic validation: a model that invents an argument or forgets a required one
 * gets a message naming the problem, which it can fix on the next turn, instead of a
 * handler-specific failure that leaks an implementation detail and teaches nothing.
 */
object ToolArgumentValidator {

    /** Null when the arguments satisfy the schema; otherwise a sentence for the model. */
    fun validate(schemaJson: String, argumentsJson: String): String? {
        val schema = runCatching { JSONObject(schemaJson) }.getOrNull() ?: return null
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "Arguments were not a JSON object: $argumentsJson"

        val properties = schema.optJSONObject("properties") ?: JSONObject()
        schema.optJSONArray("required")?.let { required ->
            for (index in 0 until required.length()) {
                val name = required.optString(index)
                if (name.isNotEmpty() && !arguments.has(name)) {
                    return "Missing required argument \"$name\"."
                }
            }
        }
        if (schema.optBoolean("additionalProperties", true).not()) {
            arguments.keys().forEach { name ->
                if (!properties.has(name)) return "Unknown argument \"$name\"."
            }
        }
        arguments.keys().forEach { name ->
            val property = properties.optJSONObject(name) ?: return@forEach
            val expected = property.optString("type")
            if (expected.isEmpty()) return@forEach
            val actual = if (arguments.isNull(name)) null else arguments.get(name)
            if (actual != null && !matchesType(actual, expected)) {
                return "Argument \"$name\" should be of type $expected."
            }
        }
        return null
    }

    private fun matchesType(value: Any, expected: String): Boolean = when (expected) {
        "string" -> value is String
        "boolean" -> value is Boolean
        "integer" -> value is Int || value is Long
        "number" -> value is Number
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        else -> true
    }
}
