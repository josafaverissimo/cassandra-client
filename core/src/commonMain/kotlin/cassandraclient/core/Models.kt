package cassandraclient.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Connection(
    val id: String,
    val name: String,
    val contactPoints: List<String>,
    val port: Int,
    val localDataCenter: String? = null,
    val username: String? = null,
    val ssl: Boolean = false,
    val color: String? = null,
    val hasPassword: Boolean = false,
    val connected: Boolean = false,
)

/** `password`: null keeps the stored one, an empty string clears it. */
@Serializable
data class ConnectionInput(
    val id: String? = null,
    val name: String,
    val contactPoints: List<String>,
    val port: Int,
    val localDataCenter: String? = null,
    val username: String? = null,
    val password: String? = null,
    val ssl: Boolean = false,
    val color: String? = null,
)

@Serializable
data class ConnectionTest(val clusterName: String, val version: String, val datacenter: String)

@Serializable
data class TableSummary(val name: String, val comment: String = "")

@Serializable
data class ViewSummary(val name: String, val baseTable: String)

@Serializable
data class UdtField(val name: String, val type: String)

@Serializable
data class UdtSummary(val name: String, val fields: List<UdtField>)

@Serializable
data class FunctionSummary(val name: String, val argumentTypes: List<String>, val returnType: String)

@Serializable
data class Keyspace(
    val name: String,
    val virtual: Boolean,
    val system: Boolean,
    val durableWrites: Boolean,
    val replication: Map<String, String>,
    val tables: List<TableSummary>,
    val views: List<ViewSummary>,
    val types: List<UdtSummary>,
    val functions: List<FunctionSummary>,
    val aggregates: List<FunctionSummary>,
)

object ColumnKind {
    const val PARTITION_KEY = "partition_key"
    const val CLUSTERING = "clustering"
    const val STATIC = "static"
    const val REGULAR = "regular"
}

@Serializable
data class Column(
    val name: String,
    val type: String,
    val kind: String,
    val position: Int,
    val clusteringOrder: String,
) {
    val isKey get() = kind == ColumnKind.PARTITION_KEY || kind == ColumnKind.CLUSTERING
}

object TableKind {
    const val TABLE = "table"
    const val VIEW = "view"
    const val VIRTUAL = "virtual"
}

@Serializable
data class IndexInfo(val name: String, val kind: String, val options: Map<String, String>)

@Serializable
data class TableInfo(
    val keyspace: String,
    val name: String,
    val kind: String,
    val columns: List<Column>,
    val options: Map<String, JsonElement>,
    val isCounter: Boolean,
    val indexes: List<IndexInfo>,
    val views: List<String>,
    val ddl: String,
)

/** Cell values are the JSON text produced by Cassandra's toJson(). */
typealias Row = Map<String, String?>

@Serializable
data class Filter(val column: String, val op: String, val value: String)

@Serializable
data class RowsQuery(
    val keyspace: String,
    val table: String,
    val filters: List<Filter> = emptyList(),
    val allowFiltering: Boolean = false,
    val pageSize: Int? = null,
    val pageState: String? = null,
)

@Serializable
data class RowsResult(
    val columns: List<Column>,
    val rows: List<Row>,
    val pageState: String?,
    val cql: String,
    val elapsedMs: Long,
    val warnings: List<String>,
)

@Serializable
data class CountResult(val count: String)

@Serializable
data class CqlText(val cql: String)

@Serializable
data class Ddl(val ddl: String)

@Serializable
data class DescribeRequest(val target: String)

@Serializable
data class InsertRow(val keyspace: String, val table: String, val values: Row, val ttl: Int? = null)

@Serializable
data class UpdateRow(
    val keyspace: String,
    val table: String,
    val key: Map<String, String>,
    val values: Row,
    val ttl: Int? = null,
)

@Serializable
data class IncrementCounter(
    val keyspace: String,
    val table: String,
    val key: Map<String, String>,
    val column: String,
    val delta: String,
)

@Serializable
data class DeleteRows(val keyspace: String, val table: String, val keys: List<Map<String, String>>)

@Serializable
data class ExecuteRequest(
    val cql: String,
    val keyspace: String? = null,
    val pageSize: Int? = null,
    val pageState: String? = null,
    val consistency: String? = null,
    val trace: Boolean = false,
    val continueOnError: Boolean = false,
)

