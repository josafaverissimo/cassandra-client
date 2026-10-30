package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.Column as CqlColumn
import cassandraclient.core.CountResult
import cassandraclient.core.DeleteRows
import cassandraclient.core.Filter
import cassandraclient.core.IncrementCounter
import cassandraclient.core.InsertRow
import cassandraclient.core.Row as CqlRow
import cassandraclient.core.RowsQuery
import cassandraclient.core.RowsResult
import cassandraclient.core.TableInfo
import cassandraclient.core.TableKind
import cassandraclient.core.UpdateRow
import cassandraclient.core.ValueKind
import cassandraclient.core.displayValue
import cassandraclient.core.filterValueType
import cassandraclient.core.fromEditable
import cassandraclient.core.isNull
import cassandraclient.core.prettyJson
import cassandraclient.core.toEditable
import cassandraclient.core.valueKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private class FilterDraft(val id: Int, val column: String, val op: String = "=", val text: String = "")

private val OPS = listOf("=", "<", "<=", ">", ">=", "IN", "CONTAINS", "CONTAINS KEY", "LIKE")
private val PAGE_SIZES = listOf(25, 50, 100, 250, 500, 1000)

private data class CellRef(val row: Int, val isNew: Boolean, val col: String)

private typealias Edits = Map<Int, Map<String, String?>>

private var filterSeq = 1

val GridRowHeight = 30.dp
private val RowNumWidth = 72.dp

/** Picks a column width from its header and the widest value on screen. */
fun gridColumnWidth(name: String, type: String, longestValue: Int): Dp =
    (maxOf(name.length + 4, type.length, longestValue).coerceIn(8, 48) * 7.4f + 22f).dp

