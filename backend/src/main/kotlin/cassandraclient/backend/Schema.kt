package cassandraclient.backend

import cassandraclient.core.ApiException
import cassandraclient.core.Column
import cassandraclient.core.ColumnKind
import cassandraclient.core.FunctionSummary
import cassandraclient.core.IndexInfo
import cassandraclient.core.Keyspace
import cassandraclient.core.TableInfo
import cassandraclient.core.TableKind
import cassandraclient.core.TableSummary
import cassandraclient.core.UdtField
import cassandraclient.core.UdtSummary
import cassandraclient.core.ViewSummary
import cassandraclient.core.qualified
import cassandraclient.core.quoteId
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

private val SYSTEM_KEYSPACES = setOf(
    "system", "system_auth", "system_schema", "system_distributed", "system_traces", "system_views", "system_virtual_schema",
)

private val KIND_ORDER = mapOf(
    ColumnKind.PARTITION_KEY to 0,
    ColumnKind.CLUSTERING to 1,
    ColumnKind.STATIC to 2,
    ColumnKind.REGULAR to 3,
)

private fun Row.strings(column: String): List<String> = getList(column, String::class.java).orEmpty()

private fun Row.toFunction(nameColumn: String) =
    FunctionSummary(getString(nameColumn)!!, strings("argument_types"), getString("return_type")!!)

suspend fun listKeyspaces(session: CqlSession): List<Keyspace> = coroutineScope {
    fun q(cql: String) = async { session.rows(cql) }
    fun optional(cql: String) = async { runCatching { session.rows(cql) }.getOrDefault(emptyList()) }

    val keyspaces = q("SELECT keyspace_name, durable_writes, replication FROM system_schema.keyspaces")
    val tables = q("SELECT keyspace_name, table_name, comment FROM system_schema.tables")
    val views = q("SELECT keyspace_name, view_name, base_table_name FROM system_schema.views")
    val udts = q("SELECT keyspace_name, type_name, field_names, field_types FROM system_schema.types")
    val functions = q("SELECT keyspace_name, function_name, argument_types, return_type FROM system_schema.functions")
    val aggregates = q("SELECT keyspace_name, aggregate_name, argument_types, return_type FROM system_schema.aggregates")
    val vKeyspaces = optional("SELECT keyspace_name FROM system_virtual_schema.keyspaces")
    val vTables = optional("SELECT keyspace_name, table_name, comment FROM system_virtual_schema.tables")

    fun <T> List<Row>.inKs(ks: String, name: (T) -> String, map: (Row) -> T): List<T> =
        filter { it.getString("keyspace_name") == ks }.map(map).sortedBy { name(it).lowercase() }

    val regular = keyspaces.await().map { k ->
        val ks = k.getString("keyspace_name")!!
        Keyspace(
            name = ks,
            virtual = false,
            system = ks in SYSTEM_KEYSPACES,
            durableWrites = k.getBoolean("durable_writes"),
            replication = k.getMap("replication", String::class.java, String::class.java).orEmpty(),
            tables = tables.await().inKs(ks, TableSummary::name) { TableSummary(it.getString("table_name")!!, it.getString("comment").orEmpty()) },
            views = views.await().inKs(ks, ViewSummary::name) { ViewSummary(it.getString("view_name")!!, it.getString("base_table_name")!!) },
            types = udts.await().inKs(ks, UdtSummary::name) { r ->
                val types = r.strings("field_types")
                UdtSummary(r.getString("type_name")!!, r.strings("field_names").mapIndexed { i, n -> UdtField(n, types[i]) })
            },
            functions = functions.await().inKs(ks, FunctionSummary::name) { it.toFunction("function_name") },
            aggregates = aggregates.await().inKs(ks, FunctionSummary::name) { it.toFunction("aggregate_name") },
        )
    }

    val virtual = vKeyspaces.await().map { k ->
        val ks = k.getString("keyspace_name")!!
        Keyspace(
            name = ks,
            virtual = true,
            system = true,
            durableWrites = false,
            replication = emptyMap(),
            tables = vTables.await().inKs(ks, TableSummary::name) { TableSummary(it.getString("table_name")!!, it.getString("comment").orEmpty()) },
            views = emptyList(),
            types = emptyList(),
            functions = emptyList(),
            aggregates = emptyList(),
        )
    }

    (regular + virtual).sortedWith(compareBy<Keyspace> { it.system }.thenBy { it.name.lowercase() })
}

