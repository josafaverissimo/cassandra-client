package cassandraclient.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cassandraclient.core.ColumnKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ---------- Text ---------- */

@Composable
fun textStyle(
    size: TextUnit = 13.sp,
    color: Color = colors.text,
    mono: Boolean = false,
    bold: Boolean = false,
): TextStyle = TextStyle(
    color = color,
    fontSize = size,
    fontFamily = if (mono) LocalFonts.current.mono else LocalFonts.current.sans,
    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    lineHeight = size * 1.45f,
)

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = colors.text,
    size: TextUnit = 13.sp,
    mono: Boolean = false,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = textStyle(size, color, mono, bold),
        maxLines = maxLines,
        softWrap = maxLines != 1,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = true, mono: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Txt(text, modifier, color = colors.muted, size = if (small) 12.sp else 13.sp, mono = mono, maxLines = maxLines)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier, size: TextUnit = 17.sp) {
    Txt(text, modifier, size = size, bold = true)
}

/* ---------- Layout helpers ---------- */

@Composable
fun HSpacer(width: Dp) = Spacer(Modifier.width(width))

@Composable
fun VSpacer(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun HLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

@Composable
fun VLine() = Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))

/** Shows only the active child but keeps the others composed, so they retain their state (like HTML `hidden`). */
@Composable
fun <T> KeepAlivePanels(items: List<T>, keyOf: (T) -> Any, isActive: (T) -> Boolean, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val activeIndex = items.indexOfFirst(isActive)
    Layout(
        modifier = modifier,
        content = {
            items.forEach { item ->
                key(keyOf(item)) {
                    Box(Modifier.fillMaxSize()) { content(item) }
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.getOrNull(activeIndex)?.measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable?.place(0, 0)
        }
    }
}

/** Scrollbars overlaid on their Box without influencing its size (a plain fillMaxHeight would stretch it). */
@Composable
fun BoxScope.VScrollbar(adapter: ScrollbarAdapter) {
    Box(Modifier.matchParentSize()) {
        VerticalScrollbar(adapter, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
fun BoxScope.HScrollbar(adapter: ScrollbarAdapter) {
    Box(Modifier.matchParentSize()) {
        HorizontalScrollbar(adapter, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/** A vertically scrolling area with a visible scrollbar. */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(state), verticalArrangement = verticalArrangement, content = content)
        VScrollbar(rememberScrollbarAdapter(state))
    }
}

/* ---------- Buttons ---------- */

enum class BtnKind { Default, Primary, Danger, DangerGhost, Active }
enum class BtnSize { Normal, Sm, Xs }

@Composable
fun Btn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Default,
    size: BtnSize = BtnSize.Normal,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val c = colors
    val background = when (kind) {
        BtnKind.Primary -> c.accent
        BtnKind.Danger -> c.danger
        BtnKind.DangerGhost -> if (hovered && enabled) c.dangerSoft else c.bg
        BtnKind.Active -> c.accentSoft
        BtnKind.Default -> if (hovered && enabled) c.bg3 else c.bg
    }
    val foreground = when (kind) {
        BtnKind.Primary, BtnKind.Danger -> if (c.isDark) Color(0xFF0E1117) else Color.White
        BtnKind.DangerGhost -> c.danger
        BtnKind.Active -> c.accent
        BtnKind.Default -> c.text
    }
    val borderColor = when (kind) {
        BtnKind.Primary -> c.accent
        BtnKind.Danger -> c.danger
        BtnKind.Active -> c.accent
        else -> c.borderStrong
    }
    val (h, pad, font) = when (size) {
        BtnSize.Normal -> Triple(32.dp, 12.dp, 13.sp)
        BtnSize.Sm -> Triple(27.dp, 9.dp, 12.sp)
        BtnSize.Xs -> Triple(22.dp, 7.dp, 11.sp)
    }
    Row(
        modifier = modifier
            .height(h)
            .clip(Radius)
            .background(background.copy(alpha = if (enabled) background.alpha else 0.55f))
            .border(1.dp, borderColor.copy(alpha = if (enabled) 1f else 0.5f), Radius)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .hoverable(interaction)
            .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Ico(icon, foreground.copy(alpha = if (enabled) 1f else 0.6f), size = if (size == BtnSize.Normal) 14.dp else 12.dp)
        if (label.isNotEmpty()) Txt(label, color = foreground.copy(alpha = if (enabled) 1f else 0.6f), size = font, maxLines = 1)
    }
}

@Composable
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = colors.muted, size: Dp = 22.dp, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(size)
            .clip(Radius)
            .background(if (hovered && enabled) colors.bg3 else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, tint, size = size * 0.6f)
    }
}

/** Plain clickable text, for table links and footers. */
@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = colors.accent, size: TextUnit = 13.sp, maxLines: Int = 1) {
    Txt(
        text,
        modifier.clickable(onClick = onClick, indication = null, interactionSource = remember { MutableInteractionSource() }).pointerHoverIcon(PointerIcon.Hand),
        color = color,
        size = size,
        maxLines = maxLines,
    )
}

/* ---------- Inputs ---------- */

@Composable
fun Input(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    mono: Boolean = false,
    small: Boolean = false,
    numeric: Boolean = false,
    autoFocus: Boolean = false,
    highlighted: Boolean = false,
    onEnter: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val c = colors
    val font = if (small) 12.sp else 13.sp
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle(font, c.text, mono),
        cursorBrush = SolidColor(c.text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange?.invoke(it.isFocused)
            }
            .onPreviewKeyEvent {
                if (onEnter != null && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onEnter()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (small) 26.dp else 32.dp)
                    .clip(Radius)
                    .background(if (highlighted) c.changed else c.bg)
                    .border(1.dp, if (focused) c.accent else c.borderStrong, Radius)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, color = c.muted, size = font, maxLines = 1)
                inner()
            }
        },
    )
}

