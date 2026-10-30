package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.AppJson
import cassandraclient.core.CONSISTENCY_LEVELS
import cassandraclient.core.ExecuteRequest
import cassandraclient.core.StatementResult
import cassandraclient.core.TraceInfo
import cassandraclient.core.plainToText
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class HistoryEntry(val cql: String, val at: Long, val conn: String, val ok: Boolean)

private const val HISTORY_KEY = "cc.history"

private fun loadHistory(): List<HistoryEntry> =
    try {
        Storage.get(HISTORY_KEY)?.let { AppJson.decodeFromString<List<HistoryEntry>>(it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

private fun pushHistory(e: HistoryEntry) {
    val list = (listOf(e) + loadHistory().filter { it.cql != e.cql }).take(200)
    Storage.set(HISTORY_KEY, AppJson.encodeToString(list))
}

private val DdlStatement = Regex("^\\s*(create|alter|drop)\\b", RegexOption.IGNORE_CASE)
private val PrettyJson = Json { prettyPrint = true }

@Composable
fun QueryEditor(initialCql: String?, initialKeyspace: String?, active: Boolean) {
    val ws = LocalWorkspace.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var editor by remember { mutableStateOf(TextFieldValue(initialCql.orEmpty())) }
    var keyspace by remember { mutableStateOf(initialKeyspace.orEmpty()) }
    var consistency by remember { mutableStateOf("LOCAL_ONE") }
    var pageSize by remember { mutableIntStateOf(200) }
    var trace by remember { mutableStateOf(false) }
    var continueOnError by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<StatementResult>()) }
    var activeResult by remember { mutableIntStateOf(0) }
    var lastCql by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var editorHeight by remember { mutableStateOf(240.dp) }

    val schema = remember(ws.keyspaces, keyspace) {
        CqlSchema(
            ws.keyspaces.associate { ks -> ks.name to (ks.tables.map { it.name } + ks.views.map { it.name }) },
            keyspace.ifEmpty { null },
        )
    }

    LaunchedEffect(active) {
        if (active) runCatching { focus.requestFocus() }
    }

    suspend fun execute(cql: String, pageState: String? = null) {
        if (cql.isBlank()) return
        running = true
        try {
            val r = ws.api.execute(
                ws.connId,
                ExecuteRequest(cql, keyspace.ifEmpty { null }, pageSize, pageState, consistency, trace, continueOnError),
            )
            if (pageState != null) {
                // "Load more": append rows to the single existing result.
                val p = results.firstOrNull()
                val n = r.results.firstOrNull()
                results = if (p == null || n == null || n.error != null) r.results else listOf(n.copy(rows = p.rows + n.rows))
            } else {
                results = r.results
                val firstError = r.results.indexOfFirst { it.error != null }
                activeResult = if (firstError >= 0) firstError else r.results.lastIndex.coerceAtLeast(0)
                lastCql = cql
                pushHistory(HistoryEntry(cql, Clock.System.now().toEpochMilliseconds(), ws.conn.name, firstError < 0))
            }
            if (r.results.any { it.error == null && DdlStatement.containsMatchIn(it.statement) }) ws.refreshSchema()
            if (r.keyspace != null && r.keyspace != keyspace) keyspace = r.keyspace.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = listOf(StatementResult(statement = cql, error = e.text))
            activeResult = 0
        } finally {
            running = false
        }
    }

    fun run() {
        val sel = editor.selection
        val text = if (sel.collapsed) editor.text else editor.text.substring(sel.min, sel.max)
        scope.launch { execute(text) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxHeight
        Column(Modifier.fillMaxSize()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Btn(if (running) "Running…" else "Run", { run() }, kind = BtnKind.Primary, size = BtnSize.Sm, enabled = !running, icon = Icons.Play)
                Muted("Ctrl+Enter · runs selection if any", maxLines = 1)
                InlineField("Keyspace") {
                    Select(keyspace, listOf("") + ws.keyspaces.map { it.name }, { keyspace = it }, Modifier.width(170.dp), label = { it.ifEmpty { "(none)" } }, small = true)
                }
                InlineField("Consistency") {
                    Select(consistency, CONSISTENCY_LEVELS, { consistency = it }, Modifier.width(140.dp), small = true)
                }
                InlineField("Page") {
                    Select(pageSize, listOf(50, 100, 200, 500, 1000, 5000), { pageSize = it }, Modifier.width(80.dp), small = true)
                }
                Checkbox(trace, { trace = it }, label = "Tracing", small = true)
                Checkbox(continueOnError, { continueOnError = it }, label = "Continue on error", small = true)
                Btn("History", { showHistory = true }, size = BtnSize.Sm)
            }
            HLine()

            CqlEditor(editor, { editor = it }, ::run, schema, focus, Modifier.fillMaxWidth().height(editorHeight))
            Splitter { deltaDp -> editorHeight = (editorHeight + deltaDp).coerceIn(80.dp, (total - 200.dp).coerceAtLeast(80.dp)) }

            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (results.size > 1) {
                    Tabs(
                        results.indices.map { i ->
                            val r = results[i]
                            i to ((if (r.error != null) "✕ " else "") + r.statement.replace(Regex("\\s+"), " ").take(32))
                        },
                        activeResult,
                        { activeResult = it },
                    )
                }
                val current = results.getOrNull(activeResult)
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("Run a statement to see results here.", small = false) }
                } else {
                    ResultView(
                        current,
                        canLoadMore = results.size == 1 && current.pageState != null,
                        onLoadMore = { scope.launch { execute(lastCql, current.pageState) } },
                        loading = running,
                    )
                }
            }
        }
    }

    if (showHistory) {
        HistoryDialog(onClose = { showHistory = false }) { cql ->
            editor = TextFieldValue(cql, TextRange(cql.length))
            showHistory = false
            scope.launch { runCatching { focus.requestFocus() } }
        }
    }
}

