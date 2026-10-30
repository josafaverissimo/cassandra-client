package cassandraclient.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import cassandraclient.core.CassandraApi
import cassandraclient.core.ClusterInfo
import cassandraclient.core.Connection
import cassandraclient.core.Keyspace
import cassandraclient.core.run
import kotlinx.coroutines.CancellationException

sealed class Tab {
    abstract val key: String
    abstract val title: String

    data class Table(val keyspace: String, val table: String) : Tab() {
        override val key get() = "table:$keyspace.$table"
        override val title get() = "$keyspace.$table"
    }

    data class KeyspaceTab(val keyspace: String) : Tab() {
        override val key get() = "keyspace:$keyspace"
        override val title get() = keyspace
    }

    data class Query(override val title: String, val initialCql: String? = null, val keyspace: String? = null, override val key: String = nextQueryKey()) : Tab()

    data object Roles : Tab() {
        override val key get() = "roles"
        override val title get() = "Roles & permissions"
    }

    data object Cluster : Tab() {
        override val key get() = "cluster"
        override val title get() = "Cluster"
    }
}

private var querySeq = 0
private fun nextQueryKey() = "query:${++querySeq}"

fun tableTabKey(keyspace: String, table: String) = Tab.Table(keyspace, table).key
fun keyspaceTabKey(keyspace: String) = Tab.KeyspaceTab(keyspace).key

@Stable
class Workspace(
    val api: CassandraApi,
    conn: Connection,
    val cluster: ClusterInfo,
    keyspaces: List<Keyspace>,
    val refreshSchema: suspend () -> Unit,
    val openTab: (Tab) -> Unit,
    val closeTab: (String) -> Unit,
) {
    var conn by mutableStateOf(conn)
    var keyspaces by mutableStateOf(keyspaces)
    val connId get() = conn.id
    val datacenters get() = cluster.nodes.map { it.datacenter }.distinct()
}

val LocalWorkspace = staticCompositionLocalOf<Workspace> { error("Workspace outside of a connection") }

val LocalApi = staticCompositionLocalOf<CassandraApi> { error("No API") }

/** Runs generated CQL with the usual toast feedback; returns whether it succeeded. */
class CqlRunner(private val ws: Workspace, private val toaster: Toaster, private val maskPasswords: Boolean, private val refreshSchema: Boolean, private val onChanged: (() -> Unit)?) {
    suspend operator fun invoke(cql: String, ok: String): Boolean =
        try {
            ws.api.run(ws.connId, cql)
            toaster.ok(ok, if (maskPasswords) maskPassword(cql) else cql)
            onChanged?.invoke()
            if (refreshSchema) ws.refreshSchema()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toaster.error(e.text)
            false
        }
}

@Composable
fun rememberCqlRunner(maskPasswords: Boolean = false, refreshSchema: Boolean = true, onChanged: (() -> Unit)? = null): CqlRunner {
    val ws = LocalWorkspace.current
    val toaster = LocalToaster.current
    return remember(ws, onChanged) { CqlRunner(ws, toaster, maskPasswords, refreshSchema, onChanged) }
}

private val PASSWORD_LITERAL = Regex("PASSWORD = '[^']*(?:''[^']*)*'")

fun maskPassword(cql: String) = PASSWORD_LITERAL.replace(cql, "PASSWORD = '•••'")
