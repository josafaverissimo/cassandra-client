package cassandraclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cassandraclient.core.Column
import cassandraclient.core.Keyspace
import cassandraclient.core.TYPE_SUGGESTIONS
import cassandraclient.core.qualified
import cassandraclient.core.quoteId
import cassandraclient.core.quoteStr
import cassandraclient.core.run
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun typeSuggestions(keyspace: Keyspace?) = TYPE_SUGGESTIONS + keyspace?.types.orEmpty().map { "frozen<${it.name}>" }

/** Runs the generated CQL of a dialog. */
private class Runner(val busy: Boolean, val execute: (String, String) -> Unit)

@Composable
private fun rememberRunner(onDone: () -> Unit): Runner {
    val ws = LocalWorkspace.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    return Runner(busy) { cql, ok ->
        scope.launch {
            busy = true
            try {
                ws.api.run(ws.connId, cql)
                toaster.ok(ok, cql)
                ws.refreshSchema()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.error(e.text)
            } finally {
                busy = false
            }
        }
    }
}

/* ---------- Keyspace ---------- */

private const val SIMPLE = "SimpleStrategy"
private const val NETWORK = "NetworkTopologyStrategy"

fun replicationCql(strategy: String, rf: String, dcs: Map<String, String>): String {
    if (strategy == SIMPLE) return "{'class': '$SIMPLE', 'replication_factor': ${rf.toIntOrNull()?.takeIf { it != 0 } ?: 1}}"
    val parts = dcs.entries.filter { it.value.isNotBlank() }.map { (dc, v) -> "${quoteStr(dc)}: ${v.toIntOrNull() ?: 0}" }
    return "{'class': '$NETWORK'${if (parts.isNotEmpty()) ", " + parts.joinToString(", ") else ""}}"
}

@Composable
fun CreateKeyspaceDialog(onClose: () -> Unit, existing: Keyspace? = null) {
    val ws = LocalWorkspace.current
    val cls = existing?.replication?.get("class").orEmpty()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var strategy by remember { mutableStateOf(if (existing != null && cls.endsWith(SIMPLE)) SIMPLE else NETWORK) }
    var rf by remember { mutableStateOf(existing?.replication?.get("replication_factor") ?: "1") }
    var dcs by remember {
        mutableStateOf(
            ws.datacenters.associateWith { dc ->
                existing?.replication?.get(dc)
                    ?: if (existing != null) "" else minOf(3, ws.cluster.nodes.count { it.datacenter == dc }).toString()
            },
        )
    }
    var durable by remember { mutableStateOf(existing?.durableWrites ?: true) }
    val runner = rememberRunner(onClose)

    val options = "replication = ${replicationCql(strategy, rf, dcs)} AND durable_writes = $durable;"
    val cql = if (existing != null) {
        "ALTER KEYSPACE ${quoteId(existing.name)} WITH $options"
    } else {
        "CREATE KEYSPACE ${quoteId(name.ifEmpty { "my_keyspace" })} WITH $options"
    }

    Modal(
        title = existing?.let { "Alter keyspace ${it.name}" } ?: "New keyspace",
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn(
                if (existing != null) "Alter keyspace" else "Create keyspace",
                { runner.execute(cql, if (existing != null) "Keyspace altered" else "Keyspace created") },
                kind = BtnKind.Primary,
                enabled = !runner.busy && (existing != null || name.isNotBlank()),
            )
        },
    ) {
        if (existing == null) {
            Field("Name") { Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "my_keyspace", autoFocus = true) }
        }
        Field("Replication strategy") {
            Select(strategy, listOf(NETWORK, SIMPLE), { strategy = it }, Modifier.fillMaxWidth())
        }
        if (strategy == SIMPLE) {
            Field("Replication factor") { Input(rf, { rf = it }, Modifier.fillMaxWidth(), numeric = true) }
        } else {
            dcs.keys.forEach { dc ->
                Field("RF in $dc") {
                    Input(dcs.getValue(dc), { v -> dcs = dcs + (dc to v) }, Modifier.fillMaxWidth(), numeric = true)
                }
            }
        }
        Checkbox(durable, { durable = it }, label = "Durable writes")
        CodeBlock(cql)
    }
}

/* ---------- Table ---------- */

private enum class ColRole(val label: String) {
    Pk("Partition key"),
    Ck("Clustering"),
    Static("Static"),
    Regular("Regular"),
}

private data class ColDraft(val id: Int, val name: String, val type: String, val role: ColRole, val order: String = "ASC")

private var nextDraftId = 1
private fun draft(name: String, type: String, role: ColRole) = ColDraft(nextDraftId++, name, type, role)