@Composable
private fun InlineField(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Muted(label, maxLines = 1)
        content()
    }
}

@Composable
private fun Splitter(onDrag: (androidx.compose.ui.unit.Dp) -> Unit) {
    val density = LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(colors.bg3)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    drag(with(density) { dragAmount.toDp() })
                }
            },
    )
}

private fun jsonRowsToObjects(result: StatementResult): JsonArray = JsonArray(
    result.rows.map { r -> JsonObject(result.columns.withIndex().associate { (i, c) -> c.name to r.getOrElse(i) { JsonNull } }) },
)

private fun exportResult(result: StatementResult, format: String) {
    val text = if (format == "json") {
        PrettyJson.encodeToString(JsonElement.serializer(), jsonRowsToObjects(result))
    } else {
        (listOf(result.columns.joinToString(",") { csvEscape(it.name) }) + result.rows.map { r ->
            r.joinToString(",") { if (it is JsonNull) "" else csvEscape(plainToText(it)) }
        }).joinToString("\n")
    }
    saveTextFile("result.$format", if (format == "json") "application/json" else "text/csv", text)
}

private enum class ResultMode { Table, Json, Trace }

@Composable
private fun ResultView(result: StatementResult, canLoadMore: Boolean, onLoadMore: () -> Unit, loading: Boolean) {
    var mode by remember { mutableStateOf(ResultMode.Table) }
    result.error?.let { error ->
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBox(error)
            CodeBlock(result.statement, maxHeight = 160.dp)
        }
        return
    }
    val cols = result.columns
    val rows = result.rows

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cols.isNotEmpty()) {
                Txt("${rows.size} row(s)", size = 12.sp, bold = true, maxLines = 1)
                Segmented(
                    buildList {
                        add(ResultMode.Table to "Table")
                        add(ResultMode.Json to "JSON")
                        if (result.trace != null) add(ResultMode.Trace to "Trace")
                    },
                    mode,
                ) { mode = it }
            } else {
                Txt("✓ Statement executed", color = colors.ok, size = 12.sp, maxLines = 1)
            }
            Muted("${result.elapsedMs} ms${result.queriedHost?.let { " · $it" }.orEmpty()}", maxLines = 1)
            result.warnings.forEach { Txt("⚠ $it", color = colors.warn, size = 12.sp, maxLines = 1) }
            Grow()
            if (cols.isNotEmpty()) {
                Btn("CSV", { exportResult(result, "csv") }, size = BtnSize.Xs)
                Btn("JSON", { exportResult(result, "json") }, size = BtnSize.Xs)
            } else if (result.trace != null) {
                Btn("Trace", { mode = ResultMode.Trace }, size = BtnSize.Xs)
            }
        }
        HLine()
        val trace = result.trace
        when {
            mode == ResultMode.Trace && trace != null -> TraceView(trace)
            mode == ResultMode.Json -> ScrollColumn(Modifier.fillMaxSize().padding(10.dp)) {
                CodeBlock(PrettyJson.encodeToString(JsonElement.serializer(), jsonRowsToObjects(result)), maxHeight = 100000.dp)
            }
            cols.isNotEmpty() -> ResultGrid(result, canLoadMore, onLoadMore, loading)
        }
    }
}

@Composable
private fun Segmented(options: List<Pair<ResultMode, String>>, active: ResultMode, onChange: (ResultMode) -> Unit) {
    Row(Modifier.clip(Radius).background(colors.bg3)) {
        options.forEach { (id, label) ->
            Txt(
                label,
                Modifier
                    .clip(Radius)
                    .background(if (id == active) colors.accentSoft else Color.Transparent)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(id) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                color = if (id == active) colors.accent else colors.muted,
                size = 11.sp,
            )
        }
    }
}