/** An input with a dropdown of matching suggestions (the `<datalist>` of the web version). */
@Composable
fun SuggestInput(value: String, onValueChange: (String) -> Unit, suggestions: List<String>, modifier: Modifier = Modifier, placeholder: String = "") {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val shown = suggestions.filter { it.contains(value.trim(), ignoreCase = true) && it != value }.take(12)
    Box(modifier.onSizeChanged { anchorHeight = it.height; anchorWidth = it.width }) {
        Input(
            value,
            {
                dismissed = false
                onValueChange(it)
            },
            Modifier.fillMaxWidth(),
            placeholder = placeholder,
            onFocusChange = { focused = it },
        )
        if (focused && !dismissed && shown.isNotEmpty()) {
            Popup(offset = IntOffset(0, anchorHeight + 2), properties = PopupProperties(focusable = false)) {
                MenuSurface(Modifier.width(with(density) { anchorWidth.toDp() }).heightIn(max = 220.dp)) {
                    shown.forEach { s ->
                        MenuItem(onClick = {
                            onValueChange(s)
                            dismissed = true
                        }) { Txt(s, mono = true, size = 12.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberScrollState()
    Box(
        modifier
            .widthIn(max = 480.dp)
            .clip(Radius)
            .background(colors.bg)
            .border(1.dp, colors.borderStrong, Radius),
        propagateMinConstraints = true,
    ) {
        // Intrinsic width: items use fillMaxWidth, which would otherwise take the whole window.
        Column(Modifier.width(IntrinsicSize.Max).verticalScroll(state).padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun MenuItem(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .background(if (hovered) colors.bg3 else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A popup anchored under its parent. */
@Composable
fun Dropdown(anchorHeightPx: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        offset = IntOffset(0, anchorHeightPx + 2),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        content = content,
    )
}

@Composable
fun <T> Select(
    value: T,
    options: List<T>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    small: Boolean = false,
    initiallyOpen: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val font = if (small) 12.sp else 13.sp
    Box(modifier.onSizeChanged { anchorHeight = it.height; anchorWidth = it.width }) {
        Row(
            Modifier
                .height(if (small) 26.dp else 32.dp)
                .fillMaxWidth()
                .clip(Radius)
                .background(colors.bg)
                .border(1.dp, colors.borderStrong, Radius)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(label(value), Modifier.weight(1f), size = font, maxLines = 1)
            Ico(Icons.CaretDown, colors.muted, size = 11.dp)
        }
        if (open) {
            Dropdown(anchorHeight, {
                open = false
                onDismiss?.invoke()
            }) {
                MenuSurface(Modifier.widthIn(min = with(density) { anchorWidth.toDp() }).heightIn(max = 300.dp)) {
                    options.forEach { o ->
                        MenuItem(onClick = {
                            open = false
                            onChange(o)
                        }) {
                            Txt(label(o), color = if (o == value) colors.accent else colors.text, size = font, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Checkbox(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null, small: Boolean = false, enabled: Boolean = true) {
    val c = colors
    Row(
        modifier
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, enabled = enabled) { onChange(!checked) }
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) c.accent else c.bg)
                .border(1.dp, if (checked) c.accent else c.borderStrong, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Ico(Icons.Check, if (c.isDark) Color(0xFF0E1117) else Color.White, size = 11.dp)
        }
        if (label != null) Txt(label, size = if (small) 12.sp else 13.sp, maxLines = 1)
    }
}

/** Label above a form control. */
@Composable
fun Field(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Muted(label)
        content()
    }
}

@Composable
fun FormRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top, content = content)
}

/* ---------- Modal ---------- */

@Composable
fun Modal(
    title: String,
    onClose: () -> Unit,
    wide: Boolean = false,
    subtitle: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(density) { window.width.toDp() }
    val windowHeight = with(density) { window.height.toDp() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(minOf(if (wide) 860.dp else 560.dp, windowWidth - 32.dp))
                .heightIn(max = windowHeight * 0.92f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.bg)
                .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp))
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading(title, size = 15.sp)
                if (subtitle != null) Muted(subtitle, mono = true)
                Grow()
                IconBtn(Icons.Close, onClose)
            }
            HLine()
            ScrollColumn(Modifier.weight(1f, fill = false)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
            if (footer != null) {
                HLine()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

/* ---------- Toasts ---------- */

enum class ToastKind { Ok, Error, Info }

private class ToastData(val id: Long, val kind: ToastKind, val message: String, val detail: String?)

fun interface Toaster {
    fun show(kind: ToastKind, message: String, detail: String?)
}

fun Toaster.ok(message: String, detail: String? = null) = show(ToastKind.Ok, message, detail)
fun Toaster.error(message: String) = show(ToastKind.Error, message, null)

val LocalToaster = staticCompositionLocalOf { Toaster { _, _, _ -> } }

@Composable
fun ToastHost(content: @Composable () -> Unit) {
    val toasts = remember { mutableStateListOf<ToastData>() }
    val scope = rememberCoroutineScope()
    var nextId by remember { mutableStateOf(0L) }
    val toaster = remember {
        Toaster { kind, message, detail ->
            val toast = ToastData(nextId++, kind, message, detail)
            toasts += toast
            scope.launch {
                delay(if (kind == ToastKind.Error) 9000 else 4500)
                toasts.remove(toast)
            }
        }
    }
    CompositionLocalProvider(LocalToaster provides toaster) {
        content()
        // A popup so it stays above any open dialog.
        if (toasts.isNotEmpty()) {
            Popup(alignment = Alignment.BottomEnd, offset = IntOffset(-16, -16), properties = PopupProperties(focusable = false)) {
                Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    toasts.forEach { t ->
                        key(t.id) {
                            val c = colors
                            val accent = when (t.kind) {
                                ToastKind.Ok -> c.ok
                                ToastKind.Error -> c.danger
                                ToastKind.Info -> c.accent
                            }
                            Column(
                                Modifier
                                    .clip(Radius)
                                    .background(c.bg2)
                                    .border(BorderStroke(1.dp, accent), Radius)
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { toasts.remove(t) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Txt(t.message, maxLines = 6)
                                if (t.detail != null) Muted(t.detail, mono = true, maxLines = 6)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------- Confirm ---------- */

class ConfirmOptions(
    val title: String,
    val message: String? = null,
    val cql: String? = null,
    val confirmLabel: String = "Confirm",
    val danger: Boolean = false,
    /** When set, the user must type this text to enable the confirm button. */
    val typeToConfirm: String? = null,
)

fun interface Confirmer {
    suspend fun confirm(options: ConfirmOptions): Boolean
}

val LocalConfirmer = staticCompositionLocalOf { Confirmer { false } }

@Composable
fun ConfirmHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<Pair<ConfirmOptions, CompletableDeferred<Boolean>>?>(null) }
    val confirmer = remember {
        Confirmer { options ->
            val answer = CompletableDeferred<Boolean>()
            request = options to answer
            answer.await()
        }
    }
    CompositionLocalProvider(LocalConfirmer provides confirmer) {
        content()
        request?.let { (o, answer) ->
            var typed by remember(o) { mutableStateOf("") }
            val close = { v: Boolean ->
                answer.complete(v)
                request = null
            }
            val blocked = o.typeToConfirm != null && typed != o.typeToConfirm
            Modal(
                title = o.title,
                onClose = { close(false) },
                footer = {
                    Btn("Cancel", { close(false) })
                    Btn(o.confirmLabel, { close(true) }, kind = if (o.danger) BtnKind.Danger else BtnKind.Primary, enabled = !blocked)
                },
            ) {
                if (o.message != null) Txt(o.message)
                if (o.cql != null) CodeBlock(o.cql)
                if (o.typeToConfirm != null) {
                    Field("Type \"${o.typeToConfirm}\" to confirm") {
                        Input(typed, { typed = it }, Modifier.fillMaxWidth(), autoFocus = true, onEnter = { if (!blocked) close(true) })
                    }
                }
            }
        }
    }
}

/* ---------- Small pieces ---------- */

@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, maxHeight: Dp = 320.dp) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val vertical = rememberScrollState()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(Radius)
            .background(colors.bg2)
            .border(1.dp, colors.border, Radius),
    ) {
        SelectionContainer {
            Box(Modifier.fillMaxWidth().verticalScroll(vertical).padding(start = 10.dp, top = 10.dp, end = 56.dp, bottom = 10.dp)) {
                BasicText(code, style = textStyle(12.sp, colors.text, mono = true))
            }
        }
        Btn(
            if (copied) "Copied" else "Copy",
            {
                copyToClipboard(code)
                copied = true
                scope.launch {
                    delay(1200)
                    copied = false
                }
            },
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            size = BtnSize.Xs,
        )
        VScrollbar(rememberScrollbarAdapter(vertical))
    }
}

@Composable
fun Spinner(label: String? = null, modifier: Modifier = Modifier) {
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            angle = (angle + 14f) % 360f
        }
    }
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(Icons.Refresh, colors.muted, size = 16.dp, modifier = Modifier.rotate(angle))
        if (label != null) Muted(label, small = false)
    }
}

@Composable
fun ErrorBox(error: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(colors.dangerSoft)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionContainer { Txt(error, color = colors.danger, mono = true, size = 12.sp) }
        if (onRetry != null) Btn("Retry", onRetry, size = BtnSize.Sm)
    }
}

/** Ok/fail result line inside dialogs. */
@Composable
fun TestResult(ok: Boolean, text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(if (ok) colors.ok.copy(alpha = 0.12f) else colors.dangerSoft)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Txt(text, color = if (ok) colors.ok else colors.danger, size = 12.sp)
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(colors.bg3).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Txt(text, color = colors.muted, size = 10.sp, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, color: Color = colors.muted, background: Color = colors.bg3) {
    Box(Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Txt(text, color = color, size = 11.sp, bold = true, maxLines = 1)
    }
}

@Composable
fun KindBadge(kind: String) {
    val c = colors
    val (label, color) = when (kind) {
        ColumnKind.PARTITION_KEY -> "PK" to c.warn
        ColumnKind.CLUSTERING -> "CK" to c.accent
        ColumnKind.STATIC -> "S" to c.ok
        else -> return
    }
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 4.dp)) {
        Txt(label, color = color, size = 10.sp, bold = true)
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun <T> Tabs(tabs: List<Pair<T, String>>, active: T, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            tabs.forEach { (id, label) ->
                val selected = id == active
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(id) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Txt(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = if (selected) colors.accent else colors.muted, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.accent else Color.Transparent))
                }
            }
        }
        HLine()
    }
}

/** Standard padded page used by the keyspace, roles and cluster tabs. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/* ---------- Tables ---------- */

/** A cell of a [ListTable], `weight` being its share of the row. */
class ListColumn(val title: String, val weight: Float)

@Composable
fun ListTable(columns: List<ListColumn>, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Radius).border(1.dp, colors.border, Radius)) {
        if (columns.any { it.title.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().background(colors.bg2).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                columns.forEach { Txt(it.title, Modifier.weight(it.weight), color = colors.muted, size = 12.sp, bold = true, maxLines = 1) }
            }
            HLine()
        }
        content()
    }
}

@Composable
fun ListRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    HLine()
}

@Composable
fun EmptyListRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Muted(text, small = false) }
}

/** Long lists (settings, trace events) rendered lazily with a scrollbar. */
@Composable
fun <T> LazyList(items: List<T>, modifier: Modifier = Modifier, row: @Composable (Int, T) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state) {
            itemsIndexed(items) { i, item -> row(i, item) }
        }
        VScrollbar(rememberScrollbarAdapter(state))
    }
}

/* ---------- Async helpers ---------- */

val Throwable.text: String get() = message ?: toString()

/** Loads data with loading/error state; `reload` re-runs the loader. */
@Stable
class Loader<T> {
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    private var nonce by mutableIntStateOf(0)
    val generation get() = nonce
    fun reload() {
        nonce++
    }
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, loader: suspend () -> T): Loader<T> {
    val state = remember { Loader<T>() }
    // Changing keys or calling reload() cancels any in-flight load and starts over.
    LaunchedEffect(keys.toList(), state.generation) {
        state.loading = true
        state.error = null
        try {
            state.data = loader()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = e.text
        }
        state.loading = false
    }
    return state
}
