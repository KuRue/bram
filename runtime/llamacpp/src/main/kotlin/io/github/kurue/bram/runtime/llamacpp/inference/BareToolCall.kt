package io.github.kurue.bram.runtime.llamacpp.inference

import org.json.JSONObject

/**
 * Recovers a tool call a model wrote without the marker its chat format requires.
 *
 * This is a compatibility shim for small models, not the way tool calling is meant to work. The
 * proper path is llama.cpp's own parser, which only accepts a call the format marked as one; this
 * runs after that has found nothing and after a retry with the tool choice forced has also failed.
 * LFM2.5 writes `[write_note(name='x', body='y')]` and never emits its `<|tool_call_start|>`, which
 * is what this exists for.
 *
 * The danger it introduces is that "the model invoked a tool" becomes "the reply contains text
 * shaped like a call". A model quoting a user, explaining itself, or — worst — echoing tool output
 * that contains a call-shaped string could all produce one. So the match is fenced hard:
 *
 * - the name must be one of the tools actually offered this turn, not any identifier;
 * - the call must be the whole reply, bar surrounding whitespace and brackets, so a call mentioned
 *   in the middle of prose is not recovered;
 * - every argument must be a simple literal, since anything needing real parsing is a sign this is
 *   prose rather than a call.
 *
 * A recovered call is also marked as such, so the approval gate always asks about it rather than
 * matching a remembered allowance. See `ToolCall.recovered`.
 */
internal object BareToolCall {

    /** The call the reply amounts to, or null if it is not one. */
    fun recover(reply: String, offeredToolNames: Set<String>): JSONObject? {
        if (offeredToolNames.isEmpty()) return null
        val trimmed = reply.trim().trim('[', ']').trim()
        val open = trimmed.indexOf('(')
        if (open <= 0 || !trimmed.endsWith(")")) return null

        val name = trimmed.substring(0, open).trim()
        if (name !in offeredToolNames) return null

        val arguments = parseArguments(trimmed.substring(open + 1, trimmed.length - 1)) ?: return null
        return JSONObject()
            .put("name", name)
            .put("arguments", arguments.toString())
            .put("id", "recovered_$name")
    }

    /**
     * Reads `key='value'` pairs into JSON, refusing anything that is not a plain literal.
     *
     * Refusing is the point: a nested call, an expression, or an unterminated string means this is
     * text that resembles a call rather than one, and recovering it would invent arguments the
     * model did not write.
     */
    private fun parseArguments(source: String): JSONObject? {
        val arguments = JSONObject()
        if (source.isBlank()) return arguments
        for (part in splitTopLevel(source)) {
            val equals = part.indexOf('=')
            if (equals <= 0) return null
            val key = part.substring(0, equals).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' }) return null
            val value = part.substring(equals + 1).trim()
            when {
                value.length >= 2 && value.first() == '\'' && value.last() == '\'' ->
                    arguments.put(key, value.substring(1, value.length - 1))
                value.length >= 2 && value.first() == '"' && value.last() == '"' ->
                    arguments.put(key, value.substring(1, value.length - 1))
                value == "true" || value == "false" -> arguments.put(key, value.toBoolean())
                value.toLongOrNull() != null -> arguments.put(key, value.toLong())
                value.toDoubleOrNull() != null -> arguments.put(key, value.toDouble())
                else -> return null
            }
        }
        return arguments
    }

    /** Splits on commas that are not inside a quoted string, so a comma in a value survives. */
    private fun splitTopLevel(source: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (character in source) {
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                }
                character == '\'' || character == '"' -> {
                    quote = character
                    current.append(character)
                }
                character == ',' -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        parts += current.toString()
        return parts.map(String::trim).filter(String::isNotEmpty)
    }
}