@Composable
private fun ResultGrid(result: StatementResult, canLoadMore: Boolean, onLoadMore: () -> Unit, loading: Boolean) {
    val cols = result.columns
    val rows = result.rows
    val widths = remember(cols, rows) {
        cols.indices.map { i ->
            val longest = rows.take(200).maxOfOrNull { plainToText(it.getOrElse(i) { JsonNull }).length } ?: 0
            gridColumnWidth(cols[i].name, cols[i].type, longest)
        }
    }
    var viewing by remember(result) { mutableStateOf<Pair<Int, JsonElement>?>(null) }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val rowNum = 56.dp
    val contentWidth = maxOf(rowNum + widths.fold(0.dp) { a, b -> a + b }, with(density) { viewport.toDp() })

    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it.width }) {
        Column(Modifier.fillMaxHeight().horizontalScroll(horizontal).width(contentWidth)) {
            Row(Modifier.fillMaxWidth().background(colors.bg2)) {
                Box(Modifier.width(rowNum))
                cols.forEachIndexed { i, c -> GridHeader(c.name, c.type, widths[i]) }
            }
            HLine()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), listState) {
                itemsIndexed(rows) { i, r ->
                    Row(Modifier.fillMaxWidth().height(GridRowHeight), verticalAlignment = Alignment.CenterVertically) {
                        Txt((i + 1).toString(), Modifier.width(rowNum).padding(horizontal = 8.dp), color = colors.muted, size = 11.sp, maxLines = 1)
                        r.forEachIndexed { j, v ->
                            ResultCell(v, widths.getOrElse(j) { 120.dp }) { viewing = j to v }
                        }
                    }
                    HLine()
                }
                if (canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.CenterStart) {
                            Btn("Load more", onLoadMore, size = BtnSize.Sm, enabled = !loading)
                        }
                    }
                }
            }
        }
        GridScrollbars(listState, horizontal)
    }
    viewing?.let { (j, v) ->
        val text = if (v is JsonPrimitive || v is JsonNull) plainToText(v) else PrettyJson.encodeToString(JsonElement.serializer(), v)
        CellViewer(cols[j].name, cols[j].type, text) { viewing = null }
    }
}

@Composable
private fun ResultCell(v: JsonElement, width: androidx.compose.ui.unit.Dp, onOpen: () -> Unit) {
    val open by rememberUpdatedState(onOpen)
    val isString = v is JsonPrimitive && v.isString
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { open() }) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Txt(
            plainToText(v),
            color = if (v is JsonNull) colors.nullText else colors.text,
            mono = !isString && v !is JsonNull,
            size = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun TraceView(trace: TraceInfo) {
    trace.error?.let {
        ErrorBox(it, modifier = Modifier.padding(12.dp))
        return
    }
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ms = ((trace.durationMicros ?: 0) / 10) / 100.0
        Muted("Trace ${trace.traceId} · coordinator ${trace.coordinator.orEmpty()} · $ms ms")
        ListTable(listOf(ListColumn("Activity", 4f), ListColumn("Source", 1.2f), ListColumn("Elapsed (µs)", 0.8f), ListColumn("Thread", 1.6f))) {
            LazyList(trace.events, Modifier.fillMaxSize()) { _, e ->
                ListRow {
                    Txt(e.activity, Modifier.weight(4f), size = 12.sp)
                    Txt(e.source, Modifier.weight(1.2f), mono = true, size = 11.sp, maxLines = 1)
                    Txt(e.elapsedMicros.toString(), Modifier.weight(0.8f), size = 12.sp, maxLines = 1)
                    Muted(e.thread, Modifier.weight(1.6f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(onClose: () -> Unit, onPick: (String) -> Unit) {
    var items by remember { mutableStateOf(loadHistory()) }
    var q by remember { mutableStateOf("") }
    val shown = items.filter { q.isEmpty() || it.cql.contains(q, ignoreCase = true) }
    Modal(
        title = "Query history",
        wide = true,
        onClose = onClose,
        footer = {
            Btn("Clear history", {
                Storage.remove(HISTORY_KEY)
                items = emptyList()
            }, kind = BtnKind.DangerGhost)
        },
    ) {
        Input(q, { q = it }, Modifier.fillMaxWidth(), placeholder = "Search…", autoFocus = true)
        shown.forEach { h ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius)
                    .background(colors.bg2)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(h.cql) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Muted("${if (h.ok) "✓" else "✕"} ${formatTimestamp(h.at)} · ${h.conn}")
                Txt(if (h.cql.length > 400) h.cql.take(400) + "…" else h.cql, mono = true, size = 12.sp)
            }
        }
        if (shown.isEmpty()) EmptyListRow("No queries yet.")
    }
}