@Composable
fun DataGrid(info: TableInfo) {
    val ws = LocalWorkspace.current
    val toaster = LocalToaster.current
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()
    val conn = ws.connId
    val columns = info.columns
    val keyCols = remember(columns) { columns.filter { it.isKey } }
    val readOnly = info.kind != TableKind.TABLE
    fun colType(name: String) = columns.find { it.name == name }?.type ?: "text"

    var filters by remember { mutableStateOf(listOf<FilterDraft>()) }
    var applied by remember { mutableStateOf(listOf<Filter>()) }
    var allowFiltering by remember { mutableStateOf(false) }
    var pageSize by remember { mutableIntStateOf(Storage.get("cc.pageSize")?.toIntOrNull() ?: 100) }
    var pageStates by remember { mutableStateOf(listOf<String?>(null)) }
    var page by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<RowsResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var edits by remember { mutableStateOf<Edits>(emptyMap()) }
    var newRows by remember { mutableStateOf(listOf<CqlRow>()) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var editing by remember { mutableStateOf<CellRef?>(null) }
    var bigEditor by remember { mutableStateOf<CellRef?>(null) }
    var viewing by remember { mutableStateOf<CellRef?>(null) }
    var count by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showCql by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var colMenu by remember { mutableStateOf(false) }
    var colMenuAnchor by remember { mutableIntStateOf(0) }

    val dirtyCount = edits.values.sumOf { it.size } + newRows.size
    val rows = result?.rows.orEmpty()
    val visibleCols = columns.filter { it.name !in hidden }

    suspend fun load(pageIndex: Int, states: List<String?>, withFilters: List<Filter>, allow: Boolean, size: Int) {
        loading = true
        error = null
        try {
            val r = ws.api.queryRows(
                conn,
                RowsQuery(info.keyspace, info.name, withFilters, allow, size, states.getOrNull(pageIndex)),
            )
            result = r
            page = pageIndex
            pageStates = states.take(pageIndex + 1) + r.pageState
            edits = emptyMap()
            selected = emptySet()
            editing = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.text
        } finally {
            loading = false
        }
    }

    suspend fun reload(pageIndex: Int = 0, withFilters: List<Filter> = applied, allow: Boolean = allowFiltering, size: Int = pageSize) {
        load(pageIndex, if (pageIndex == 0) listOf(null) else pageStates, withFilters, allow, size)
    }

    LaunchedEffect(info) { reload(0) }

    suspend fun guardDirty() = dirtyCount == 0 ||
        confirmer.confirm(ConfirmOptions("Discard changes?", "You have $dirtyCount unsaved change(s).", confirmLabel = "Discard", danger = true))

    fun applyFilters() = scope.launch {
        try {
            val built = filters
                .filter { it.column.isNotEmpty() && (it.text.isNotEmpty() || valueKind(colType(it.column)) == ValueKind.STRING) }
                .map { buildFilter(it, columns) }
            if (!guardDirty()) return@launch
            newRows = emptyList()
            applied = built
            count = null
            reload(0, withFilters = built)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toaster.error(e.text)
        }
    }

    fun cellValue(ref: CellRef): String? = when {
        ref.isNew -> newRows.getOrNull(ref.row)?.get(ref.col)
        edits[ref.row]?.containsKey(ref.col) == true -> edits.getValue(ref.row)[ref.col]
        else -> rows.getOrNull(ref.row)?.get(ref.col)
    }

    fun setCell(ref: CellRef, json: String?) {
        if (ref.isNew) {
            newRows = newRows.mapIndexed { i, r -> if (i == ref.row) r + (ref.col to json) else r }
            return
        }
        val original = rows.getOrNull(ref.row)?.get(ref.col)
        val rowEdits = (edits[ref.row] ?: emptyMap()).toMutableMap()
        if ((isNull(original) && isNull(json)) || original == json) rowEdits.remove(ref.col) else rowEdits[ref.col] = json
        edits = if (rowEdits.isEmpty()) edits - ref.row else edits + (ref.row to rowEdits)
    }

    fun canEdit(ref: CellRef): Boolean {
        if (readOnly) return false
        return ref.isNew || !columns.first { it.name == ref.col }.isKey
    }

    fun startEdit(ref: CellRef, forceBig: Boolean = false) {
        if (!canEdit(ref)) {
            viewing = ref
            return
        }
        val type = colType(ref.col)
        val text = toEditable(cellValue(ref), type)
        if (forceBig || valueKind(type) == ValueKind.JSON || text.length > 80 || text.contains('\n')) bigEditor = ref else editing = ref
    }

    fun keyOf(row: CqlRow) = keyCols.associate { it.name to (row[it.name] ?: "null") }

    fun save() = scope.launch {
        saving = true
        val executed = mutableListOf<String>()
        var failed = 0
        try {
            for ((idx, changes) in edits) {
                val row = rows[idx]
                try {
                    val (counters, regular) = changes.entries.partition { colType(it.key) == "counter" }
                    if (regular.isNotEmpty()) {
                        executed += ws.api.updateRow(conn, UpdateRow(info.keyspace, info.name, keyOf(row), regular.associate { it.key to it.value })).cql
                    }
                    for ((column, value) in counters.map { it.key to it.value }) {
                        val current = row[column].takeUnless { isNull(it) } ?: "0"
                        val delta = (value ?: "0").toLong() - current.toLong()
                        executed += ws.api.incrementCounter(conn, IncrementCounter(info.keyspace, info.name, keyOf(row), column, delta.toString())).cql
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    toaster.error("Row ${idx + 1}: ${e.text}")
                }
            }
            val remaining = mutableListOf<CqlRow>()
            for (r in newRows) {
                try {
                    if (info.isCounter) {
                        for (c in columns.filter { it.type == "counter" && !isNull(r[it.name]) }) {
                            executed += ws.api.incrementCounter(conn, IncrementCounter(info.keyspace, info.name, keyOf(r), c.name, r.getValue(c.name)!!)).cql
                        }
                    } else {
                        executed += ws.api.insertRow(conn, InsertRow(info.keyspace, info.name, r)).cql
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    remaining += r
                    toaster.error("New row: ${e.text}")
                }
            }
            if (executed.isNotEmpty()) toaster.ok("${executed.size} statement(s) applied", executed.joinToString(";\n"))
            newRows = remaining
            if (failed == 0) reload(page)
        } finally {
            saving = false
        }
    }

    fun discard() {
        edits = emptyMap()
        newRows = emptyList()
        editing = null
    }

    fun deleteSelected() = scope.launch {
        val keys = selected.map { keyOf(rows[it]) }
        if (keys.isEmpty()) return@launch
        val preview = keys.take(5).joinToString("\n") { k ->
            "DELETE FROM ${info.keyspace}.${info.name} WHERE ${k.entries.joinToString(" AND ") { "${it.key} = ${it.value}" }};"
        }
        val more = if (keys.size > 5) "\n-- … and ${keys.size - 5} more" else ""
        if (!confirmer.confirm(ConfirmOptions("Delete ${keys.size} row(s)?", cql = preview + more, confirmLabel = "Delete", danger = true))) return@launch
        try {
            val r = ws.api.deleteRows(conn, DeleteRows(info.keyspace, info.name, keys))
            toaster.ok("${keys.size} row(s) deleted", r.cql)
            reload(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toaster.error(e.text)
        }
    }

    fun runCount() = scope.launch {
        count = "…"
        try {
            val r: CountResult = ws.api.countRows(conn, RowsQuery(info.keyspace, info.name, applied, allowFiltering))
            count = r.count
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            count = null
            toaster.error(e.text)
        }
    }

    fun exportData(format: String) {
        val all = rows.mapIndexed { i, r -> r + (edits[i] ?: emptyMap()) }
        val text = if (format == "json") {
            // Compose raw JSON text so bigint/decimal values keep full precision.
            "[\n" + all.joinToString(",\n") { r ->
                "  {" + visibleCols.joinToString(", ") { c -> "${JsonPrimitive(c.name)}: ${r[c.name] ?: "null"}" } + "}"
            } + "\n]"
        } else {
            val header = visibleCols.joinToString(",") { csvEscape(it.name) }
            (listOf(header) + all.map { r ->
                visibleCols.joinToString(",") { c -> if (isNull(r[c.name])) "" else csvEscape(toEditable(r[c.name], c.type)) }
            }).joinToString("\n")
        }
        saveTextFile("${info.keyspace}.${info.name}.page${page + 1}.$format", if (format == "json") "application/json" else "text/csv", text)
    }

    val allSelected = rows.isNotEmpty() && selected.size == rows.size
    val needsAllowFiltering = error?.contains("ALLOW FILTERING", ignoreCase = true) == true && !allowFiltering

    val widths = remember(columns, rows) {
        columns.associate { c ->
            val longest = rows.take(200).maxOfOrNull { displayValue(it[c.name], c.type).length } ?: 0
            c.name to gridColumnWidth(c.name, c.type, longest)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Toolbar
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("", { scope.launch { if (guardDirty()) { discard(); reload(page) } } }, size = BtnSize.Sm, icon = Icons.Refresh)
            Btn(
                "Filters${if (applied.isNotEmpty()) " (${applied.size})" else ""}",
                {
                    if (filters.isEmpty()) {
                        filters = listOf(FilterDraft(filterSeq++, keyCols.firstOrNull()?.name ?: columns.first().name))
                    }
                },
                size = BtnSize.Sm,
                kind = if (filters.isNotEmpty()) BtnKind.Active else BtnKind.Default,
                icon = Icons.Filter,
            )
            Box(Modifier.onSizeChanged { colMenuAnchor = it.height }) {
                Btn("Columns${if (hidden.isNotEmpty()) " (${columns.size - hidden.size}/${columns.size})" else ""}", { colMenu = !colMenu }, size = BtnSize.Sm, icon = Icons.Columns)
                if (colMenu) {
                    Dropdown(colMenuAnchor, { colMenu = false }) {
                        MenuSurface(Modifier.widthIn(min = 180.dp).heightIn(max = 340.dp)) {
                            columns.forEach { c ->
                                Checkbox(
                                    c.name !in hidden,
                                    { show -> hidden = if (show) hidden - c.name else hidden + c.name },
                                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    label = c.name,
                                    small = true,
                                )
                            }
                        }
                    }
                }
            }
            if (!readOnly) {
                Box(Modifier.width(1.dp).height(18.dp).background(colors.border))
                Btn("Add row", { newRows = listOf(columns.associate { it.name to null as String? }) + newRows }, size = BtnSize.Sm, icon = Icons.Plus)
                Btn("Delete${if (selected.isNotEmpty()) " (${selected.size})" else ""}", { deleteSelected() }, size = BtnSize.Sm, kind = BtnKind.DangerGhost, enabled = selected.isNotEmpty())
            }
            Grow()
            Btn(count?.let { "$it rows" } ?: "Count rows", { runCount() }, size = BtnSize.Sm)
            Btn("CSV", { exportData("csv") }, size = BtnSize.Sm, enabled = rows.isNotEmpty())
            Btn("JSON", { exportData("json") }, size = BtnSize.Sm, enabled = rows.isNotEmpty())
        }
        HLine()

        // Filter bar
        if (filters.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(colors.bg2).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.forEach { f ->
                    val col = columns.find { it.name == f.column }
                    fun update(next: FilterDraft) {
                        filters = filters.map { if (it.id == f.id) next else it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Select(f.column, columns.map { it.name }, { update(FilterDraft(f.id, it, f.op, f.text)) }, Modifier.width(180.dp), small = true)
                        Select(f.op, OPS, { update(FilterDraft(f.id, f.column, it, f.text)) }, Modifier.width(130.dp), small = true)
                        Input(
                            f.text,
                            { update(FilterDraft(f.id, f.column, f.op, it)) },
                            Modifier.width(320.dp),
                            small = true,
                            placeholder = if (f.op == "IN") "a, b, c  or  JSON array" else col?.let { filterValueType(it.type, f.op) }.orEmpty(),
                            onEnter = { applyFilters() },
                        )
                        IconBtn(Icons.Close, { filters = filters.filterNot { it.id == f.id } })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("+ Condition", { filters = filters + FilterDraft(filterSeq++, columns.first().name) }, size = BtnSize.Xs)
                    Checkbox(
                        allowFiltering,
                        {
                            allowFiltering = it
                            scope.launch { reload(0, allow = it) }
                        },
                        label = "ALLOW FILTERING",
                        small = true,
                    )
                    Grow()
                    Btn("Clear", {
                        filters = emptyList()
                        applied = emptyList()
                        count = null
                        scope.launch { reload(0, withFilters = emptyList()) }
                    }, size = BtnSize.Xs)
                    Btn("Apply", { applyFilters() }, size = BtnSize.Xs, kind = BtnKind.Primary)
                }
            }
            HLine()
        }

        // Error
        error?.let { message ->
            Column(Modifier.fillMaxWidth().background(colors.dangerSoft).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(message, color = colors.danger, mono = true, size = 12.sp)
                if (needsAllowFiltering) {
                    Btn("Retry with ALLOW FILTERING", {
                        allowFiltering = true
                        scope.launch { reload(0, allow = true) }
                    }, size = BtnSize.Sm)
                }
            }
        }

        // Grid
        val listState = rememberLazyListState()
        val horizontal = rememberScrollState()
        var viewport by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val contentWidth = maxOf(
            RowNumWidth + visibleCols.fold(0.dp) { acc, c -> acc + widths.getValue(c.name) },
            with(density) { viewport.toDp() },
        )
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it.width }.alpha(if (loading) 0.6f else 1f)) {
            Column(Modifier.fillMaxHeight().horizontalScroll(horizontal).width(contentWidth)) {
                Row(Modifier.fillMaxWidth().background(colors.bg2), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RowNumWidth).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (!readOnly) Checkbox(allSelected, { selected = if (allSelected) emptySet() else rows.indices.toSet() })
                    }
                    visibleCols.forEach { c -> GridHeader(c.name, c.type, widths.getValue(c.name), c.kind) }
                }
                HLine()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), listState) {
                    itemsIndexed(newRows, key = { i, _ -> "new-$i" }) { i, r ->
                        Row(Modifier.fillMaxWidth().height(GridRowHeight).background(colors.newRow), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(RowNumWidth).padding(horizontal = 6.dp)) {
                                IconBtn(Icons.Close, { newRows = newRows.filterIndexed { j, _ -> j != i } }, tint = colors.danger, size = 20.dp)
                            }
                            visibleCols.forEach { c ->
                                val ref = CellRef(i, true, c.name)
                                val value = r[c.name]
                                Cell(
                                    column = c,
                                    value = value,
                                    width = widths.getValue(c.name),
                                    changed = !isNull(value),
                                    required = c.isKey,
                                    editing = editing == ref,
                                    editable = true,
                                    onStart = { big -> startEdit(ref, big) },
                                    onCommit = {
                                        setCell(ref, it)
                                        editing = null
                                    },
                                    onCancel = { editing = null },
                                    onError = { toaster.error(it) },
                                )
                            }
                        }
                        HLine()
                    }
                    itemsIndexed(rows, key = { i, _ -> i }) { i, r ->
                        val isSelected = i in selected
                        Row(Modifier.fillMaxWidth().height(GridRowHeight).background(if (isSelected) colors.selected else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.width(RowNumWidth).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!readOnly) Checkbox(isSelected, { selected = if (isSelected) selected - i else selected + i })
                                Muted((page * pageSize + i + 1).toString(), size11 = true)
                            }
                            visibleCols.forEach { c ->
                                val ref = CellRef(i, false, c.name)
                                val changed = edits[i]?.containsKey(c.name) == true
                                Cell(
                                    column = c,
                                    value = if (changed) edits.getValue(i)[c.name] else r[c.name],
                                    width = widths.getValue(c.name),
                                    changed = changed,
                                    required = false,
                                    editing = editing == ref,
                                    editable = canEdit(ref),
                                    onStart = { big -> startEdit(ref, big) },
                                    onCommit = {
                                        setCell(ref, it)
                                        editing = null
                                    },
                                    onCancel = { editing = null },
                                    onError = { toaster.error(it) },
                                )
                            }
                        }
                        HLine()
                    }
                    if (!loading && error == null && rows.isEmpty() && newRows.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Muted("No rows${if (applied.isNotEmpty()) " match these filters" else ""}.", small = false)
                            }
                        }
                    }
                }
            }
            GridScrollbars(listState, horizontal)
        }
        HLine()

        // Footer
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dirtyCount > 0) {
                Txt("$dirtyCount pending change(s)", bold = true, size = 12.sp)
                Btn("Discard", { discard() }, size = BtnSize.Sm, enabled = !saving)
                Btn(if (saving) "Saving…" else "Save $dirtyCount change(s)", { save() }, size = BtnSize.Sm, kind = BtnKind.Primary, enabled = !saving)
            } else {
                LinkText(
                    (result?.let { "${rows.size} row(s) · ${it.elapsedMs} ms  " } ?: "") + (if (showCql) "▾ CQL" else "▸ CQL"),
                    { showCql = !showCql },
                    color = colors.muted,
                    size = 12.sp,
                )
            }
            Grow()
            result?.warnings?.forEach { w -> Txt("⚠ ${w.take(60)}", color = colors.warn, size = 12.sp, maxLines = 1) }
            Select(
                pageSize,
                PAGE_SIZES,
                { size ->
                    scope.launch {
                        if (guardDirty()) {
                            pageSize = size
                            Storage.set("cc.pageSize", size.toString())
                            reload(0, size = size)
                        }
                    }
                },
                Modifier.width(120.dp),
                label = { "$it / page" },
                small = true,
            )
            Btn("‹ Prev", { scope.launch { if (guardDirty()) reload(page - 1) } }, size = BtnSize.Sm, enabled = page > 0 && !loading)
            Muted("Page ${page + 1}")
            Btn("Next ›", { scope.launch { if (guardDirty()) reload(page + 1) } }, size = BtnSize.Sm, enabled = pageStates.getOrNull(page + 1) != null && !loading)
        }
        if (showCql && dirtyCount == 0) result?.let { CodeBlock(it.cql, Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp), maxHeight = 140.dp) }
    }

    bigEditor?.let { ref ->
        BigEditor(
            column = columns.first { it.name == ref.col },
            value = cellValue(ref),
            onClose = { bigEditor = null },
            onSave = {
                setCell(ref, it)
                bigEditor = null
            },
        )
    }
    viewing?.let { ref ->
        val column = columns.first { it.name == ref.col }
        CellViewer(column.name, column.type, displayForViewer(cellValue(ref), column.type)) { viewing = null }
    }
}

