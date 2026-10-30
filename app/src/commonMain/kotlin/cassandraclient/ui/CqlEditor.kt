package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

private val KEYWORDS = (
    "add aggregate all allow alter and apply as asc ascii authorize batch begin by called clustering columnfamily compact contains count create custom default delete desc describe distinct drop each_quorum entries execute exists filtering finalfunc from frozen full function functions grant if in index infinity initcond input inet insert into is json key keys keyspace keyspaces language like limit list local_one local_quorum login materialized mbean mbeans modify nan norecursive nosuperuser not null of on one options or order partition password per permission permissions primary quorum rename replace returns revoke role roles schema select set sfunc static storage superuser table tables text timestamp to token trigger truncate ttl tuple type unlogged unset update use user users using values view where with writetime maxwritetime access datacenters cidrs masked unmask select_masked mask_inner mask_outer mask_null mask_default mask_replace mask_hash sai describe"
    ).split(" ").toSet()
private val TYPES = "ascii bigint blob boolean counter date decimal double duration float inet int list map set smallint text time timestamp timeuuid tinyint tuple uuid varchar varint vector frozen".split(" ").toSet()
private val BUILTINS = "now uuid totimestamp tojson fromjson token ttl writetime count min max sum avg cast tounixtimestamp currenttimestamp currentdate".split(" ").toSet()

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

/** Highlights CQL; offsets are unchanged so no mapping is needed. */
private fun highlightCql(text: String, palette: AppColors): AnnotatedString = buildAnnotatedString {
    append(text)
    fun mark(from: Int, to: Int, style: SpanStyle) = addStyle(style, from, to)
    val comment = SpanStyle(color = palette.muted)
    val string = SpanStyle(color = palette.syntaxString)
    val number = SpanStyle(color = palette.syntaxNumber)
    val keyword = SpanStyle(color = palette.syntaxKeyword, fontWeight = FontWeight.SemiBold)
    val type = SpanStyle(color = palette.syntaxType)
    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]
        val next = text.getOrNull(i + 1)
        when {
            (ch == '-' && next == '-') || (ch == '/' && next == '/') -> {
                val end = text.indexOf('\n', i).let { if (it == -1) n else it }
                mark(i, end, comment)
                i = end
            }
            ch == '/' && next == '*' -> {
                val end = text.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
                mark(i, end, comment)
                i = end
            }
            ch == '$' && next == '$' -> {
                val end = text.indexOf("$$", i + 2).let { if (it == -1) n else it + 2 }
                mark(i, end, string)
                i = end
            }
            ch == '\'' || ch == '"' -> {
                var j = i + 1
                while (j < n) {
                    if (text[j] == ch) {
                        if (text.getOrNull(j + 1) == ch) j += 2 else break
                    } else {
                        j++
                    }
                }
                val end = minOf(j + 1, n)
                if (ch == '\'') mark(i, end, string)
                i = end
            }
            ch.isDigit() -> {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '-')) j++
                mark(i, j, number)
                i = j
            }
            isWordChar(ch) -> {
                var j = i
                while (j < n && isWordChar(text[j])) j++
                val word = text.substring(i, j).lowercase()
                when (word) {
                    in TYPES -> mark(i, j, type)
                    in KEYWORDS -> mark(i, j, keyword)
                    in BUILTINS -> mark(i, j, type)
                }
                i = j
            }
            else -> i++
        }
    }
}

/** What autocomplete knows about: keyspace → its tables and views. */
class CqlSchema(val tablesByKeyspace: Map<String, List<String>>, val currentKeyspace: String?)

private class CompletionRequest(val from: Int, val candidates: List<String>)

private fun completionsAt(text: String, caret: Int, schema: CqlSchema): CompletionRequest? {
    var start = caret
    while (start > 0 && isWordChar(text[start - 1])) start--
    if (caret < text.length && isWordChar(text[caret])) return null
    val prefix = text.substring(start, caret)
    val qualifier = if (start > 0 && text[start - 1] == '.') {
        var q = start - 1
        while (q > 0 && (isWordChar(text[q - 1]) || text[q - 1] == '"')) q--
        text.substring(q, start - 1).trim('"')
    } else {
        null
    }
    if (prefix.isEmpty() && qualifier == null) return null
    val needle = prefix.lowercase()
    val pool: List<String> = if (qualifier != null) {
        schema.tablesByKeyspace[qualifier].orEmpty()
    } else {
        val tables = schema.currentKeyspace?.let { schema.tablesByKeyspace[it] }.orEmpty()
        tables + schema.tablesByKeyspace.keys + (KEYWORDS + TYPES).map { it.uppercase() } + BUILTINS
    }
    val matches = pool.distinct().filter { it.lowercase().startsWith(needle) && it.lowercase() != needle }.take(10)
    return if (matches.isEmpty()) null else CompletionRequest(start, matches)
}

private val CqlLineHeight = 13 * 1.45f

/**
 * The CodeMirror replacement: highlighting, line numbers, autocomplete,
 * Tab indentation and Ctrl/Shift+Enter to run. Undo comes with the text field.
 */
