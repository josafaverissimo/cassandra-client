package cassandraclient.backend

import cassandraclient.core.AuthInfo
import cassandraclient.core.ClusterInfo
import cassandraclient.core.ExecuteRequest
import cassandraclient.core.ExecuteResult
import cassandraclient.core.NodeInfo
import cassandraclient.core.Permission
import cassandraclient.core.ResultColumn
import cassandraclient.core.Role
import cassandraclient.core.Setting
import cassandraclient.core.StatementResult
import cassandraclient.core.TraceEvent
import cassandraclient.core.TraceInfo
import cassandraclient.core.quoteStr
import cassandraclient.core.resourceToCql
import cassandraclient.core.splitStatements
import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.ExecutionInfo
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.metadata.NodeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject

suspend fun authInfo(session: CqlSession): AuthInfo {
    val rows = runCatching {
        session.rows("SELECT name, value FROM system_views.settings WHERE name IN ('authenticator', 'authorizer', 'role_manager')")
    }.getOrDefault(emptyList())
    fun get(name: String) = rows.firstOrNull { it.getString("name") == name }?.getString("value")
    val authenticator = get("authenticator")
    val authorizer = get("authorizer")
    return AuthInfo(
        authenticator = authenticator,
        authorizer = authorizer,
        roleManager = get("role_manager"),
        authenticationEnabled = authenticator?.let { !it.contains("AllowAll") },
        authorizationEnabled = authorizer?.let { !it.contains("AllowAll") },
    )
}

suspend fun listRoles(session: CqlSession): List<Role> = coroutineScope {
    val table = async { runCatching { session.rows("SELECT role, can_login, is_superuser, member_of FROM system_auth.roles") } }
    val listed = async { runCatching { session.rows("LIST ROLES") } }
    val tableRows = table.await().getOrNull()
    val listedResult = listed.await()
    if (tableRows == null) listedResult.getOrThrow()
    val extra = listedResult.getOrDefault(emptyList()).associateBy { it.getString("role") }
    (tableRows ?: listedResult.getOrThrow())
        .map { r ->
            val name = r.getString("role")!!
            val more = extra[name]
            Role(
                name = name,
                login = if (r.has("can_login")) r.getBoolean("can_login") else r.getBoolean("login"),
                superuser = if (r.has("is_superuser")) r.getBoolean("is_superuser") else r.getBoolean("super"),
                memberOf = if (r.has("member_of")) r.getSet("member_of", String::class.java).orEmpty().sorted() else emptyList(),
                options = more?.plain("options"),
                datacenters = more?.textOrNull("datacenters"),
            )
        }
        .sortedBy { it.name.lowercase() }
}

suspend fun rolePermissions(session: CqlSession, role: String): List<Permission> =
    session.rows("LIST ALL PERMISSIONS OF ${quoteStr(role)}").map { r ->
        val resource = r.getString("resource")!!
        val grantedTo = r.getString("role")!!
        Permission(
            grantedTo = grantedTo,
            inherited = grantedTo != role,
            resource = resource,
            resourceCql = resourceToCql(resource),
            permission = r.getString("permission")!!,
        )
    }

suspend fun clusterInfo(session: CqlSession): ClusterInfo {
    val l = session.rows("SELECT * FROM system.local").first()
    val peers = try {
        session.rows("SELECT * FROM system.peers_v2")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        session.rows("SELECT * FROM system.peers")
    }
    val hosts = session.metadata.nodes
    fun node(r: Row, isLocal: Boolean): NodeInfo {
        val address = if (isLocal) r.plain("broadcast_address").takeUnless { it.plainText() == "null" } ?: r.plain("rpc_address") else r.plain("peer")
        val native = if (isLocal) r.plain("rpc_address") else r.plain("native_address").takeUnless { it.plainText() == "null" } ?: r.plain("rpc_address")
        val hostId = r.getUuid("host_id")
        return NodeInfo(
            address = address.plainText(),
            nativeAddress = native.plainText(),
            datacenter = r.getString("data_center").orEmpty(),
            rack = r.getString("rack").orEmpty(),
            version = r.getString("release_version").orEmpty(),
            hostId = hostId.toString(),
            tokens = r.getSet("tokens", String::class.java).orEmpty().size,
            up = hosts[hostId]?.let { it.state == NodeState.UP },
            local = isLocal,
        )
    }
    return ClusterInfo(
        clusterName = l.getString("cluster_name").orEmpty(),
        partitioner = l.getString("partitioner").orEmpty(),
        cqlVersion = l.getString("cql_version").orEmpty(),
        nativeProtocolVersion = l.getString("native_protocol_version").orEmpty(),
        nodes = listOf(node(l, true)) + peers.map { node(it, false) },
        auth = authInfo(session),
    )
}

