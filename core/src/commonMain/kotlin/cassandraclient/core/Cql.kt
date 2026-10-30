package cassandraclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Always quote identifiers so mixed case and reserved words round-trip exactly. */
fun quoteId(name: String) = "\"${name.replace("\"", "\"\"")}\""
fun quoteStr(value: String) = "'${value.replace("'", "''")}'"
fun qualified(keyspace: String, name: String) = "${quoteId(keyspace)}.${quoteId(name)}"

val NATIVE_TYPES = listOf(
    "ascii", "bigint", "blob", "boolean", "counter", "date", "decimal", "double", "duration", "float",
    "inet", "int", "smallint", "text", "time", "timestamp", "timeuuid", "tinyint", "uuid", "varchar", "varint",
)

val TYPE_SUGGESTIONS = NATIVE_TYPES + listOf(
    "list<text>", "set<text>", "map<text, text>", "frozen<list<int>>", "tuple<int, text>", "vector<float, 3>",
)

private val STRING_TYPES = setOf(
    "ascii", "text", "varchar", "uuid", "timeuuid", "inet", "timestamp", "date", "time", "duration", "blob",
)
private val NUMBER_TYPES = setOf("bigint", "counter", "decimal", "double", "float", "int", "smallint", "tinyint", "varint")

enum class ValueKind { STRING, NUMBER, BOOLEAN, JSON }

fun unfrozen(type: String): String {
    var t = type.trim()
    while (t.startsWith("frozen<") && t.endsWith(">")) t = t.substring(7, t.length - 1).trim()
    return t
}

/** How a column's toJson() representation looks, which decides how the editor treats input. */
fun valueKind(type: String): ValueKind {
    val t = unfrozen(type)
    return when {
        t in STRING_TYPES -> ValueKind.STRING
        t in NUMBER_TYPES -> ValueKind.NUMBER
        t == "boolean" -> ValueKind.BOOLEAN
        else -> ValueKind.JSON
    }
}

/** Splits `a, b<c, d>` at top-level commas. */
private fun splitTypeArgs(s: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    s.forEachIndexed { i, ch ->
        when {
            ch == '<' -> depth++
            ch == '>' -> depth--
            ch == ',' && depth == 0 -> {
                out += s.substring(start, i).trim()
                start = i + 1
            }
        }
    }
    out += s.substring(start).trim()
    return out
}

private val COLLECTION_TYPE = Regex("^(list|set|map)<(.*)>$")

/** Type of the value on the right-hand side of a filter operator (CONTAINS uses the element type). */
fun filterValueType(type: String, op: String): String {
    val t = unfrozen(type)
    val m = COLLECTION_TYPE.find(t) ?: return t
    val collection = m.groupValues[1]
    val args = splitTypeArgs(m.groupValues[2])
    return when (op) {
        "CONTAINS" -> if (collection == "map") args[1] else args[0]
        "CONTAINS KEY" -> args[0]
        else -> t
    }
}

fun isNull(json: String?) = json == null || json == "null"

/** JSON text from toJson() → text shown in the editor (strings are unquoted). */
fun toEditable(json: String?, type: String): String {
    if (isNull(json)) return ""
    if (valueKind(type) == ValueKind.STRING) {
        return try {
            Json.parseToJsonElement(json!!).jsonPrimitive.content
        } catch (_: Exception) {
            json!!
        }
    }
    return json!!
}

private val NUMBER_LITERAL = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$")
private val NON_FINITE_LITERAL = Regex("^-?(NaN|Infinity)$")

/** Text typed by the user → JSON text accepted by fromJson(). Throws with a friendly message. */
fun fromEditable(text: String, type: String): String {
    val kind = valueKind(type)
    if (kind == ValueKind.STRING) return JsonPrimitive(text).toString()
    val trimmed = text.trim()
    return when (kind) {
        ValueKind.NUMBER -> {
            if (!NUMBER_LITERAL.matches(trimmed) && !NON_FINITE_LITERAL.matches(trimmed)) {
                throw IllegalArgumentException("\"$text\" is not a valid $type")
            }
            trimmed
        }
        ValueKind.BOOLEAN -> {
            if (trimmed != "true" && trimmed != "false") throw IllegalArgumentException("Use true or false")
            trimmed
        }
        else -> {
            try {
                Json.parseToJsonElement(trimmed)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid JSON for $type")
            }
            trimmed
        }
    }
}