private fun buildCreateTable(ks: String, name: String, cols: List<ColDraft>, comment: String, ttl: String, ifNotExists: Boolean, extra: String): String {
    val valid = cols.filter { it.name.isNotBlank() && it.type.isNotBlank() }
    val pk = valid.filter { it.role == ColRole.Pk }.map { quoteId(it.name.trim()) }
    val ck = valid.filter { it.role == ColRole.Ck }
    val lines = valid.map { "  ${quoteId(it.name.trim())} ${it.type.trim()}${if (it.role == ColRole.Static) " STATIC" else ""}" }.toMutableList()
    val pkExpr = if (pk.size > 1) "(${pk.joinToString(", ")})" else pk.joinToString("")
    lines += "  PRIMARY KEY (${(listOf(pkExpr) + ck.map { quoteId(it.name.trim()) }).filter { it.isNotEmpty() }.joinToString(", ")})"
    val withs = mutableListOf<String>()
    if (ck.isNotEmpty()) withs += "CLUSTERING ORDER BY (${ck.joinToString(", ") { "${quoteId(it.name.trim())} ${it.order}" }})"
    if (comment.isNotEmpty()) withs += "comment = ${quoteStr(comment)}"
    ttl.toIntOrNull()?.takeIf { it > 0 }?.let { withs += "default_time_to_live = $it" }
    if (extra.isNotBlank()) withs += extra.trim()
    return "CREATE TABLE ${if (ifNotExists) "IF NOT EXISTS " else ""}${qualified(ks, name.ifEmpty { "my_table" })} (\n${lines.joinToString(",\n")}\n)" +
        (if (withs.isNotEmpty()) " WITH ${withs.joinToString("\n  AND ")}" else "") + ";"
}

@Composable
fun CreateTableDialog(keyspace: String, onClose: () -> Unit) {
    val ws = LocalWorkspace.current
    val ks = ws.keyspaces.find { it.name == keyspace }
    var name by remember { mutableStateOf("") }
    var cols by remember { mutableStateOf(listOf(draft("id", "uuid", ColRole.Pk), draft("created_at", "timestamp", ColRole.Regular))) }
    var comment by remember { mutableStateOf("") }
    var ttl by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var ifNotExists by remember { mutableStateOf(false) }
    val runner = rememberRunner {
        onClose()
        if (name.isNotEmpty()) ws.openTab(Tab.Table(keyspace, name))
    }

    fun update(next: ColDraft) {
        cols = cols.map { if (it.id == next.id) next else it }
    }

    fun move(id: Int, dir: Int) {
        val i = cols.indexOfFirst { it.id == id }
        val j = i + dir
        if (j !in cols.indices) return
        cols = cols.toMutableList().also {
            val tmp = it[i]
            it[i] = it[j]
            it[j] = tmp
        }
    }

    val cql = buildCreateTable(keyspace, name, cols, comment, ttl, ifNotExists, extra)
    val hasPk = cols.any { it.role == ColRole.Pk && it.name.isNotBlank() }
    val suggestions = remember(ks) { typeSuggestions(ks) }

    Modal(
        title = "New table in $keyspace",
        wide = true,
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn("Create table", { runner.execute(cql, "Table created") }, kind = BtnKind.Primary, enabled = !runner.busy && name.isNotBlank() && hasPk)
        },
    ) {
        Field("Table name") { Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "my_table", autoFocus = true) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted("Column", Modifier.weight(1f))
            Muted("Type", Modifier.weight(1f))
            Muted("Key", Modifier.width(140.dp))
            Muted("Order", Modifier.width(80.dp))
            HSpacer(74.dp)
        }
        cols.forEach { c ->
            key(c.id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Input(c.name, { update(c.copy(name = it)) }, Modifier.weight(1f), placeholder = "column_name")
                    SuggestInput(c.type, { update(c.copy(type = it)) }, suggestions, Modifier.weight(1f), placeholder = "text")
                    Select(c.role, ColRole.entries, { update(c.copy(role = it)) }, Modifier.width(140.dp), label = { it.label })
                    Row(Modifier.width(80.dp)) {
                        if (c.role == ColRole.Ck) Select(c.order, listOf("ASC", "DESC"), { update(c.copy(order = it)) }, Modifier.fillMaxWidth())
                    }
                    IconBtn(Icons.Up, { move(c.id, -1) })
                    IconBtn(Icons.Down, { move(c.id, 1) })
                    IconBtn(Icons.Close, { cols = cols.filterNot { it.id == c.id } }, tint = colors.danger)
                }
            }
        }
        Btn("Add column", { cols = cols + draft("", "text", ColRole.Regular) }, size = BtnSize.Sm, icon = Icons.Plus)
        Muted("Key column order matters: partition keys and clustering columns are used in the order listed.")
        FormRow {
            Field("Comment", Modifier.weight(1f)) { Input(comment, { comment = it }, Modifier.fillMaxWidth()) }
            Field("Default TTL (seconds)", Modifier.weight(1f)) { Input(ttl, { ttl = it }, Modifier.fillMaxWidth(), numeric = true, placeholder = "0") }
        }
        Field("Extra options") {
            Input(extra, { extra = it }, Modifier.fillMaxWidth(), placeholder = "compaction = {'class': 'UnifiedCompactionStrategy'}")
        }
        Checkbox(ifNotExists, { ifNotExists = it }, label = "IF NOT EXISTS")
        CodeBlock(cql)
    }
}

