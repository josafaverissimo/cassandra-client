package cassandraclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import cassandraclient.core.AppJson
import cassandraclient.core.ColumnKind
import cassandraclient.core.TableInfo
import cassandraclient.core.TableKind
import cassandraclient.core.qualified
import cassandraclient.core.quoteId
import cassandraclient.core.quoteStr
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private enum class Sub { Data, Structure, Indexes, Options, Ddl }

@Composable
fun TableView(keyspace: String, table: String) {
    val ws = LocalWorkspace.current
    var sub by remember { mutableStateOf(Sub.Data) }
    val loader = rememberLoader(ws.connId, keyspace, table) { ws.api.table(ws.connId, keyspace, table) }
    val info = loader.data

    if (loader.loading && info == null) {
        Spinner("Loading table…")
        return
    }
    loader.error?.let {
        ErrorBox(it, loader::reload, Modifier.padding(16.dp))
        return
    }
    if (info == null) return

    val pk = info.columns.filter { it.kind == ColumnKind.PARTITION_KEY }.map { it.name }
    val ck = info.columns.filter { it.kind == ColumnKind.CLUSTERING }.map { it.name }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(if (info.kind == TableKind.VIEW) Icons.View else Icons.Table, colors.muted, size = 16.dp)
                    Row {
                        Txt("$keyspace.", color = colors.muted, size = 17.sp, bold = true)
                        Heading(table)
                    }
                    if (info.kind != TableKind.TABLE) Tag(if (info.kind == TableKind.VIEW) "materialized view" else "virtual")
                    if (info.isCounter) Tag("counter")
                }
                Muted("PRIMARY KEY ((${pk.joinToString(", ")})${if (ck.isNotEmpty()) ", ${ck.joinToString(", ")}" else ""})", mono = true, maxLines = 1)
            }
            Btn("Query", {
                ws.openTab(Tab.Query("Query $table", "SELECT * FROM ${qualified(keyspace, table)} LIMIT 100;", keyspace))
            }, icon = Icons.Play)
        }
        Tabs(
            listOf(
                Sub.Data to "Data",
                Sub.Structure to "Structure (${info.columns.size})",
                Sub.Indexes to "Indexes (${info.indexes.size})",
                Sub.Options to "Options",
                Sub.Ddl to "DDL",
            ),
            sub,
            { sub = it },
        )
        KeepAlivePanels(Sub.entries, { it }, { it == sub }, Modifier.weight(1f).fillMaxWidth()) { panel ->
            when (panel) {
                Sub.Data -> DataGrid(info)
                Sub.Structure -> SubPage { Structure(info, loader::reload) }
                Sub.Indexes -> SubPage { Indexes(info, loader::reload) }
                Sub.Options -> SubPage { Options(info, loader::reload) }
                Sub.Ddl -> SubPage { CodeBlock(info.ddl, maxHeight = 2000.dp) }
            }
        }
    }
}

@Composable
private fun SubPage(content: @Composable () -> Unit) {
    ScrollColumn(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun Structure(info: TableInfo, onChanged: () -> Unit) {
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner(onChanged = onChanged)
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val editable = info.kind == TableKind.TABLE

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (editable) Btn("Add column", { adding = true }, kind = BtnKind.Primary, size = BtnSize.Sm, icon = Icons.Plus)
        ListTable(listOf(ListColumn("Column", 2f), ListColumn("Type", 2f), ListColumn("Kind", 1f), ListColumn("Clustering order", 1f), ListColumn("", 0.8f))) {
            info.columns.forEach { c ->
                ListRow {
                    Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt(c.name, mono = true, maxLines = 1)
                        KindBadge(c.kind)
                    }
                    Txt(c.type, Modifier.weight(2f), mono = true, maxLines = 1)
                    Muted(c.kind.replace("_", " "), Modifier.weight(1f), small = false)
                    Muted(if (c.kind == ColumnKind.CLUSTERING) c.clusteringOrder.uppercase() else "", Modifier.weight(1f), small = false)
                    Row(Modifier.weight(0.8f), horizontalArrangement = Arrangement.End) {
                        if (editable && c.isKey) Btn("Rename", { renaming = c.name }, size = BtnSize.Xs)
                        if (editable && !c.isKey) {
                            Btn("Drop", {
                                scope.launch {
                                    val cql = "ALTER TABLE ${qualified(info.keyspace, info.name)} DROP ${quoteId(c.name)};"
                                    if (confirmer.confirm(ConfirmOptions("Drop column ${c.name}", "The column and its data will be removed.", cql, "Drop column", danger = true))) {
                                        exec(cql, "Column dropped")
                                    }
                                }
                            }, size = BtnSize.Xs, kind = BtnKind.DangerGhost)
                        }
                    }
                }
            }
        }
    }
    if (adding) AddColumnDialog(info.keyspace, info.name, onClose = { adding = false }, onDone = onChanged)
    renaming?.let { from ->
        RenameColumn(from, onClose = { renaming = null }) { to ->
            scope.launch {
                val cql = "ALTER TABLE ${qualified(info.keyspace, info.name)} RENAME ${quoteId(from)} TO ${quoteId(to)};"
                if (exec(cql, "Column renamed")) renaming = null
            }
        }
    }
}

@Composable
private fun RenameColumn(from: String, onClose: () -> Unit, onRename: (String) -> Unit) {
    var to by remember { mutableStateOf(from) }
    val valid = to.isNotBlank() && to != from
    Modal(
        title = "Rename $from",
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn("Rename", { onRename(to.trim()) }, kind = BtnKind.Primary, enabled = valid)
        },
    ) {
        Field("New name") {
            Input(to, { to = it }, Modifier.fillMaxWidth(), autoFocus = true, onEnter = { if (valid) onRename(to.trim()) })
        }
    }
}

