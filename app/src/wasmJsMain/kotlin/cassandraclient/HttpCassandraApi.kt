package cassandraclient

import cassandraclient.core.ApiErrorBody
import cassandraclient.core.ApiException
import cassandraclient.core.AppJson
import cassandraclient.core.AuthInfo
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
import cassandraclient.core.ExecuteResult
import cassandraclient.core.IncrementCounter
import cassandraclient.core.InsertRow
import cassandraclient.core.Keyspace
import cassandraclient.core.Permission
import cassandraclient.core.Role
import cassandraclient.core.RowsQuery
import cassandraclient.core.RowsResult
import cassandraclient.core.Setting
import cassandraclient.core.TableInfo
import cassandraclient.core.UpdateRow
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalWasmJsInterop::class)
private fun origin(): String = js("window.location.origin")

/** The API as seen from the browser: same contract as the backend, across HTTP. */
class HttpCassandraApi : CassandraApi {
    private val client = HttpClient(Js)
    private val base = "${origin()}/api"

    private fun enc(part: String) = part.encodeURLPathPart()

    private suspend fun send(method: HttpMethod, path: String, body: String?): HttpResponse {
        val response = client.request("$base$path") {
            this.method = method
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val message = runCatching { AppJson.decodeFromString<ApiErrorBody>(text).error }.getOrNull()
            throw ApiException(response.status.value, message ?: "${response.status.value} ${response.status.description}")
        }
        return response
    }

    private suspend inline fun <reified T> get(path: String): T =
        AppJson.decodeFromString(send(HttpMethod.Get, path, null).bodyAsText())

    private suspend inline fun <reified B, reified T> post(path: String, body: B, method: HttpMethod = HttpMethod.Post): T =
        AppJson.decodeFromString(send(method, path, AppJson.encodeToString(body)).bodyAsText())

    private suspend inline fun <reified T> post(path: String): T =
        AppJson.decodeFromString(send(HttpMethod.Post, path, null).bodyAsText())

    override suspend fun connections(): List<Connection> = get("/connections")

    override suspend fun saveConnection(input: ConnectionInput): Connection {
        val id = input.id
        return if (id != null) post("/connections/${enc(id)}", input, HttpMethod.Put) else post("/connections", input)
    }

    override suspend fun deleteConnection(id: String) {
        send(HttpMethod.Delete, "/connections/${enc(id)}", null)
    }

    override suspend fun testConnection(input: ConnectionInput): ConnectionTest = post("/connections/test", input)

    override suspend fun connect(conn: String): ClusterInfo = post("/c/${enc(conn)}/connect")

    override suspend fun disconnect(conn: String) {
        send(HttpMethod.Post, "/c/${enc(conn)}/disconnect", null)
    }

    override suspend fun cluster(conn: String): ClusterInfo = get("/c/${enc(conn)}/cluster")
    override suspend fun settings(conn: String): List<Setting> = get("/c/${enc(conn)}/cluster/settings")
    override suspend fun clients(conn: String): List<JsonObject> = get("/c/${enc(conn)}/cluster/clients")

    override suspend fun keyspaces(conn: String): List<Keyspace> = get("/c/${enc(conn)}/keyspaces")
    override suspend fun keyspaceDdl(conn: String, keyspace: String): Ddl = get("/c/${enc(conn)}/keyspaces/${enc(keyspace)}/ddl")
    override suspend fun table(conn: String, keyspace: String, table: String): TableInfo = get("/c/${enc(conn)}/tables/${enc(keyspace)}/${enc(table)}")

    override suspend fun queryRows(conn: String, query: RowsQuery): RowsResult = post("/c/${enc(conn)}/rows/query", query)
    override suspend fun countRows(conn: String, query: RowsQuery): CountResult = post("/c/${enc(conn)}/rows/count", query)
    override suspend fun insertRow(conn: String, mutation: InsertRow): CqlText = post("/c/${enc(conn)}/rows/insert", mutation)
    override suspend fun updateRow(conn: String, mutation: UpdateRow): CqlText = post("/c/${enc(conn)}/rows/update", mutation)
    override suspend fun incrementCounter(conn: String, mutation: IncrementCounter): CqlText = post("/c/${enc(conn)}/rows/increment", mutation)
    override suspend fun deleteRows(conn: String, mutation: DeleteRows): CqlText = post("/c/${enc(conn)}/rows/delete", mutation)

    override suspend fun execute(conn: String, request: ExecuteRequest): ExecuteResult = post("/c/${enc(conn)}/execute", request)

    override suspend fun auth(conn: String): AuthInfo = get("/c/${enc(conn)}/auth")
    override suspend fun roles(conn: String): List<Role> = get("/c/${enc(conn)}/roles")
    override suspend fun permissions(conn: String, role: String): List<Permission> = get("/c/${enc(conn)}/roles/${enc(role)}/permissions")
}