@Composable
fun CqlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onRun: () -> Unit,
    schema: CqlSchema,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val palette = colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var completion by remember { mutableStateOf<CompletionRequest?>(null) }
    var selectedCompletion by remember { mutableIntStateOf(0) }

    val transformation = remember(palette) {
        VisualTransformation { text -> TransformedText(highlightCql(text.text, palette), OffsetMapping.Identity) }
    }

    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)

    // Edits from a key handler wait for the next frame: on the web, the browser's hidden input
    // syncs its own text right after the key event and would otherwise overwrite them.
    fun editNextFrame(edit: (TextFieldValue) -> TextFieldValue) {
        scope.launch {
            withFrameNanos { }
            latestOnChange(edit(latestValue))
        }
    }

    fun accept(candidate: String) {
        val req = completion ?: return
        completion = null
        editNextFrame { current ->
            val caret = current.selection.end
            TextFieldValue(current.text.replaceRange(req.from, caret, candidate), TextRange(req.from + candidate.length))
        }
    }

    fun indent(unindent: Boolean) = editNextFrame { current ->
        val sel = current.selection
        val text = current.text
        if (sel.collapsed && !unindent) {
            return@editNextFrame TextFieldValue(text.replaceRange(sel.min, sel.max, "  "), TextRange(sel.min + 2))
        }
        val lineStart = if (sel.min == 0) 0 else text.lastIndexOf('\n', sel.min - 1) + 1
        val lines = text.substring(lineStart, sel.max).split('\n')
        val indentOf = { line: String -> if (unindent) -minOf(2, line.length - line.trimStart(' ').length) else 2 }
        val changed = lines.joinToString("\n") { line ->
            val by = indentOf(line)
            if (by >= 0) " ".repeat(by) + line else line.drop(-by)
        }
        val newStart = (sel.min + indentOf(lines.first())).coerceAtLeast(lineStart)
        val newEnd = sel.max + changed.length - (sel.max - lineStart)
        TextFieldValue(text.substring(0, lineStart) + changed + text.substring(sel.max), TextRange(newStart, newEnd))
    }

    val lineTops: List<Pair<Int, Float>> = run {
        val l = layout
        val starts = buildList {
            add(0)
            value.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
        starts.mapIndexed { i, offset ->
            val top = if (l != null && offset <= l.layoutInput.text.length) {
                l.getLineTop(l.getLineForOffset(offset))
            } else {
                with(density) { (i * CqlLineHeight).sp.toPx() }
            }
            (i + 1) to top
        }
    }
    val gutterDigits = lineTops.size.toString().length.coerceAtLeast(2)
    val gutterWidth = (gutterDigits * 8 + 16).dp

    val caretRect: Rect? = layout?.let { l ->
        val caret = value.selection.end
        if (caret <= l.layoutInput.text.length) l.getCursorRect(caret) else null
    }

    Box(modifier.background(palette.bg)) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // Gutter
            Box(Modifier.width(gutterWidth).background(palette.bg2).fillMaxHeight()) {
                lineTops.forEach { (number, top) ->
                    Txt(
                        number.toString(),
                        Modifier.offset { IntOffset(0, top.toInt()) }.padding(start = 6.dp, top = 8.dp),
                        color = palette.muted,
                        mono = true,
                        size = 11.sp,
                    )
                }
            }
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    val typed = next.text != value.text && next.text.length > value.text.length
                    val moved = next.text == value.text && next.selection != value.selection
                    onValueChange(next)
                    completion = when {
                        typed && next.selection.collapsed -> completionsAt(next.text, next.selection.end, schema)
                        moved || next.text != value.text -> null
                        else -> completion
                    }
                    selectedCompletion = 0
                },
                textStyle = textStyle(13.sp, palette.text, mono = true),
                cursorBrush = SolidColor(palette.text),
                visualTransformation = transformation,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .padding(start = gutterWidth + 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val current = completion
                        if ((e.isCtrlPressed || e.isMetaPressed || e.isShiftPressed) && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            completion = null
                            onRun()
                            return@onPreviewKeyEvent true
                        }
                        if ((e.isCtrlPressed || e.isMetaPressed) && e.key == Key.Spacebar) {
                            completion = completionsAt(value.text, value.selection.end, schema)
                            selectedCompletion = 0
                            return@onPreviewKeyEvent true
                        }
                        if (current != null) {
                            when (e.key) {
                                Key.DirectionDown -> {
                                    selectedCompletion = (selectedCompletion + 1) % current.candidates.size
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    selectedCompletion = (selectedCompletion - 1 + current.candidates.size) % current.candidates.size
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                                    accept(current.candidates[selectedCompletion])
                                    return@onPreviewKeyEvent true
                                }
                                Key.Escape -> {
                                    completion = null
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        if (e.key == Key.Tab) {
                            indent(e.isShiftPressed)
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
            )
        }
        VScrollbar(rememberScrollbarAdapter(scroll))

        val current = completion
        if (current != null && caretRect != null) {
            val x = with(density) { (gutterWidth + 8.dp).roundToPx() } + caretRect.left.toInt()
            val y = with(density) { 8.dp.roundToPx() } + caretRect.bottom.toInt() - scroll.value
            // Not focusable: typing must keep going to the editor underneath.
            Popup(offset = IntOffset(x, y + 2), properties = PopupProperties(focusable = false)) {
                MenuSurface(Modifier.widthIn(min = 200.dp, max = 360.dp)) {
                    current.candidates.forEachIndexed { i, candidate ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(if (i == selectedCompletion) palette.accentSoft else Color.Transparent)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { accept(candidate) }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Txt(candidate, mono = true, size = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
