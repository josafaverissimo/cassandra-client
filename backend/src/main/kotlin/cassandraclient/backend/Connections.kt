package cassandraclient.backend

import cassandraclient.core.ApiException
import cassandraclient.core.AppJson
import cassandraclient.core.Connection
import cassandraclient.core.ConnectionInput
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.internal.core.loadbalancing.DcInferringLoadBalancingPolicy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** On-disk format of connections.json (the password is stored in plain text). */
@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val contactPoints: List<String>,
    val port: Int,
    val localDataCenter: String? = null,
    val username: String? = null,
    val password: String? = null,
    val ssl: Boolean = false,
    val color: String? = null,
)

private val DEFAULT_PROFILES = listOf(
    ConnectionProfile(id = "local", name = "Local", contactPoints = listOf("127.0.0.1"), port = 9042, color = "#3b82f6"),
)

private val FileJson = Json(AppJson) { prettyPrint = true }

fun defaultConfigPath(): Path =
    System.getenv("CASSANDRA_CLIENT_CONFIG")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".config", "cassandra-client", "connections.json")

class ConnectionManager(private val configPath: Path = defaultConfigPath()) {
    private val lock = Mutex()
    private var profiles: List<ConnectionProfile>? = null
    private val sessions = HashMap<String, Deferred<CqlSession>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun load(): List<ConnectionProfile> =
        profiles ?: (
            if (Files.exists(configPath)) {
                FileJson.decodeFromString<List<ConnectionProfile>>(Files.readString(configPath))
            } else {
                DEFAULT_PROFILES
            }
        ).also { profiles = it }

    private fun save(list: List<ConnectionProfile>) {
        profiles = list
        configPath.toAbsolutePath().parent?.let(Files::createDirectories)
        // Profiles may hold passwords: keep the file private to the current user.
        if (!Files.exists(configPath)) runCatching { Files.createFile(configPath, ownerOnly()) }
        Files.writeString(configPath, FileJson.encodeToString(list))
        runCatching { Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun ownerOnly() = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    private fun ConnectionProfile.toPublic() = Connection(
        id = id,
        name = name,
        contactPoints = contactPoints,
        port = port,
        localDataCenter = localDataCenter,
        username = username,
        ssl = ssl,
        color = color,
        hasPassword = !password.isNullOrEmpty(),
        connected = sessions[id]?.let { it.isCompleted && !it.isCancelled } == true,
    )

    suspend fun listProfiles(): List<Connection> = lock.withLock { load().map { it.toPublic() } }

    suspend fun getProfile(id: String): ConnectionProfile =
        lock.withLock { load().find { it.id == id } } ?: throw ApiException(404, "Connection $id not found")

    suspend fun upsertProfile(input: ConnectionInput): Connection {
        val existing = input.id?.let { id -> lock.withLock { load().find { it.id == id } } }
        if (existing != null) disconnect(existing.id)
        return lock.withLock {
            val list = load()
            if (existing != null) {
                // An omitted password keeps the stored one; an empty string clears it.
                val password = if (input.password == null) existing.password else input.password!!.ifEmpty { null }
                val updated = input.toProfile(existing.id, password)
                save(list.map { if (it.id == existing.id) updated else it })
                updated.toPublic()
            } else {
                val profile = input.toProfile(UUID.randomUUID().toString(), input.password?.ifEmpty { null })
                save(list + profile)
                profile.toPublic()
            }
        }
    }

    private fun ConnectionInput.toProfile(id: String, password: String?) = ConnectionProfile(
        id = id,
        name = name,
        contactPoints = contactPoints,
        port = port,
        localDataCenter = localDataCenter,
        username = username,
        password = password,
        ssl = ssl,
        color = color,
    )

    suspend fun deleteProfile(id: String) {
        disconnect(id)
        lock.withLock { save(load().filterNot { it.id == id }) }
    }

    /** Connects using the profile's DC, inferring it from the contact points when not configured. */
    suspend fun openSession(p: ConnectionProfile): CqlSession {
        val config = DriverConfigLoader.programmaticBuilder()
            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(8))
            .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(8))
            .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, Duration.ofSeconds(8))
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(60))
            .withClass(DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS, DcInferringLoadBalancingPolicy::class.java)
            // The UI reads system_schema itself, so the driver's own schema copy would be wasted work.
            .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, false)
            .withBoolean(DefaultDriverOption.METADATA_TOKEN_MAP_ENABLED, false)
            // Server warnings are returned to the UI; logging them too would only duplicate them.
            .withBoolean(DefaultDriverOption.REQUEST_LOG_WARNINGS, false)
            .build()
        val builder = CqlSession.builder()
            .withConfigLoader(config)
            .withApplicationName("cassandra-client")
            .addContactPoints(p.contactPoints.map { InetSocketAddress(it, p.port) })
        p.localDataCenter?.trim()?.ifEmpty { null }?.let(builder::withLocalDatacenter)
        p.username?.let { builder.withAuthCredentials(it, p.password ?: "") }
        if (p.ssl) builder.withSslContext(trustAllContext())
        return builder.buildAsync().await()
    }

    suspend fun getSession(id: String): CqlSession {
        val deferred = lock.withLock {
            sessions.getOrPut(id) {
                scope.async { openSession(getProfile(id)) }
            }
        }
        try {
            return deferred.await()
        } catch (e: Exception) {
            // Only a failed connection attempt is forgotten, not a cancelled caller.
            if (deferred.isCompleted) {
                lock.withLock { if (sessions[id] === deferred) sessions.remove(id) }
            }
            throw e
        }
    }

    suspend fun disconnect(id: String) {
        val deferred = lock.withLock { sessions.remove(id) } ?: return
        runCatching { deferred.await().closeAsync().await() }
    }

    suspend fun disconnectAll() {
        lock.withLock { sessions.keys.toList() }.forEach { disconnect(it) }
    }

    /** TLS without certificate validation, the same trade-off the connection profile has always made. */
    private fun trustAllContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
    }
}