@Composable
private fun Indexes(info: TableInfo, onChanged: () -> Unit) {
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner(onChanged = onChanged)
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (info.kind == TableKind.TABLE) Btn("Create index", { creating = true }, kind = BtnKind.Primary, size = BtnSize.Sm, icon = Icons.Plus)
        ListTable(listOf(ListColumn("Name", 2f), ListColumn("Target", 2f), ListColumn("Kind", 1f), ListColumn("Class", 1.4f), ListColumn("", 0.6f))) {
            info.indexes.forEach { ix ->
                ListRow {
                    Txt(ix.name, Modifier.weight(2f), mono = true, maxLines = 1)
                    Txt(ix.options["target"].orEmpty(), Modifier.weight(2f), mono = true, maxLines = 1)
                    Muted(ix.kind, Modifier.weight(1f), small = false)
                    Muted(ix.options["class_name"]?.substringAfterLast('.') ?: "legacy", Modifier.weight(1.4f))
                    Row(Modifier.weight(0.6f), horizontalArrangement = Arrangement.End) {
                        Btn("Drop", {
                            scope.launch {
                                val cql = "DROP INDEX ${qualified(info.keyspace, ix.name)};"
                                if (confirmer.confirm(ConfirmOptions("Drop index ${ix.name}", cql = cql, confirmLabel = "Drop index", danger = true))) exec(cql, "Index dropped")
                            }
                        }, size = BtnSize.Xs, kind = BtnKind.DangerGhost)
                    }
                }
            }
            if (info.indexes.isEmpty()) EmptyListRow("No secondary indexes.")
        }
        if (info.views.isNotEmpty()) Muted("Materialized views on this table: ${info.views.joinToString(", ")}")
    }
    if (creating) CreateIndexDialog(info.keyspace, info.name, info.columns, onClose = { creating = false }, onDone = onChanged)
}

private enum class OptionKind { Text, Number, Map }

private val EDITABLE_OPTIONS = mapOf(
    "comment" to OptionKind.Text,
    "default_time_to_live" to OptionKind.Number,
    "gc_grace_seconds" to OptionKind.Number,
    "bloom_filter_fp_chance" to OptionKind.Number,
    "crc_check_chance" to OptionKind.Number,
    "memtable_flush_period_in_ms" to OptionKind.Number,
    "min_index_interval" to OptionKind.Number,
    "max_index_interval" to OptionKind.Number,
    "speculative_retry" to OptionKind.Text,
    "additional_write_policy" to OptionKind.Text,
    "read_repair" to OptionKind.Text,
    "caching" to OptionKind.Map,
    "compaction" to OptionKind.Map,
    "compression" to OptionKind.Map,
)

private fun optionLiteral(kind: OptionKind, value: String): String = when (kind) {
    OptionKind.Number -> value.trim()
    OptionKind.Text -> quoteStr(value)
    OptionKind.Map -> {
        val obj = AppJson.parseToJsonElement(value).jsonObject
        "{" + obj.entries.joinToString(", ") { (k, v) -> "${quoteStr(k)}: ${quoteStr(jsonOptionText(v))}" } + "}"
    }
}

private fun jsonOptionText(v: JsonElement) = if (v is JsonPrimitive) v.content else v.toString()

private fun optionToText(v: JsonElement): String = when (v) {
    is JsonNull -> ""
    is JsonObject -> v.toString()
    is JsonPrimitive -> v.content
    else -> v.toString()
}

@Composable
private fun Options(info: TableInfo, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner(onChanged = onChanged)
    val initial = remember(info) { info.options.mapValues { optionToText(it.value) } }
    var values by remember(info) { mutableStateOf(initial) }
    val changed = values.keys.filter { values[it] != initial[it] && it in EDITABLE_OPTIONS }
    var cql = ""
    var err: String? = null
    try {
        if (changed.isNotEmpty()) {
            val target = if (info.kind == TableKind.VIEW) "MATERIALIZED VIEW" else "TABLE"
            cql = "ALTER $target ${qualified(info.keyspace, info.name)} WITH " +
                changed.joinToString("\n  AND ") { "$it = ${optionLiteral(EDITABLE_OPTIONS.getValue(it), values.getValue(it))}" } + ";"
        }
    } catch (e: Exception) {
        err = "Invalid JSON: ${e.text}"
    }
    val editable = info.kind != TableKind.VIRTUAL

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ListTable(listOf(ListColumn("", 1f))) {
            info.options.keys.sorted().forEach { k ->
                ListRow {
                    Txt(k, Modifier.width(240.dp), mono = true, size = 12.sp, maxLines = 1)
                    if (editable && k in EDITABLE_OPTIONS) {
                        Input(
                            values[k].orEmpty(),
                            { v -> values = values + (k to v) },
                            Modifier.weight(1f),
                            mono = true,
                            small = true,
                            highlighted = values[k] != initial[k],
                        )
                    } else {
                        Muted(initial[k].orEmpty(), Modifier.weight(1f), mono = true)
                    }
                }
            }
        }
        err?.let { TestResult(false, it) }
        if (cql.isNotEmpty() && err == null) {
            CodeBlock(cql)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Reset", { values = initial }, size = BtnSize.Sm)
                Btn("Apply changes", { scope.launch { exec(cql, "Table options updated") } }, size = BtnSize.Sm, kind = BtnKind.Primary)
            }
        }
    }
}