/** Compact one-line rendering for the grid. */
fun displayValue(json: String?, type: String): String {
    if (isNull(json)) return "NULL"
    return if (valueKind(type) == ValueKind.STRING) toEditable(json, type) else json!!
}

/** Renders any plain value (from the query editor) as a cell string. */
fun plainToText(v: JsonElement): String = when {
    v is JsonNull -> "NULL"
    v is JsonPrimitive && v.isString -> v.content
    else -> v.toString()
}

val CONSISTENCY_LEVELS = listOf(
    "ANY", "ONE", "TWO", "THREE", "QUORUM", "ALL", "LOCAL_QUORUM", "EACH_QUORUM", "SERIAL", "LOCAL_SERIAL", "LOCAL_ONE",
)

val PERMISSIONS = listOf(
    "CREATE", "ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "DESCRIBE", "EXECUTE", "UNMASK", "SELECT_MASKED",
)

/** Permissions that Cassandra accepts for each resource type. */
val APPLICABLE: Map<String, List<String>> = linkedMapOf(
    "ALL KEYSPACES" to listOf("CREATE", "ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "UNMASK", "SELECT_MASKED"),
    "KEYSPACE" to listOf("CREATE", "ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "UNMASK", "SELECT_MASKED"),
    "TABLE" to listOf("ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "UNMASK", "SELECT_MASKED"),
    "ALL ROLES" to listOf("CREATE", "ALTER", "DROP", "AUTHORIZE", "DESCRIBE"),
    "ROLE" to listOf("ALTER", "DROP", "AUTHORIZE"),
    "ALL FUNCTIONS" to listOf("CREATE", "ALTER", "DROP", "AUTHORIZE", "EXECUTE"),
    "ALL FUNCTIONS IN KEYSPACE" to listOf("CREATE", "ALTER", "DROP", "AUTHORIZE", "EXECUTE"),
    "ALL MBEANS" to listOf("AUTHORIZE", "DESCRIBE", "EXECUTE", "MODIFY", "SELECT"),
)

fun resourceKindOf(resourceCql: String): String? =
    APPLICABLE.keys.sortedByDescending { it.length }.firstOrNull { resourceCql == it || resourceCql.startsWith("$it ") }

/** Applicable permissions for a resource; FUNCTION/MBEAN fall back to everything. */
fun applicableFor(resourceCql: String): List<String> {
    if (resourceCql.startsWith("FUNCTION ")) return listOf("ALTER", "DROP", "AUTHORIZE", "EXECUTE")
    if (resourceCql.startsWith("MBEAN ")) return APPLICABLE.getValue("ALL MBEANS")
    return resourceKindOf(resourceCql)?.let { APPLICABLE.getValue(it) } ?: PERMISSIONS
}

/** Turns `<table ks.t>`-style names into a short readable label. */
fun resourceLabel(resource: String) = resource.removePrefix("<").removeSuffix(">")

/** Maps a resource as printed by LIST PERMISSIONS (e.g. `<table ks.t>`) to GRANT/REVOKE syntax. */
fun resourceToCql(resource: String): String {
    val inner = resourceLabel(resource)
    fun m(re: String) = Regex(re).find(inner)?.groupValues
    when (inner) {
        "all keyspaces" -> return "ALL KEYSPACES"
        "all roles" -> return "ALL ROLES"
        "all functions" -> return "ALL FUNCTIONS"
        "all mbeans" -> return "ALL MBEANS"
    }
    m("^all functions in (?:keyspace )?(.+)$")?.let { return "ALL FUNCTIONS IN KEYSPACE ${quoteId(it[1])}" }
    m("^keyspace (.+)$")?.let { return "KEYSPACE ${quoteId(it[1])}" }
    m("^table ([^.]+)\\.(.+)$")?.let { return "TABLE ${qualified(it[1], it[2])}" }
    m("^role (.+)$")?.let { return "ROLE ${quoteStr(it[1])}" }
    m("^mbean (.+)$")?.let { return "MBEAN ${quoteStr(it[1])}" }
    m("^function ([^.]+)\\.([^(]+)(\\(.*\\))$")?.let { return "FUNCTION ${qualified(it[1], it[2])}${it[3]}" }
    return inner.uppercase()
}

private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
private val LINE_COMMENT = Regex("(--|//).*$", RegexOption.MULTILINE)

private fun stripComments(s: String) = s.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

/**
 * Splits a CQL script into statements, honouring quoted strings, quoted
 * identifiers, $$ bodies and comments.
 */
fun splitStatements(script: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    val n = script.length
    while (i < n) {
        val ch = script[i]
        val next = script.getOrNull(i + 1)
        if (ch == '\'' || ch == '"') {
            var j = i + 1
            while (j < n) {
                if (script[j] == ch) {
                    if (script.getOrNull(j + 1) == ch) j += 2 else break
                } else {
                    j++
                }
            }
            val end = minOf(j + 1, n)
            current.append(script, i, end)
            i = end
        } else if (ch == '$' && next == '$') {
            val end = script.indexOf("$$", i + 2)
            val j = if (end == -1) n else end + 2
            current.append(script, i, j)
            i = j
        } else if ((ch == '-' && next == '-') || (ch == '/' && next == '/')) {
            val end = script.indexOf('\n', i)
            val j = if (end == -1) n else end
            current.append(script, i, j)
            i = j
        } else if (ch == '/' && next == '*') {
            val end = script.indexOf("*/", i + 2)
            val j = if (end == -1) n else end + 2
            current.append(script, i, j)
            i = j
        } else if (ch == ';') {
            out += current.toString()
            current.clear()
            i++
        } else {
            current.append(ch)
            i++
        }
    }
    out += current.toString()
    return out.map { it.trim() }.filter { stripComments(it).trim().isNotEmpty() }
}

/** Splits the top-level elements of a JSON array without parsing numbers (keeps bigint precision). */
fun splitJsonArray(text: String): List<String> {
    val s = text.trim()
    if (!s.startsWith("[") || !s.endsWith("]")) throw IllegalArgumentException("IN expects a JSON array, e.g. [1, 2, 3]")
    val items = mutableListOf<String>()
    var depth = 0
    var inStr = false
    var start = 1
    var i = 1
    while (i < s.length - 1) {
        val ch = s[i]
        if (inStr) {
            if (ch == '\\') i++ else if (ch == '"') inStr = false
        } else if (ch == '"') {
            inStr = true
        } else if (ch == '[' || ch == '{') {
            depth++
        } else if (ch == ']' || ch == '}') {
            depth--
        } else if (ch == ',' && depth == 0) {
            items += s.substring(start, i).trim()
            start = i + 1
        }
        i++
    }
    val last = s.substring(start, s.length - 1).trim()
    if (last.isNotEmpty()) items += last
    return items
}

/** Re-indents JSON text without parsing numbers (keeps bigint precision). */
fun prettyJson(text: String): String {
    val out = StringBuilder()
    var indent = 0
    var inStr = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inStr) {
            out.append(ch)
            if (ch == '\\') {
                i++
                text.getOrNull(i)?.let(out::append)
            } else if (ch == '"') {
                inStr = false
            }
        } else if (ch == '"') {
            inStr = true
            out.append(ch)
        } else if (ch == '{' || ch == '[') {
            val close = if (ch == '{') '}' else ']'
            val next = text.substring(i + 1).trimStart().firstOrNull()
            if (next == close) {
                out.append(ch).append(close)
                i = text.indexOf(close, i)
            } else {
                indent++
                out.append(ch).append('\n').append("  ".repeat(indent))
            }
        } else if (ch == '}' || ch == ']') {
            indent--
            out.append('\n').append("  ".repeat(indent)).append(ch)
        } else if (ch == ',') {
            out.append(",\n").append("  ".repeat(indent))
        } else if (ch == ':') {
            out.append(": ")
        } else if (!ch.isWhitespace()) {
            out.append(ch)
        }
        i++
    }
    return out.toString()
}