private fun displayForViewer(json: String?, type: String) =
    if (valueKind(type) == ValueKind.JSON && !isNull(json)) prettyJson(json!!) else displayValue(json, type)

fun csvEscape(s: String) = if (s.any { it == '"' || it == ',' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s

private fun buildFilter(f: FilterDraft, columns: List<CqlColumn>): Filter {
    val col = columns.first { it.name == f.column }
    val type = filterValueType(col.type, f.op)
    if (f.op == "IN") {
        val t = f.text.trim()
        if (t.startsWith("[")) return Filter(f.column, f.op, t)
        val items = t.split(",").map { fromEditable(if (valueKind(type) == ValueKind.STRING) it.trim() else it, type) }
        return Filter(f.column, f.op, "[${items.joinToString(", ")}]")
    }
    return Filter(f.column, f.op, fromEditable(f.text, type))
}

@Composable
private fun Muted(text: String, size11: Boolean) {
    Txt(text, color = colors.muted, size = if (size11) 11.sp else 12.sp, maxLines = 1)
}

@Composable
fun GridHeader(name: String, type: String, width: Dp, kind: String? = null) {
    Column(Modifier.width(width).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(name, Modifier.weight(1f, fill = false), bold = true, size = 12.sp, maxLines = 1)
            if (kind != null) KindBadge(kind)
        }
        Txt(type, color = colors.muted, size = 10.sp, mono = true, maxLines = 1)
    }
}

@Composable
fun GridScrollbars(list: LazyListState, horizontal: androidx.compose.foundation.ScrollState) {
    Box(Modifier.fillMaxSize()) {
        VScrollbar(rememberScrollbarAdapter(list))
        HScrollbar(rememberScrollbarAdapter(horizontal))
    }
}

@Composable
private fun Cell(
    column: CqlColumn,
    value: String?,
    width: Dp,
    changed: Boolean,
    required: Boolean,
    editing: Boolean,
    editable: Boolean,
    onStart: (Boolean) -> Unit,
    onCommit: (String?) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val kind = valueKind(column.type)
    val nul = isNull(value)

    if (editing) {
        if (kind == ValueKind.BOOLEAN) {
            Select(
                if (nul) "NULL" else value!!,
                listOf("NULL", "true", "false"),
                { onCommit(if (it == "NULL") null else it) },
                Modifier.width(width).padding(horizontal = 2.dp),
                small = true,
                initiallyOpen = true,
                onDismiss = onCancel,
            )
        } else {
            CellEditor(column, value, width, onCommit, onCancel, onError)
        }
        return
    }

    val c = colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val start by rememberUpdatedState(onStart)
    val background = when {
        changed -> c.changed
        required && nul -> c.dangerSoft
        else -> Color.Transparent
    }
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(background)
            .hoverable(interaction)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { start(false) }) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Txt(
            displayValue(value, column.type),
            color = if (nul) c.nullText else if (!editable) c.muted else c.text,
            mono = kind == ValueKind.JSON || kind == ValueKind.NUMBER,
            size = 12.sp,
            maxLines = 1,
        )
        if (editable && hovered) {
            IconBtn(Icons.Expand, { onStart(true) }, Modifier.align(Alignment.CenterEnd).background(c.bg), size = 18.dp)
        }
    }
}

