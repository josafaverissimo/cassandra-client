package cassandraclient.server

import cassandraclient.backend.CassandraBackend
import cassandraclient.backend.errorMessage
import cassandraclient.core.ApiErrorBody
import cassandraclient.core.ApiException
import cassandraclient.core.AppJson
import cassandraclient.core.ConnectionInput
import cassandraclient.core.DeleteRows
import cassandraclient.core.ExecuteRequest
import cassandraclient.core.IncrementCounter
import cassandraclient.core.InsertRow
import cassandraclient.core.RowsQuery
import cassandraclient.core.UpdateRow
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException

private suspend inline fun <reified T> ApplicationCall.respondJson(value: T) =
    respondText(AppJson.encodeToString(value), ContentType.Application.Json)

/**
 * Bodies must be declared as JSON: that forces a CORS preflight, which is what stops an arbitrary
 * website from sending CQL to this local server with a "simple" text/plain request.
 */
private suspend inline fun <reified T> ApplicationCall.body(): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw ApiException(415, "Content-Type must be application/json")
    }
    return AppJson.decodeFromString(receiveText())
}

private suspend fun ApplicationCall.ok() = respondText("""{"ok":true}""", ContentType.Application.Json)

private val ApplicationCall.conn get() = parameters["conn"]!!

private fun Route.api(backend: CassandraBackend) {
    get("/connections") { call.respondJson(backend.connections()) }
    post("/connections") { call.respondJson(backend.saveConnection(call.body())) }
    put("/connections/{id}") {
        val input = call.body<ConnectionInput>().copy(id = call.parameters["id"])
        call.respondJson(backend.saveConnection(input))
    }
    delete("/connections/{id}") {
        backend.deleteConnection(call.parameters["id"]!!)
        call.ok()
    }
    post("/connections/test") { call.respondJson(backend.testConnection(call.body())) }

    route("/c/{conn}") {
        post("/connect") { call.respondJson(backend.connect(call.conn)) }
        post("/disconnect") {
            backend.disconnect(call.conn)
            call.ok()
        }
        get("/cluster") { call.respondJson(backend.cluster(call.conn)) }
        get("/cluster/settings") { call.respondJson(backend.settings(call.conn)) }
        get("/cluster/clients") { call.respondJson(backend.clients(call.conn)) }

        get("/keyspaces") { call.respondJson(backend.keyspaces(call.conn)) }
        get("/keyspaces/{ks}/ddl") { call.respondJson(backend.keyspaceDdl(call.conn, call.parameters["ks"]!!)) }
        get("/tables/{ks}/{table}") { call.respondJson(backend.table(call.conn, call.parameters["ks"]!!, call.parameters["table"]!!)) }

        post("/rows/query") { call.respondJson(backend.queryRows(call.conn, call.body<RowsQuery>())) }
        post("/rows/count") { call.respondJson(backend.countRows(call.conn, call.body<RowsQuery>())) }
        post("/rows/insert") { call.respondJson(backend.insertRow(call.conn, call.body<InsertRow>())) }
        post("/rows/update") { call.respondJson(backend.updateRow(call.conn, call.body<UpdateRow>())) }
        post("/rows/increment") { call.respondJson(backend.incrementCounter(call.conn, call.body<IncrementCounter>())) }
        post("/rows/delete") { call.respondJson(backend.deleteRows(call.conn, call.body<DeleteRows>())) }

        post("/execute") { call.respondJson(backend.execute(call.conn, call.body<ExecuteRequest>())) }

        get("/auth") { call.respondJson(backend.auth(call.conn)) }
        get("/roles") { call.respondJson(backend.roles(call.conn)) }
        get("/roles/{role}/permissions") { call.respondJson(backend.permissions(call.conn, call.parameters["role"]!!)) }
    }
}

fun main() {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toInt() ?: 4310
    val backend = CassandraBackend()

    val server = embeddedServer(CIO, host = host, port = port) {
        install(Compression) { gzip() }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                val status = when (cause) {
                    is ApiException -> HttpStatusCode.fromValue(cause.status)
                    is SerializationException -> HttpStatusCode.UnprocessableEntity
                    else -> HttpStatusCode.BadRequest
                }
                call.respondText(
                    AppJson.encodeToString(ApiErrorBody(errorMessage(cause))),
                    ContentType.Application.Json,
                    status,
                )
            }
        }
        routing {
            route("/api") { api(backend) }
            staticResources("/", "web") { default("index.html") }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { backend.shutdown() } })
    println("Cassandra Client running at http://$host:$port")
    server.start(wait = true)
}
