package cassandraclient.backend

import cassandraclient.core.ApiException
import cassandraclient.core.Column
import cassandraclient.core.ColumnKind
import cassandraclient.core.CountResult
import cassandraclient.core.CqlText
import cassandraclient.core.DeleteRows
import cassandraclient.core.Filter
import cassandraclient.core.IncrementCounter
import cassandraclient.core.InsertRow
import cassandraclient.core.RowsQuery
import cassandraclient.core.RowsResult
import cassandraclient.core.UpdateRow
import cassandraclient.core.qualified
import cassandraclient.core.quoteId
import cassandraclient.core.splitJsonArray
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await

/*
 * Every value crosses the API as the JSON text produced by Cassandra's own
 * toJson() and is written back with fromJson(). That keeps all CQL types
 * (bigint, decimal, UDTs, nested collections, vectors...) lossless without
 * re-implementing type conversion here.
 */

private val OPS = setOf("=", "<", "<=", ">", ">=", "IN", "CONTAINS", "CONTAINS KEY", "LIKE")

private fun assertColumn(cols: Map<String, Column>, name: String): Column =
    cols[name] ?: throw ApiException(400, "Unknown column $name")

private class Clause(val where: String, val params: List<String>)

private fun buildWhere(filters: List<Filter>, cols: Map<String, Column>): Clause {
    val clauses = mutableListOf<String>()
    val params = mutableListOf<String>()
    for (f in filters) {
        assertColumn(cols, f.column)
        if (f.op !in OPS) throw ApiException(400, "Unsupported operator ${f.op}")
        if (f.op == "IN") {
            val items = try {
                splitJsonArray(f.value)
            } catch (e: IllegalArgumentException) {
                throw ApiException(400, e.message!!)
            }
            clauses += "${quoteId(f.column)} IN (${items.joinToString(", ") { "fromJson(?)" }})"
            params += items
        } else {
            clauses += "${quoteId(f.column)} ${f.op} fromJson(?)"
            params += f.value
        }
    }
    return Clause(if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}", params)
}

private fun keyWhere(key: Map<String, String>, columns: List<Column>): Clause {
    val pk = columns.filter { it.isKey }
    val missing = pk.filter { it.name !in key }
    // Clustering columns may be omitted only for static-only rows; partition key is mandatory.
    if (missing.any { it.kind == ColumnKind.PARTITION_KEY }) {
        throw ApiException(400, "Missing partition key column(s): ${missing.joinToString(", ") { it.name }}")
    }
    val used = pk.filter { it.name in key }
    return Clause(used.joinToString(" AND ") { "${quoteId(it.name)} = fromJson(?)" }, used.map { key.getValue(it.name) })
}

private suspend fun CqlSession.bound(cql: String, params: List<String>): BoundStatement =
    prepareAsync(cql).await().bind(*params.toTypedArray())

suspend fun queryRows(session: CqlSession, q: RowsQuery): RowsResult {
    val columns = getColumns(session, q.keyspace, q.table)
    val cols = columns.associateBy { it.name }
    val select = columns.joinToString(", ") { "toJson(${quoteId(it.name)}) AS ${quoteId(it.name)}" }
    val clause = buildWhere(q.filters, cols)
    val cql = "SELECT $select FROM ${qualified(q.keyspace, q.table)}${clause.where}${if (q.allowFiltering) " ALLOW FILTERING" else ""}"
    val started = System.nanoTime()
    var statement = session.bound(cql, clause.params).setPageSize((q.pageSize ?: 100).coerceIn(1, 5000))
    q.pageState?.ifEmpty { null }?.let { statement = statement.setPagingState(pageStateFrom(it)) }
    val rs = session.executeAsync(statement).await()
    return RowsResult(
        columns = columns,
        rows = rs.currentPage().map { row -> columns.withIndex().associate { (i, c) -> c.name to row.getString(i) } },
        pageState = pageStateOf(rs),
        cql = displayCql(cql, clause.params),
        elapsedMs = (System.nanoTime() - started) / 1_000_000,
        warnings = rs.executionInfo.warnings,
    )
}

