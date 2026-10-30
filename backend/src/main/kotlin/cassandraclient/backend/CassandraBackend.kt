package cassandraclient.backend

import cassandraclient.core.ApiException
import cassandraclient.core.CassandraApi
import cassandraclient.core.ClusterInfo
import cassandraclient.core.Connection
import cassandraclient.core.ConnectionInput
import cassandraclient.core.ConnectionTest
import cassandraclient.core.CountResult
import cassandraclient.core.CqlText
import cassandraclient.core.Ddl
import cassandraclient.core.DeleteRows
import cassandraclient.core.ExecuteRequest
import cassandraclient.core.IncrementCounter
import cassandraclient.core.InsertRow
import cassandraclient.core.RowsQuery
import cassandraclient.core.UpdateRow
import com.datastax.oss.driver.api.core.CqlSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** The driver-backed implementation of the API, used in-process by the desktop app and behind HTTP by the server. */
class CassandraBackend(private val manager: ConnectionManager = ConnectionManager()) : CassandraApi {

    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(400, errorMessage(e), e)
        }
    }

    private suspend fun <T> withSession(conn: String, block: suspend (CqlSession) -> T): T =
        guarded { block(manager.getSession(conn)) }

    override suspend fun connections(): List<Connection> = guarded { manager.listProfiles() }
    override suspend fun saveConnection(input: ConnectionInput): Connection = guarded { manager.upsertProfile(input) }
    override suspend fun deleteConnection(id: String) = guarded { manager.deleteProfile(id) }

    override suspend fun testConnection(input: ConnectionInput): ConnectionTest = guarded {
        // Testing an existing profile without retyping its password reuses the stored one.
        val stored = if (input.id != null && input.password == null) runCatching { manager.getProfile(input.id!!) }.getOrNull() else null
        val session = manager.openSession(
            ConnectionProfile(
                id = "test",
                name = input.name,
                contactPoints = input.contactPoints,
                port = input.port,
                localDataCenter = input.localDataCenter,
                username = input.username,
                password = input.password ?: stored?.password,
                ssl = input.ssl,
            ),
        )
        try {
            val local = session.rows("SELECT cluster_name, release_version, data_center FROM system.local").first()
            ConnectionTest(
                clusterName = local.getString("cluster_name").orEmpty(),
                version = local.getString("release_version").orEmpty(),
                datacenter = local.getString("data_center").orEmpty(),
            )
        } finally {
            runCatching { session.closeAsync().await() }
        }
    }

    override suspend fun connect(conn: String): ClusterInfo = withSession(conn) { clusterInfo(it) }
    override suspend fun disconnect(conn: String) = guarded { manager.disconnect(conn) }
    override suspend fun cluster(conn: String) = withSession(conn) { clusterInfo(it) }
    override suspend fun settings(conn: String) = withSession(conn) { clusterSettings(it) }
    override suspend fun clients(conn: String) = withSession(conn) { clusterClients(it) }

    override suspend fun keyspaces(conn: String) = withSession(conn) { listKeyspaces(it) }
    override suspend fun keyspaceDdl(conn: String, keyspace: String) = withSession(conn) { Ddl(describeKeyspace(it, keyspace)) }
    override suspend fun table(conn: String, keyspace: String, table: String) = withSession(conn) { describeTable(it, keyspace, table) }

    override suspend fun queryRows(conn: String, query: RowsQuery) = withSession(conn) { queryRows(it, query) }
    override suspend fun countRows(conn: String, query: RowsQuery): CountResult = withSession(conn) { countRows(it, query) }
    override suspend fun insertRow(conn: String, mutation: InsertRow): CqlText = withSession(conn) { insertRow(it, mutation) }
    override suspend fun updateRow(conn: String, mutation: UpdateRow): CqlText = withSession(conn) { updateRow(it, mutation) }
    override suspend fun incrementCounter(conn: String, mutation: IncrementCounter): CqlText = withSession(conn) { incrementCounter(it, mutation) }
    override suspend fun deleteRows(conn: String, mutation: DeleteRows): CqlText = withSession(conn) { deleteRows(it, mutation) }

    override suspend fun execute(conn: String, request: ExecuteRequest) = withSession(conn) { execute(it, request) }

    override suspend fun auth(conn: String) = withSession(conn) { authInfo(it) }
    override suspend fun roles(conn: String) = withSession(conn) { listRoles(it) }
    override suspend fun permissions(conn: String, role: String) = withSession(conn) { rolePermissions(it, role) }

    suspend fun shutdown() = manager.disconnectAll()
}
