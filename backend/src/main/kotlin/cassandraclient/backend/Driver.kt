package cassandraclient.backend

import com.datastax.oss.driver.api.core.AllNodesFailedException
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.cql.Statement
import com.datastax.oss.driver.api.core.data.CqlDuration
import com.datastax.oss.driver.api.core.data.TupleValue
import com.datastax.oss.driver.api.core.data.UdtValue
import com.datastax.oss.protocol.internal.util.Bytes
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Converts driver values (UUID, ByteBuffer, Map...) into JSON-safe values. */
fun toPlain(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
    is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
    // Rendered as text: a JSON number would not survive every reader beyond 2^53.
    is Long, is BigInteger, is BigDecimal -> JsonPrimitive(value.toString())
    is Number -> JsonPrimitive(value)
    is ByteBuffer -> JsonPrimitive(Bytes.toHexString(value))
    is Instant -> JsonPrimitive(value.toString())
    is UUID, is LocalDate, is LocalTime, is CqlDuration -> JsonPrimitive(value.toString())
    is InetAddress -> JsonPrimitive(value.hostAddress)
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) -> (if (k is String) k else toPlain(k).toString()) to toPlain(v) },
    )
    is Iterable<*> -> JsonArray(value.map(::toPlain))
    is TupleValue -> JsonArray(List(value.type.componentTypes.size) { toPlain(value.getObject(it)) })
    is UdtValue -> JsonObject(
        value.type.fieldNames.withIndex().associate { (i, name) -> name.asInternal() to toPlain(value.getObject(i)) },
    )
    else -> JsonPrimitive(value.toString())
}

/** A cell as a plain value; custom types with no codec fall back to their raw bytes. */
fun Row.plainAt(i: Int): JsonElement =
    try {
        toPlain(getObject(i))
    } catch (_: Exception) {
        toPlain(getBytesUnsafe(i))
    }

fun Row.has(column: String) = columnDefinitions.contains(column)

fun Row.plain(column: String): JsonElement = if (has(column)) plainAt(columnDefinitions.firstIndexOf(column)) else JsonNull

fun Row.textOrNull(column: String): String? = if (has(column)) getString(column) else null

fun JsonElement.plainText(): String = when {
    this is JsonNull -> "null"
    this is JsonPrimitive && isString -> content
    else -> toString()
}

fun pageStateOf(rs: AsyncResultSet): String? = rs.executionInfo.pagingState?.let(Bytes::toHexString)

fun pageStateFrom(state: String): ByteBuffer = Bytes.fromHexString(state)

/** Reads every page of a statement's result. */
suspend fun CqlSession.rows(statement: Statement<*>): List<Row> {
    var rs = executeAsync(statement).await()
    val out = rs.currentPage().toMutableList()
    while (rs.hasMorePages()) {
        rs = rs.fetchNextPage().await()
        out += rs.currentPage()
    }
    return out
}

suspend fun CqlSession.rows(cql: String, vararg params: Any?): List<Row> =
    rows(SimpleStatement.newInstance(cql, *params))

fun errorMessage(e: Throwable): String {
    if (e is AllNodesFailedException) {
        val inner = e.allErrors.entries.joinToString("; ") { (node, errors) ->
            "${node.endPoint.toString().removePrefix("/")}: ${errors.firstOrNull()?.message ?: "unknown error"}"
        }
        if (inner.isNotEmpty()) return "No host available — $inner"
    }
    return e.message ?: e.toString()
}