suspend fun countRows(session: CqlSession, q: RowsQuery): CountResult {
    val cols = getColumns(session, q.keyspace, q.table).associateBy { it.name }
    val clause = buildWhere(q.filters, cols)
    val cql = "SELECT COUNT(*) FROM ${qualified(q.keyspace, q.table)}${clause.where}${if (q.allowFiltering) " ALLOW FILTERING" else ""}"
    val rs = session.executeAsync(session.bound(cql, clause.params).setTimeout(Duration.ofSeconds(120))).await()
    return CountResult(rs.one()!!.getLong(0).toString())
}

suspend fun insertRow(session: CqlSession, m: InsertRow): CqlText {
    val columns = getColumns(session, m.keyspace, m.table)
    val cols = columns.associateBy { it.name }
    val entries = m.values.entries.filter { it.value != null }
    for (e in entries) assertColumn(cols, e.key)
    val missing = columns.filter { c -> c.isKey && entries.none { it.key == c.name } }
    if (missing.isNotEmpty()) throw ApiException(400, "Missing primary key column(s): ${missing.joinToString(", ") { it.name }}")
    val cql = "INSERT INTO ${qualified(m.keyspace, m.table)} (${entries.joinToString(", ") { quoteId(it.key) }}) " +
        "VALUES (${entries.joinToString(", ") { "fromJson(?)" }})${ttlClause(m.ttl)}"
    val params = entries.map { it.value!! }
    session.executeAsync(session.bound(cql, params)).await()
    return CqlText(displayCql(cql, params))
}

suspend fun updateRow(session: CqlSession, m: UpdateRow): CqlText {
    val columns = getColumns(session, m.keyspace, m.table)
    val cols = columns.associateBy { it.name }
    val sets = m.values.entries.toList()
    if (sets.isEmpty()) throw ApiException(400, "Nothing to update")
    for (e in sets) {
        val col = assertColumn(cols, e.key)
        if (col.isKey) throw ApiException(400, "Primary key column ${e.key} cannot be updated; delete and re-insert the row instead")
        if (col.type == "counter") throw ApiException(400, "Counter column ${e.key} can only be incremented")
    }
    val k = keyWhere(m.key, columns)
    val assignments = sets.joinToString(", ") { "${quoteId(it.key)} = ${if (it.value == null) "null" else "fromJson(?)"}" }
    val cql = "UPDATE ${qualified(m.keyspace, m.table)}${ttlClause(m.ttl)} SET $assignments WHERE ${k.where}"
    val params = sets.mapNotNull { it.value } + k.params
    session.executeAsync(session.bound(cql, params)).await()
    return CqlText(displayCql(cql, params))
}

suspend fun incrementCounter(session: CqlSession, m: IncrementCounter): CqlText {
    val columns = getColumns(session, m.keyspace, m.table)
    val col = assertColumn(columns.associateBy { it.name }, m.column)
    if (col.type != "counter") throw ApiException(400, "${m.column} is not a counter")
    if (!Regex("^-?\\d+$").matches(m.delta)) throw ApiException(400, "Delta must be an integer")
    val k = keyWhere(m.key, columns)
    val c = quoteId(m.column)
    val cql = "UPDATE ${qualified(m.keyspace, m.table)} SET $c = $c + ${m.delta} WHERE ${k.where}"
    session.executeAsync(session.bound(cql, k.params)).await()
    return CqlText(displayCql(cql, k.params))
}

suspend fun deleteRows(session: CqlSession, m: DeleteRows): CqlText = coroutineScope {
    val columns = getColumns(session, m.keyspace, m.table)
    val statements = m.keys.map { key ->
        async {
            val k = keyWhere(key, columns)
            val cql = "DELETE FROM ${qualified(m.keyspace, m.table)} WHERE ${k.where}"
            session.executeAsync(session.bound(cql, k.params)).await()
            displayCql(cql, k.params)
        }
    }.awaitAll()
    CqlText(statements.joinToString(";\n"))
}

private fun ttlClause(ttl: Int?) = if (ttl != null && ttl > 0) " USING TTL $ttl" else ""

/** Human readable CQL with bind markers replaced by their fromJson literals (display only). */
private fun displayCql(cql: String, params: List<String>): String {
    var i = 0
    return Regex("fromJson\\(\\?\\)").replace(cql) {
        val v = params.getOrNull(i++).orEmpty()
        "fromJson('${v.replace("'", "''")}')"
    }
}