@Composable
private fun CellEditor(column: CqlColumn, value: String?, width: Dp, onCommit: (String?) -> Unit, onCancel: () -> Unit, onError: (String) -> Unit) {
    val kind = valueKind(column.type)
    val focus = remember { FocusRequester() }
    var field by remember {
        val text = toEditable(value, column.type)
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    var finished by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }

    fun commit() {
        if (finished) return
        try {
            val json = if (field.text.isEmpty() && kind != ValueKind.STRING) null else fromEditable(field.text, column.type)
            finished = true
            onCommit(json)
        } catch (e: Exception) {
            onError(e.text)
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = field,
        onValueChange = { field = it },
        singleLine = true,
        textStyle = textStyle(12.sp, colors.text, kind != ValueKind.STRING),
        cursorBrush = SolidColor(colors.text),
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(colors.bg)
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) commit()
            }
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        commit()
                        true
                    }
                    Key.Escape -> {
                        finished = true
                        onCancel()
                        true
                    }
                    else -> false
                }
            },
    )
}

@Composable
private fun BigEditor(column: CqlColumn, value: String?, onClose: () -> Unit, onSave: (String?) -> Unit) {
    val kind = valueKind(column.type)
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf(if (kind == ValueKind.JSON && !isNull(value)) prettyJson(value!!) else toEditable(value, column.type)) }
    var err by remember { mutableStateOf<String?>(null) }
    fun save() {
        try {
            onSave(fromEditable(if (kind == ValueKind.JSON) text.trim() else text, column.type))
        } catch (e: Exception) {
            err = e.text
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Modal(
        title = column.name,
        subtitle = column.type,
        wide = true,
        onClose = onClose,
        footer = {
            Btn("Set NULL", { onSave(null) }, kind = BtnKind.DangerGhost)
            Grow()
            Btn("Cancel", onClose)
            Btn("Apply", { save() }, kind = BtnKind.Primary)
        },
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                err = null
            },
            textStyle = textStyle(12.sp, colors.text, mono = true),
            cursorBrush = SolidColor(colors.text),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 420.dp)
                .background(colors.bg2, Radius)
                .padding(10.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed) && it.key == Key.Enter) {
                        save()
                        true
                    } else {
                        false
                    }
                },
        )
        if (kind == ValueKind.JSON) cassandraclient.ui.Muted("Value in Cassandra JSON format (as accepted by fromJson()). Ctrl+Enter to apply.")
        err?.let { TestResult(false, it) }
    }
}

/** Read-only view of a cell's full value: truncated text has no tooltip on canvas-rendered UI. */
@Composable
fun CellViewer(name: String, type: String, text: String, onClose: () -> Unit) {
    Modal(title = name, subtitle = type, wide = true, onClose = onClose) {
        CodeBlock(text, maxHeight = 460.dp)
    }
}