/* ---------- Columns & indexes ---------- */

@Composable
fun AddColumnDialog(keyspace: String, table: String, onClose: () -> Unit, onDone: () -> Unit) {
    val ws = LocalWorkspace.current
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("text") }
    var isStatic by remember { mutableStateOf(false) }
    val runner = rememberRunner {
        onDone()
        onClose()
    }
    val cql = "ALTER TABLE ${qualified(keyspace, table)} ADD ${quoteId(name.ifEmpty { "new_column" })} $type${if (isStatic) " STATIC" else ""};"
    val suggestions = remember(ws.keyspaces) { typeSuggestions(ws.keyspaces.find { it.name == keyspace }) }
    Modal(
        title = "Add column",
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn("Add column", { runner.execute(cql, "Column added") }, kind = BtnKind.Primary, enabled = !runner.busy && name.isNotBlank() && type.isNotBlank())
        },
    ) {
        FormRow {
            Field("Name", Modifier.weight(1f)) { Input(name, { name = it }, Modifier.fillMaxWidth(), autoFocus = true) }
            Field("Type", Modifier.weight(1f)) { SuggestInput(type, { type = it }, suggestions, Modifier.fillMaxWidth()) }
        }
        Checkbox(isStatic, { isStatic = it }, label = "Static column")
        CodeBlock(cql)
    }
}

private val INDEX_IMPLEMENTATIONS = listOf("sai" to "Storage-attached (SAI)", "legacy" to "Legacy secondary index", "sasi" to "SASI")

@Composable
fun CreateIndexDialog(keyspace: String, table: String, columns: List<Column>, onClose: () -> Unit, onDone: () -> Unit) {
    var column by remember { mutableStateOf(columns.find { !it.isKey && it.kind == "regular" }?.name ?: columns.firstOrNull()?.name.orEmpty()) }
    var target by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var using by remember { mutableStateOf("sai") }
    val runner = rememberRunner {
        onDone()
        onClose()
    }
    val col = columns.find { it.name == column }
    val isMap = col != null && (col.type.startsWith("map<") || col.type.startsWith("frozen<map<"))
    val isCollection = col != null && (col.type.startsWith("list<") || col.type.startsWith("set<") || col.type.startsWith("map<"))
    val targetExpr = if (target.isNotEmpty()) "$target(${quoteId(column)})" else quoteId(column)
    val cql = "CREATE INDEX ${name.trim().takeIf { it.isNotEmpty() }?.let { quoteId(it) + " " }.orEmpty()}ON ${qualified(keyspace, table)} ($targetExpr)" +
        when (using) {
            "sai" -> " USING 'sai'"
            "sasi" -> " USING 'org.apache.cassandra.index.sasi.SASIIndex'"
            else -> ""
        } + ";"
    Modal(
        title = "Create index",
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn("Create index", { runner.execute(cql, "Index created") }, kind = BtnKind.Primary, enabled = !runner.busy && column.isNotEmpty())
        },
    ) {
        FormRow {
            Field("Column", Modifier.weight(1f)) {
                Select(
                    column,
                    columns.map { it.name },
                    {
                        column = it
                        target = ""
                    },
                    Modifier.fillMaxWidth(),
                    label = { n -> "$n (${columns.first { it.name == n }.type})" },
                )
            }
            Field("Implementation", Modifier.weight(1f)) {
                Select(using, INDEX_IMPLEMENTATIONS.map { it.first }, { using = it }, Modifier.fillMaxWidth(), label = { id -> INDEX_IMPLEMENTATIONS.first { it.first == id }.second })
            }
        }
        FormRow {
            if (isCollection) {
                Field("Index target", Modifier.weight(1f)) {
                    val targets = buildList {
                        add("")
                        if (isMap) {
                            add("KEYS")
                            add("ENTRIES")
                        }
                        add("FULL")
                    }
                    Select(target, targets, { target = it }, Modifier.fillMaxWidth(), label = {
                        when (it) {
                            "" -> if (isMap) "VALUES (default)" else "VALUES"
                            "FULL" -> "FULL (frozen only)"
                            else -> it
                        }
                    })
                }
            }
            Field("Index name (optional)", Modifier.weight(1f)) {
                Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "${table}_${column}_idx")
            }
        }
        CodeBlock(cql)
    }
}