suspend fun getColumns(session: CqlSession, ks: String, table: String): List<Column> {
    val cql = "SELECT column_name, type, kind, position, clustering_order FROM %s WHERE keyspace_name = ? AND table_name = ?"
    var rows = session.rows(cql.replace("%s", "system_schema.columns"), ks, table)
    if (rows.isEmpty()) {
        rows = runCatching { session.rows(cql.replace("%s", "system_virtual_schema.columns"), ks, table) }.getOrDefault(emptyList())
    }
    if (rows.isEmpty()) throw ApiException(404, "Table $ks.$table not found")
    return rows
        .map {
            Column(
                name = it.getString("column_name")!!,
                type = it.getString("type")!!,
                kind = it.getString("kind")!!,
                position = it.getInt("position"),
                clusteringOrder = it.getString("clustering_order").orEmpty(),
            )
        }
        .sortedWith(compareBy<Column> { KIND_ORDER[it.kind] ?: 4 }.thenBy { it.position }.thenBy { it.name })
}

suspend fun describeTable(session: CqlSession, ks: String, table: String): TableInfo = coroutineScope {
    val columns = async { getColumns(session, ks, table) }
    val tableRow = async { session.rows("SELECT * FROM system_schema.tables WHERE keyspace_name = ? AND table_name = ?", ks, table) }
    val viewRow = async { session.rows("SELECT * FROM system_schema.views WHERE keyspace_name = ? AND view_name = ?", ks, table) }
    val virtualRow = async {
        runCatching { session.rows("SELECT * FROM system_virtual_schema.tables WHERE keyspace_name = ? AND table_name = ?", ks, table) }
            .getOrDefault(emptyList())
    }
    val indexes = async { session.rows("SELECT index_name, kind, options FROM system_schema.indexes WHERE keyspace_name = ? AND table_name = ?", ks, table) }
    val views = async { session.rows("SELECT view_name FROM system_schema.views WHERE keyspace_name = ? AND base_table_name = ? ALLOW FILTERING", ks, table) }

    val kind = when {
        viewRow.await().isNotEmpty() -> TableKind.VIEW
        virtualRow.await().isNotEmpty() && tableRow.await().isEmpty() -> TableKind.VIRTUAL
        else -> TableKind.TABLE
    }
    val optionsRow = tableRow.await().firstOrNull() ?: viewRow.await().firstOrNull() ?: virtualRow.await().firstOrNull()
    val options = linkedMapOf<String, JsonElement>()
    optionsRow?.columnDefinitions?.forEachIndexed { i, def ->
        val name = def.name.asInternal()
        if (name !in setOf("keyspace_name", "table_name", "view_name", "id", "base_table_id")) options[name] = optionsRow.plainAt(i)
    }
    val target = "${if (kind == TableKind.VIEW) "MATERIALIZED VIEW" else "TABLE"} ${qualified(ks, table)}"
    val cols = columns.await()

    TableInfo(
        keyspace = ks,
        name = table,
        kind = kind,
        columns = cols,
        options = options,
        isCounter = cols.any { it.type == "counter" },
        indexes = indexes.await().map {
            IndexInfo(it.getString("index_name")!!, it.getString("kind")!!, it.getMap("options", String::class.java, String::class.java).orEmpty())
        },
        views = views.await().map { it.getString("view_name")!! },
        ddl = try {
            describe(session, target)
        } catch (e: Exception) {
            "-- ${e.message}"
        },
    )
}

/** Runs server-side DESCRIBE (Cassandra 4.0+) and joins the create statements. */
suspend fun describe(session: CqlSession, target: String): String =
    session.rows("DESCRIBE $target").joinToString("\n\n") { it.getString("create_statement").orEmpty() }

suspend fun describeKeyspace(session: CqlSession, ks: String): String = describe(session, "KEYSPACE ${quoteId(ks)}")