suspend fun clusterSettings(session: CqlSession): List<Setting> =
    session.rows("SELECT name, value FROM system_views.settings").map { Setting(it.getString("name")!!, it.getString("value")) }

suspend fun clusterClients(session: CqlSession): List<JsonObject> =
    session.rows("SELECT * FROM system_views.clients").map { r ->
        JsonObject(r.columnDefinitions.withIndex().associate { (i, def) -> def.name.asInternal() to r.plainAt(i) })
    }

private val USE_STATEMENT = Regex("^USE\\s+(\"(?:[^\"]|\"\")+\"|\\w+)$", RegexOption.IGNORE_CASE)

/** `USE ks` → ks, following CQL's identifier rules (unquoted names are lower-cased). */
private fun useTarget(statement: String): String? {
    val raw = USE_STATEMENT.find(statement.trim())?.groupValues?.get(1) ?: return null
    return if (raw.startsWith("\"")) raw.substring(1, raw.length - 1).replace("\"\"", "\"") else raw.lowercase()
}

private fun elapsedSince(started: Long) = (System.nanoTime() - started) / 1_000_000

suspend fun execute(session: CqlSession, req: ExecuteRequest): ExecuteResult {
    val statements = splitStatements(req.cql)
    // The keyspace travels with each request (protocol v5) rather than living on the shared
    // session, so tabs pointing at different keyspaces can't interfere with each other.
    var keyspace = req.keyspace?.ifEmpty { null }
    val consistency = req.consistency?.let { DefaultConsistencyLevel.valueOf(it) }
    val results = mutableListOf<StatementResult>()
    for (statement in statements) {
        val started = System.nanoTime()
        try {
            val target = useTarget(statement)
            if (target != null) {
                if (session.rows("SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?", target).isEmpty() &&
                    session.rows("SELECT keyspace_name FROM system_virtual_schema.keyspaces WHERE keyspace_name = ?", target).isEmpty()
                ) {
                    throw IllegalArgumentException("Keyspace '$target' does not exist")
                }
                keyspace = target
                results += StatementResult(statement = statement, elapsedMs = elapsedSince(started))
                continue
            }
            var request = SimpleStatement.newInstance(statement)
                .setPageSize((req.pageSize ?: 200).coerceIn(1, 5000))
                .setTracing(req.trace)
            keyspace?.let { request = request.setKeyspace(CqlIdentifier.fromInternal(it)) }
            consistency?.let { request = request.setConsistencyLevel(it) }
            if (statements.size == 1) req.pageState?.ifEmpty { null }?.let { request = request.setPagingState(pageStateFrom(it)) }
            val rs = session.executeAsync(request).await()
            val elapsedMs = elapsedSince(started)
            val defs = rs.columnDefinitions.toList()
            results += StatementResult(
                statement = statement,
                columns = defs.map { ResultColumn(it.name.asInternal(), it.type.asCql(true, true)) },
                rows = rs.currentPage().map { row -> defs.indices.map { row.plainAt(it) } },
                pageState = pageStateOf(rs),
                elapsedMs = elapsedMs,
                warnings = rs.executionInfo.warnings,
                queriedHost = rs.executionInfo.coordinator?.endPoint?.toString()?.removePrefix("/"),
                trace = if (req.trace) readTrace(rs.executionInfo) else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results += StatementResult(statement = statement, error = errorMessage(e), elapsedMs = elapsedSince(started))
            if (!req.continueOnError) break
        }
    }
    return ExecuteResult(results, keyspace)
}

private suspend fun readTrace(info: ExecutionInfo): TraceInfo? {
    val traceId = info.tracingId ?: return null
    return try {
        val t = info.queryTraceAsync.await()
        TraceInfo(
            traceId = traceId.toString(),
            coordinator = t.coordinatorAddress.toString().removePrefix("/"),
            durationMicros = t.durationMicros,
            events = t.events.map {
                TraceEvent(
                    activity = it.activity.orEmpty(),
                    source = it.sourceAddress?.toString()?.removePrefix("/").orEmpty(),
                    elapsedMicros = it.sourceElapsedMicros,
                    thread = it.threadName.orEmpty(),
                )
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TraceInfo(traceId = traceId.toString(), error = errorMessage(e))
    }
}