@Serializable
data class ResultColumn(val name: String, val type: String)

@Serializable
data class TraceEvent(val activity: String, val source: String, val elapsedMicros: Int, val thread: String)

@Serializable
data class TraceInfo(
    val traceId: String,
    val coordinator: String? = null,
    val durationMicros: Int? = null,
    val events: List<TraceEvent> = emptyList(),
    val error: String? = null,
)

@Serializable
data class StatementResult(
    val statement: String,
    val columns: List<ResultColumn> = emptyList(),
    val rows: List<List<JsonElement>> = emptyList(),
    val pageState: String? = null,
    val elapsedMs: Long = 0,
    val warnings: List<String> = emptyList(),
    val queriedHost: String? = null,
    val trace: TraceInfo? = null,
    val error: String? = null,
)

@Serializable
data class ExecuteResult(val results: List<StatementResult>, val keyspace: String?)

@Serializable
data class AuthInfo(
    val authenticator: String?,
    val authorizer: String?,
    val roleManager: String?,
    val authenticationEnabled: Boolean?,
    val authorizationEnabled: Boolean?,
)

@Serializable
data class NodeInfo(
    val address: String,
    val nativeAddress: String,
    val datacenter: String,
    val rack: String,
    val version: String,
    val hostId: String,
    val tokens: Int,
    val up: Boolean?,
    val local: Boolean,
)

@Serializable
data class ClusterInfo(
    val clusterName: String,
    val partitioner: String,
    val cqlVersion: String,
    val nativeProtocolVersion: String,
    val nodes: List<NodeInfo>,
    val auth: AuthInfo,
)

@Serializable
data class Setting(val name: String, val value: String?)

@Serializable
data class Role(
    val name: String,
    val login: Boolean,
    val superuser: Boolean,
    val memberOf: List<String>,
    val options: JsonElement? = null,
    val datacenters: String? = null,
)

@Serializable
data class Permission(
    val grantedTo: String,
    val inherited: Boolean,
    val resource: String,
    val resourceCql: String,
    val permission: String,
)

@Serializable
data class ApiErrorBody(val error: String)

class ApiException(val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything the UI needs from Cassandra. The desktop app implements it in-process with the
 * driver; the browser can't speak the native protocol, so the web app calls the server over HTTP.
 */
interface CassandraApi {
    suspend fun connections(): List<Connection>
    suspend fun saveConnection(input: ConnectionInput): Connection
    suspend fun deleteConnection(id: String)
    suspend fun testConnection(input: ConnectionInput): ConnectionTest

    suspend fun connect(conn: String): ClusterInfo
    suspend fun disconnect(conn: String)
    suspend fun cluster(conn: String): ClusterInfo
    suspend fun settings(conn: String): List<Setting>
    suspend fun clients(conn: String): List<JsonObject>

    suspend fun keyspaces(conn: String): List<Keyspace>
    suspend fun keyspaceDdl(conn: String, keyspace: String): Ddl
    suspend fun table(conn: String, keyspace: String, table: String): TableInfo

    suspend fun queryRows(conn: String, query: RowsQuery): RowsResult
    suspend fun countRows(conn: String, query: RowsQuery): CountResult
    suspend fun insertRow(conn: String, mutation: InsertRow): CqlText
    suspend fun updateRow(conn: String, mutation: UpdateRow): CqlText
    suspend fun incrementCounter(conn: String, mutation: IncrementCounter): CqlText
    suspend fun deleteRows(conn: String, mutation: DeleteRows): CqlText

    suspend fun execute(conn: String, request: ExecuteRequest): ExecuteResult

    suspend fun auth(conn: String): AuthInfo
    suspend fun roles(conn: String): List<Role>
    suspend fun permissions(conn: String, role: String): List<Permission>
}

/** Executes CQL and throws on the first failing statement. */
suspend fun CassandraApi.run(conn: String, cql: String): List<StatementResult> {
    val results = execute(conn, ExecuteRequest(cql = cql)).results
    results.firstOrNull { it.error != null }?.let { throw ApiException(400, it.error!!) }
    return results
}
