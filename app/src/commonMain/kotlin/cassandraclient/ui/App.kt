package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.CassandraApi
import cassandraclient.core.ClusterInfo
import cassandraclient.core.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Root of both the desktop and the web app; only the [api] implementation differs. */
@Composable
fun App(api: CassandraApi) {
    var themeMode by remember { mutableStateOf(Storage.get("cc.theme") ?: "system") }
    AppTheme(themeMode) {
        CompositionLocalProvider(LocalApi provides api) {
            ToastHost {
                ConfirmHost {
                    Box(Modifier.fillMaxSize().background(colors.bg)) {
                        Root(
                            themeMode = themeMode,
                            onThemeChange = {
                                themeMode = it
                                Storage.set("cc.theme", it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Root(themeMode: String, onThemeChange: (String) -> Unit) {
    val api = LocalApi.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var connections by remember { mutableStateOf<List<Connection>?>(null) }
    var activeId by remember { mutableStateOf(Storage.sessionGet("cc.active")) }
    var editing by remember { mutableStateOf<Connection?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun loadConnections() {
        try {
            connections = api.connections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connections = connections ?: emptyList()
            toaster.error(e.text)
        }
    }
    LaunchedEffect(Unit) { loadConnections() }

    fun activate(id: String?) {
        activeId = id
        Storage.sessionSet("cc.active", id)
    }

    val active = connections?.find { it.id == activeId }
    if (active != null) {
        key(active.id) {
            ConnectedWorkspace(
                conn = active,
                connections = connections.orEmpty(),
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onSwitch = ::activate,
                onEdit = { editing = it },
                onNew = { creating = true },
                onDisconnect = {
                    scope.launch {
                        runCatching { api.disconnect(active.id) }
                        activate(null)
                        loadConnections()
                    }
                },
            )
        }
    } else {
        Welcome(connections, onOpen = ::activate, onEdit = { editing = it }, onNew = { creating = true })
    }

    if (editing != null || creating) {
        val isNew = creating
        ConnectionDialog(
            initial = editing,
            onClose = {
                editing = null
                creating = false
            },
            onSaved = { saved ->
                editing = null
                creating = false
                scope.launch {
                    loadConnections()
                    if (isNew) activate(saved.id)
                }
            },
            onDeleted = { id ->
                editing = null
                creating = false
                if (id == activeId) activate(null)
                scope.launch { loadConnections() }
            },
        )
    }
}

@Composable
private fun Welcome(connections: List<Connection>?, onOpen: (String) -> Unit, onEdit: (Connection) -> Unit, onNew: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ico(Icons.Database, colors.accent, size = 28.dp)
                Heading("Cassandra Client", size = 24.sp)
            }
            Muted("Choose a connection to browse data, run CQL and manage roles & permissions.", small = false)
            if (connections == null) {
                Spinner()
            } else {
                connections.forEach { c ->
                    key(c.id) { ConnectionCard(c, { onOpen(c.id) }, { onEdit(c) }) }
                }
                Btn("New connection", onNew, Modifier.fillMaxWidth(), icon = Icons.Plus)
            }
        }
    }
}

@Composable
private fun ConnectionCard(c: Connection, onOpen: () -> Unit, onEdit: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(if (hovered) colors.bg3 else colors.bg2)
            .border(1.dp, colors.border, Radius)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColorDot(parseHexColor(c.color), size = 12.dp)
        Column(Modifier.weight(1f)) {
            Txt(c.name, bold = true, maxLines = 1)
            Muted("${c.username?.let { "$it@" }.orEmpty()}${c.contactPoints.joinToString(",")}:${c.port}", mono = true, maxLines = 1)
        }
        IconBtn(Icons.Edit, onEdit)
    }
}

@Composable
private fun ConnectedWorkspace(
    conn: Connection,
    connections: List<Connection>,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onSwitch: (String) -> Unit,
    onEdit: (Connection) -> Unit,
    onNew: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val api = LocalApi.current
    var cluster by remember { mutableStateOf<ClusterInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var tabs by remember { mutableStateOf(listOf<Tab>()) }
    var activeTab by remember { mutableStateOf<String?>(null) }

    val openTab = remember {
        { tab: Tab ->
            if (tabs.none { it.key == tab.key }) tabs = tabs + tab
            activeTab = tab.key
        }
    }
    val closeTab = remember {
        { key: String ->
            val idx = tabs.indexOfFirst { it.key == key }
            if (idx >= 0) {
                val next = tabs.filterNot { it.key == key }
                tabs = next
                if (activeTab == key) activeTab = next.getOrNull(maxOf(0, idx - 1))?.key
            }
        }
    }
    var workspace by remember { mutableStateOf<Workspace?>(null) }

    LaunchedEffect(conn.id, attempt) {
        error = null
        try {
            coroutineScope {
                val c = async { api.connect(conn.id) }
                val ks = async { api.keyspaces(conn.id) }
                cluster = c.await()
                val existing = workspace
                if (existing != null) {
                    existing.keyspaces = ks.await()
                } else {
                    lateinit var ws: Workspace
                    ws = Workspace(
                        api = api,
                        conn = conn,
                        cluster = cluster!!,
                        keyspaces = ks.await(),
                        refreshSchema = { ws.keyspaces = api.keyspaces(conn.id) },
                        openTab = openTab,
                        closeTab = closeTab,
                    )
                    workspace = ws
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.text
        }
    }

    error?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 640.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Heading("Could not connect to ${conn.name}")
                ErrorBox(it, onRetry = { attempt++ })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Edit connection", { onEdit(conn) })
                    Btn("Back", onDisconnect)
                }
            }
        }
        return
    }

    val ws = workspace
    SideEffect { ws?.conn = conn }
    if (ws == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner("Connecting to ${conn.name}…") }
        return
    }

    CompositionLocalProvider(LocalWorkspace provides ws) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                connections = connections,
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onSwitch = onSwitch,
                onEdit = { onEdit(conn) },
                onNew = onNew,
                onDisconnect = onDisconnect,
                activeTab = activeTab,
            )
            VLine()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TabBar(tabs, activeTab, onActivate = { activeTab = it }, onClose = closeTab, onNew = { openTab(Tab.Query("Query")) })
                HLine()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tabs.isEmpty()) EmptyState()
                    KeepAlivePanels(tabs, { it.key }, { it.key == activeTab }, Modifier.fillMaxSize()) { t ->
                        RenderTab(t, t.key == activeTab)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabBar(tabs: List<Tab>, active: String?, onActivate: (String) -> Unit, onClose: (String) -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(colors.bg2), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                key(t.key) { TabChip(t, t.key == active, { onActivate(t.key) }, { onClose(t.key) }) }
            }
        }
        IconBtn(Icons.Plus, onNew, Modifier.padding(horizontal = 6.dp), size = 24.dp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabChip(t: Tab, active: Boolean, onActivate: () -> Unit, onClose: () -> Unit) {
    val icon = when (t) {
        is Tab.Table -> Icons.Table
        is Tab.KeyspaceTab -> Icons.Database
        is Tab.Query -> Icons.Play
        Tab.Roles -> Icons.Lock
        Tab.Cluster -> Icons.Server
    }
    Row(
        Modifier
            .height(36.dp)
            .widthIn(max = 240.dp)
            .background(if (active) colors.bg else Color.Transparent)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onActivate)
            .pointerInput(Unit) {
                // Middle click closes, as in the web version and in browsers.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Release && event.button == PointerButton.Tertiary) onClose()
                    }
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Ico(icon, if (active) colors.accent else colors.muted, size = 12.dp)
        Txt(t.title, Modifier.weight(1f, fill = false), color = if (active) colors.text else colors.muted, size = 12.sp, maxLines = 1)
        IconBtn(Icons.Close, onClose, size = 18.dp)
    }
    VLine()
}

@Composable
private fun RenderTab(t: Tab, active: Boolean) {
    when (t) {
        is Tab.Table -> TableView(t.keyspace, t.table)
        is Tab.KeyspaceTab -> KeyspaceView(t.keyspace)
        is Tab.Query -> QueryEditor(t.initialCql, t.keyspace, active)
        Tab.Roles -> RolesView()
        Tab.Cluster -> ClusterView()
    }
}

@Composable
private fun EmptyState() {
    val ws = LocalWorkspace.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionContainer { Heading(ws.cluster.clusterName, size = 20.sp) }
            Muted(
                "Cassandra ${ws.cluster.nodes.firstOrNull()?.version.orEmpty()} · ${ws.cluster.nodes.size} node(s) · " +
                    "${ws.keyspaces.count { !it.system }} keyspace(s)",
                small = false,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("New CQL query", { ws.openTab(Tab.Query("Query")) }, icon = Icons.Play)
                Btn("Roles & permissions", { ws.openTab(Tab.Roles) }, icon = Icons.Lock)
                Btn("Cluster overview", { ws.openTab(Tab.Cluster) }, icon = Icons.Server)
            }
            Muted("Pick a table in the sidebar to browse and edit its data.")
        }
    }
}

val SidebarWidth = 270.dp
