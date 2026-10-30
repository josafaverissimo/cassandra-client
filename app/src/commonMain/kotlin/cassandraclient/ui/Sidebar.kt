package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.Connection
import cassandraclient.core.Keyspace
import cassandraclient.core.TableSummary
import cassandraclient.core.ViewSummary
import kotlinx.coroutines.launch

private class KeyspaceMatch(val ks: Keyspace, val tables: List<TableSummary>, val views: List<ViewSummary>)

@Composable
fun Sidebar(
    connections: List<Connection>,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onSwitch: (String) -> Unit,
    onEdit: () -> Unit,
    onNew: () -> Unit,
    onDisconnect: () -> Unit,
    activeTab: String?,
) {
    val ws = LocalWorkspace.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(Storage.get("cc.showSystem") == "1") }
    var expanded by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var createKs by remember { mutableStateOf(false) }
    var createTableIn by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val q = search.trim().lowercase()
    val filtered = ws.keyspaces
        .filter { showSystem || !it.system }
        .map { k ->
            if (q.isEmpty() || k.name.lowercase().contains(q)) {
                KeyspaceMatch(k, k.tables, k.views)
            } else {
                KeyspaceMatch(k, k.tables.filter { it.name.lowercase().contains(q) }, k.views.filter { it.name.lowercase().contains(q) })
            }
        }
        .filter { q.isEmpty() || it.ks.name.lowercase().contains(q) || it.tables.isNotEmpty() || it.views.isNotEmpty() }

    fun isOpen(ks: Keyspace) = expanded[ks.name] ?: (q.isNotEmpty() || (!ks.system && filtered.size <= 3))

    Column(Modifier.width(SidebarWidth).fillMaxHeight().background(colors.bg2)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(Icons.Database, colors.accent, size = 20.dp)
                Txt("Cassandra Client", bold = true, size = 14.sp)
            }
            ConnectionSwitcher(connections, onSwitch, onEdit, onNew, onDisconnect)
        }
        HLine()
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            SideNavItem(Icons.Play, "New query", false) { ws.openTab(Tab.Query("Query")) }
            SideNavItem(Icons.Lock, "Roles & permissions", activeTab == Tab.Roles.key) { ws.openTab(Tab.Roles) }
            SideNavItem(Icons.Server, "Cluster", activeTab == Tab.Cluster.key) { ws.openTab(Tab.Cluster) }
        }
        HLine()
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Keyspaces", color = colors.muted, size = 12.sp, bold = true)
            Grow()
            IconBtn(Icons.Plus, { createKs = true })
            IconBtn(
                Icons.Refresh,
                {
                    scope.launch {
                        refreshing = true
                        try {
                            ws.refreshSchema()
                        } catch (e: Exception) {
                            toaster.error(e.text)
                        } finally {
                            refreshing = false
                        }
                    }
                },
                tint = if (refreshing) colors.accent else colors.muted,
            )
        }
        Input(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), placeholder = "Filter tables…", small = true)

        ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
            filtered.forEach { match ->
                key(match.ks.name) {
                    KeyspaceNode(
                        match,
                        open = isOpen(match.ks),
                        activeTab = activeTab,
                        onToggle = { expanded = expanded + (match.ks.name to !isOpen(match.ks)) },
                        onNewTable = { createTableIn = match.ks.name },
                    )
                }
            }
        }

        HLine()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                showSystem,
                {
                    showSystem = it
                    Storage.set("cc.showSystem", if (it) "1" else "0")
                },
                label = "Show system keyspaces",
                small = true,
            )
            Grow()
            LinkText(
                when (themeMode) {
                    "dark" -> "Dark"
                    "light" -> "Light"
                    else -> "Auto"
                },
                {
                    onThemeChange(
                        when (themeMode) {
                            "system" -> "dark"
                            "dark" -> "light"
                            else -> "system"
                        },
                    )
                },
                color = colors.muted,
                size = 11.sp,
            )
        }
    }

    if (createKs) CreateKeyspaceDialog(onClose = { createKs = false })
    createTableIn?.let { CreateTableDialog(it, onClose = { createTableIn = null }) }
}

