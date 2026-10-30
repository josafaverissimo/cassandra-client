package cassandraclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.Ddl
import cassandraclient.core.qualified
import cassandraclient.core.quoteId
import kotlinx.coroutines.launch

private enum class KeyspaceTabId { Tables, Types, Functions, Ddl }

@Composable
fun KeyspaceView(keyspace: String) {
    val ws = LocalWorkspace.current
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner()
    val ks = ws.keyspaces.find { it.name == keyspace }
    var tab by remember { mutableStateOf(KeyspaceTabId.Tables) }
    var alter by remember { mutableStateOf(false) }
    var createTable by remember { mutableStateOf(false) }
    val ddl = rememberLoader(ws.connId, keyspace, tab == KeyspaceTabId.Ddl, ws.keyspaces) {
        if (ks?.virtual == true) Ddl("-- virtual keyspace") else ws.api.keyspaceDdl(ws.connId, keyspace)
    }

    if (ks == null) {
        ErrorBox("Keyspace $keyspace no longer exists.")
        return
    }

    fun dropKeyspace() = scope.launch {
        val cql = "DROP KEYSPACE ${quoteId(keyspace)};"
        if (confirmer.confirm(ConfirmOptions("Drop keyspace $keyspace", "This permanently deletes every table and all data in this keyspace.", cql, "Drop keyspace", danger = true, typeToConfirm = keyspace))) {
            if (exec(cql, "Keyspace dropped")) ws.closeTab(keyspaceTabKey(keyspace))
        }
    }

    fun dropTable(table: String) = scope.launch {
        val cql = "DROP TABLE ${qualified(keyspace, table)};"
        if (confirmer.confirm(ConfirmOptions("Drop table $table", "The table and all its data will be deleted.", cql, "Drop table", danger = true, typeToConfirm = table))) {
            if (exec(cql, "Table dropped")) ws.closeTab(tableTabKey(keyspace, table))
        }
    }

    fun truncate(table: String) = scope.launch {
        val cql = "TRUNCATE ${qualified(keyspace, table)};"
        if (confirmer.confirm(ConfirmOptions("Truncate $table", "All rows will be removed from the table.", cql, "Truncate", danger = true, typeToConfirm = table))) {
            exec(cql, "Table truncated")
        }
    }

    fun dropType(name: String) = scope.launch {
        val cql = "DROP TYPE ${qualified(keyspace, name)};"
        if (confirmer.confirm(ConfirmOptions("Drop type $name", cql = cql, confirmLabel = "Drop type", danger = true))) exec(cql, "Type dropped")
    }

    fun dropView(name: String) = scope.launch {
        val cql = "DROP MATERIALIZED VIEW ${qualified(keyspace, name)};"
        if (confirmer.confirm(ConfirmOptions("Drop view $name", cql = cql, confirmLabel = "Drop view", danger = true))) exec(cql, "View dropped")
    }

    val replication = ks.replication.entries.joinToString(" · ") { (k, v) ->
        if (k == "class") v.removePrefix("org.apache.cassandra.locator.") else "$k: $v"
    }

    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(Icons.Database, colors.muted, size = 16.dp)
                    Heading(ks.name)
                    if (ks.virtual) Tag("virtual")
                    if (ks.system && !ks.virtual) Tag("system")
                }
                Muted(replication.ifEmpty { "—" } + if (!ks.virtual) " · durable writes ${if (ks.durableWrites) "on" else "off"}" else "")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Query", { ws.openTab(Tab.Query("Query $keyspace", keyspace = keyspace)) }, icon = Icons.Play)
                if (!ks.system) {
                    Btn("New table", { createTable = true }, kind = BtnKind.Primary, icon = Icons.Plus)
                    Btn("Replication…", { alter = true })
                    Btn("Drop", { dropKeyspace() }, kind = BtnKind.DangerGhost)
                }
            }
        }

        Tabs(
            listOf(
                KeyspaceTabId.Tables to "Tables (${ks.tables.size + ks.views.size})",
                KeyspaceTabId.Types to "Types (${ks.types.size})",
                KeyspaceTabId.Functions to "Functions (${ks.functions.size + ks.aggregates.size})",
                KeyspaceTabId.Ddl to "DDL",
            ),
            tab,
            { tab = it },
        )

        ScrollColumn(Modifier.fillMaxSize()) {
            when (tab) {
                KeyspaceTabId.Tables -> ListTable(listOf(ListColumn("Name", 2f), ListColumn("Kind", 1f), ListColumn("Comment", 2f), ListColumn("", 1.4f))) {
                    ks.tables.forEach { t ->
                        ListRow {
                            LinkText(t.name, { ws.openTab(Tab.Table(keyspace, t.name)) }, Modifier.weight(2f))
                            Muted("table", Modifier.weight(1f), small = false)
                            Muted(t.comment, Modifier.weight(2f), small = false, maxLines = 1)
                            Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                                if (!ks.system) {
                                    Btn("Truncate", { truncate(t.name) }, size = BtnSize.Xs)
                                    Btn("Drop", { dropTable(t.name) }, size = BtnSize.Xs, kind = BtnKind.DangerGhost)
                                }
                            }
                        }
                    }
                    ks.views.forEach { v ->
                        ListRow {
                            LinkText(v.name, { ws.openTab(Tab.Table(keyspace, v.name)) }, Modifier.weight(2f))
                            Muted("materialized view", Modifier.weight(1f), small = false, maxLines = 1)
                            Muted("on ${v.baseTable}", Modifier.weight(2f), small = false, maxLines = 1)
                            Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.End) {
                                Btn("Drop", { dropView(v.name) }, size = BtnSize.Xs, kind = BtnKind.DangerGhost)
                            }
                        }
                    }
                    if (ks.tables.isEmpty() && ks.views.isEmpty()) EmptyListRow("No tables yet.")
                }
                KeyspaceTabId.Types -> ListTable(listOf(ListColumn("Type", 1f), ListColumn("Fields", 3f), ListColumn("", 0.5f))) {
                    ks.types.forEach { t ->
                        ListRow {
                            Txt(t.name, Modifier.weight(1f), mono = true)
                            Txt(t.fields.joinToString(", ") { "${it.name} ${it.type}" }, Modifier.weight(3f), mono = true, size = 12.sp)
                            Row(Modifier.weight(0.5f), horizontalArrangement = Arrangement.End) {
                                if (!ks.system) Btn("Drop", { dropType(t.name) }, size = BtnSize.Xs, kind = BtnKind.DangerGhost)
                            }
                        }
                    }
                    if (ks.types.isEmpty()) EmptyListRow("No user-defined types. Create one with CREATE TYPE in the query editor.")
                }
                KeyspaceTabId.Functions -> ListTable(listOf(ListColumn("Name", 1f), ListColumn("Kind", 0.5f), ListColumn("Signature", 2f))) {
                    val all = ks.functions.map { it to "function" } + ks.aggregates.map { it to "aggregate" }
                    all.forEach { (f, kind) ->
                        ListRow {
                            Txt(f.name, Modifier.weight(1f), mono = true)
                            Muted(kind, Modifier.weight(0.5f), small = false)
                            Txt("(${f.argumentTypes.joinToString(", ")}) → ${f.returnType}", Modifier.weight(2f), mono = true, size = 12.sp)
                        }
                    }
                    if (all.isEmpty()) EmptyListRow("No user-defined functions or aggregates.")
                }
                KeyspaceTabId.Ddl -> when {
                    ddl.loading -> Spinner()
                    ddl.error != null -> ErrorBox(ddl.error!!, ddl::reload)
                    else -> CodeBlock(ddl.data?.ddl.orEmpty(), maxHeight = 2000.dp)
                }
            }
        }
    }

    if (alter) CreateKeyspaceDialog(existing = ks, onClose = { alter = false })
    if (createTable) CreateTableDialog(keyspace, onClose = { createTable = false })
}
