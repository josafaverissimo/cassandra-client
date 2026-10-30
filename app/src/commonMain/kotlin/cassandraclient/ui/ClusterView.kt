package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.Setting
import cassandraclient.core.plainToText
import kotlinx.serialization.json.JsonObject

private enum class ClusterTab { Nodes, Settings, Clients }

@Composable
fun ClusterView() {
    val ws = LocalWorkspace.current
    var tab by remember { mutableStateOf(ClusterTab.Nodes) }
    val cluster = rememberLoader(ws.connId) { ws.api.cluster(ws.connId) }
    val settings = rememberLoader(ws.connId, tab) { if (tab == ClusterTab.Settings) ws.api.settings(ws.connId) else null }
    val clients = rememberLoader(ws.connId, tab) { if (tab == ClusterTab.Clients) ws.api.clients(ws.connId) else null }
    val c = cluster.data ?: ws.cluster
    val dcs = c.nodes.map { it.datacenter }.distinct()
    val userKeyspaces = ws.keyspaces.filter { !it.system }

    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(Icons.Server, colors.muted, size = 16.dp)
                    Heading(c.clusterName)
                }
                Muted("${c.partitioner.substringAfterLast('.')} · CQL ${c.cqlVersion} · native protocol v${c.nativeProtocolVersion}")
            }
            Btn("Refresh", {
                cluster.reload()
                settings.reload()
                clients.reload()
            }, icon = Icons.Refresh)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(c.nodes.size.toString(), "nodes")
            Stat(dcs.size.toString(), "datacenters")
            Stat(userKeyspaces.size.toString(), "user keyspaces")
            Stat(userKeyspaces.sumOf { it.tables.size }.toString(), "user tables")
            Stat(if (c.auth.authenticationEnabled == true) "On" else "Off", "authentication", if (c.auth.authenticationEnabled == true) colors.ok else colors.warn)
        }

        Tabs(
            listOf(ClusterTab.Nodes to "Nodes", ClusterTab.Settings to "Settings", ClusterTab.Clients to "Connected clients"),
            tab,
            { tab = it },
        )

        when (tab) {
            ClusterTab.Nodes -> NodesTable(c.nodes)
            ClusterTab.Settings -> when {
                settings.loading -> Spinner()
                settings.error != null -> ErrorBox(settings.error!!, settings::reload)
                else -> SettingsList(settings.data.orEmpty())
            }
            ClusterTab.Clients -> when {
                clients.loading -> Spinner()
                clients.error != null -> ErrorBox(clients.error!!, clients::reload)
                else -> ClientsTable(clients.data.orEmpty())
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color = colors.text) {
    Column(
        Modifier
            .widthIn(min = 110.dp)
            .clip(Radius)
            .background(colors.bg2)
            .border(1.dp, colors.border, Radius)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Txt(value, color = color, size = 20.sp, bold = true)
        Muted(label)
    }
}

@Composable
private fun NodesTable(nodes: List<cassandraclient.core.NodeInfo>) {
    ListTable(
        listOf(
            ListColumn("Status", 1.6f),
            ListColumn("Address", 1.2f),
            ListColumn("Datacenter", 1f),
            ListColumn("Rack", 0.8f),
            ListColumn("Version", 0.7f),
            ListColumn("Tokens", 0.6f),
            ListColumn("Host ID", 2.4f),
        ),
    ) {
        nodes.forEach { n ->
            ListRow {
                Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorDot(
                        when (n.up) {
                            true -> colors.ok
                            false -> colors.danger
                            null -> colors.muted
                        },
                        size = 8.dp,
                    )
                    Txt(
                        when (n.up) {
                            true -> "Up"
                            false -> "Down"
                            null -> "?"
                        },
                    )
                    if (n.local) Tag("coordinator")
                }
                Txt(n.address, Modifier.weight(1.2f), mono = true, size = 12.sp, maxLines = 1)
                Txt(n.datacenter, Modifier.weight(1f), maxLines = 1)
                Txt(n.rack, Modifier.weight(0.8f), maxLines = 1)
                Txt(n.version, Modifier.weight(0.7f), maxLines = 1)
                Txt(n.tokens.toString(), Modifier.weight(0.6f), maxLines = 1)
                Muted(n.hostId, Modifier.weight(2.4f), mono = true, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SettingsList(settings: List<Setting>) {
    var q by remember { mutableStateOf("") }
    val needle = q.lowercase()
    val shown = settings.filter { needle.isEmpty() || it.name.contains(needle) || it.value.orEmpty().lowercase().contains(needle) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Input(q, { q = it }, Modifier.fillMaxWidth(), placeholder = "Search settings…")
        LazyList(shown, Modifier.fillMaxSize().clip(Radius).border(1.dp, colors.border, Radius)) { _, s ->
            ListRow {
                SelectionContainer(Modifier.weight(1f)) { Txt(s.name, mono = true, size = 12.sp) }
                SelectionContainer(Modifier.weight(1.4f)) { Muted(s.value ?: "null", mono = true) }
            }
        }
    }
}

@Composable
private fun ClientsTable(clients: List<JsonObject>) {
    if (clients.isEmpty()) {
        EmptyListRow("No connected clients.")
        return
    }
    val keys = clients.first().keys.toList()
    val widths = keys.map { key ->
        val longest = maxOf(key.length, clients.maxOf { plainToText(it[key] ?: kotlinx.serialization.json.JsonNull).length })
        (longest.coerceIn(6, 44) * 7 + 20).dp
    }
    Box(Modifier.fillMaxSize().clip(Radius).border(1.dp, colors.border, Radius)) {
        ScrollColumn(Modifier.fillMaxSize()) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(colors.bg2).padding(vertical = 6.dp)) {
                    keys.forEachIndexed { i, k -> Txt(k, Modifier.width(widths[i]).padding(horizontal = 10.dp), color = colors.muted, size = 12.sp, bold = true, maxLines = 1) }
                }
                clients.forEach { row ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        keys.forEachIndexed { i, k ->
                            Txt(plainToText(row[k] ?: kotlinx.serialization.json.JsonNull), Modifier.width(widths[i]).padding(horizontal = 10.dp), size = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