@Composable
private fun ConnectionSwitcher(connections: List<Connection>, onSwitch: (String) -> Unit, onEdit: () -> Unit, onNew: () -> Unit, onDisconnect: () -> Unit) {
    val ws = LocalWorkspace.current
    var menuOpen by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorHeight = it.height }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Radius)
                .background(colors.bg)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { menuOpen = !menuOpen }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorDot(parseHexColor(ws.conn.color))
            Column(Modifier.weight(1f)) {
                Txt(ws.conn.name, bold = true, maxLines = 1)
                Muted("${ws.cluster.clusterName} · v${ws.cluster.nodes.firstOrNull()?.version.orEmpty()}", maxLines = 1)
            }
            Ico(Icons.CaretDown, colors.muted, size = 11.dp)
        }
        if (menuOpen) {
            Dropdown(anchorHeight, { menuOpen = false }) {
                MenuSurface(Modifier.width(SidebarWidth - 24.dp)) {
                    connections.forEach { c ->
                        MenuItem({
                            menuOpen = false
                            if (c.id != ws.conn.id) onSwitch(c.id)
                        }) {
                            ColorDot(parseHexColor(c.color))
                            Txt(c.name, Modifier.weight(1f), maxLines = 1)
                            if (c.id == ws.conn.id) Ico(Icons.Check, colors.muted, size = 12.dp)
                        }
                    }
                    HLine()
                    MenuItem({
                        menuOpen = false
                        onEdit()
                    }) { Txt("Edit this connection…") }
                    MenuItem({
                        menuOpen = false
                        onNew()
                    }) { Txt("New connection…") }
                    MenuItem({
                        menuOpen = false
                        onDisconnect()
                    }) { Txt("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun SideNavItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(Radius)
            .background(if (active) colors.accentSoft else if (hovered) colors.bg3 else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Ico(icon, if (active) colors.accent else colors.muted, size = 13.dp)
        Txt(label, color = if (active) colors.accent else colors.text, maxLines = 1)
    }
}

@Composable
private fun TreeRow(active: Boolean, indent: Int, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(if (active) colors.accentSoft else if (hovered) colors.bg3 else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction)
            .padding(start = indent.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun KeyspaceNode(match: KeyspaceMatch, open: Boolean, activeTab: String?, onToggle: () -> Unit, onNewTable: () -> Unit) {
    val ws = LocalWorkspace.current
    val ks = match.ks
    TreeRow(activeTab == keyspaceTabKey(ks.name), 6, { ws.openTab(Tab.KeyspaceTab(ks.name)) }) {
        IconBtn(if (open) Icons.CaretDown else Icons.CaretRight, onToggle, size = 18.dp)
        Ico(Icons.Database, colors.muted, size = 13.dp)
        Txt(ks.name, Modifier.weight(1f), maxLines = 1)
        if (ks.virtual) Tag("virtual")
        if (!ks.system) IconBtn(Icons.Plus, onNewTable, size = 18.dp)
    }
    if (open) {
        if (match.tables.isEmpty() && match.views.isEmpty()) {
            Muted("No tables", Modifier.padding(start = 44.dp, top = 2.dp, bottom = 4.dp))
        }
        match.tables.forEach { t ->
            key(t.name) {
                TreeRow(activeTab == tableTabKey(ks.name, t.name), 30, { ws.openTab(Tab.Table(ks.name, t.name)) }) {
                    Ico(Icons.Table, colors.muted, size = 13.dp)
                    Txt(t.name, maxLines = 1)
                }
            }
        }
        match.views.forEach { v ->
            key(v.name) {
                TreeRow(activeTab == tableTabKey(ks.name, v.name), 30, { ws.openTab(Tab.Table(ks.name, v.name)) }) {
                    Ico(Icons.View, colors.muted, size = 13.dp)
                    Txt(v.name, maxLines = 1)
                }
            }
        }
    }
}
