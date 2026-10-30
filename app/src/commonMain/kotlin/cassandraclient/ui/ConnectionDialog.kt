package cassandraclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import cassandraclient.core.Connection
import cassandraclient.core.ConnectionInput
import kotlinx.coroutines.launch

private val COLORS = listOf("#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#ec4899", "#14b8a6", "#64748b")

@Composable
fun ConnectionDialog(initial: Connection?, onClose: () -> Unit, onSaved: (Connection) -> Unit, onDeleted: (String) -> Unit) {
    val api = LocalApi.current
    val toaster = LocalToaster.current
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var hosts by remember { mutableStateOf(initial?.contactPoints?.joinToString(", ") ?: "127.0.0.1") }
    var port by remember { mutableStateOf((initial?.port ?: 9042).toString()) }
    var localDc by remember { mutableStateOf(initial?.localDataCenter.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var ssl by remember { mutableStateOf(initial?.ssl ?: false) }
    var color by remember { mutableStateOf(initial?.color ?: COLORS[0]) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun toInput() = ConnectionInput(
        id = initial?.id,
        name = name.trim().ifEmpty { hosts.split(",").first().trim() },
        contactPoints = hosts.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        port = port.toIntOrNull() ?: 9042,
        localDataCenter = localDc.trim().ifEmpty { null },
        username = username.trim().ifEmpty { null },
        // Leaving the password blank on an existing profile keeps the stored one.
        password = password.ifEmpty { if (initial?.hasPassword == true) null else "" },
        ssl = ssl,
        color = color,
    )

    Modal(
        title = if (initial != null) "Edit connection" else "New connection",
        onClose = onClose,
        footer = {
            if (initial != null) {
                Btn("Delete", {
                    scope.launch {
                        if (confirmer.confirm(ConfirmOptions("Delete connection", "Delete the saved connection “${initial.name}”?", danger = true, confirmLabel = "Delete"))) {
                            try {
                                api.deleteConnection(initial.id)
                                onDeleted(initial.id)
                            } catch (e: Exception) {
                                toaster.error(e.text)
                            }
                        }
                    }
                }, kind = BtnKind.DangerGhost)
                Grow()
            }
            Btn(if (testing) "Testing…" else "Test connection", {
                scope.launch {
                    testing = true
                    testResult = null
                    testResult = try {
                        val r = api.testConnection(toInput())
                        true to "Connected to “${r.clusterName}” — Cassandra ${r.version} (${r.datacenter})"
                    } catch (e: Exception) {
                        false to e.text
                    } finally {
                        testing = false
                    }
                }
            }, enabled = !testing)
            Btn("Save", {
                scope.launch {
                    saving = true
                    try {
                        onSaved(api.saveConnection(toInput()))
                    } catch (e: Exception) {
                        toaster.error(e.text)
                    } finally {
                        saving = false
                    }
                }
            }, kind = BtnKind.Primary, enabled = !saving && hosts.isNotBlank())
        },
    ) {
        Field("Name") { Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Production", autoFocus = true) }
        FormRow {
            Field("Contact points", Modifier.weight(1f)) { Input(hosts, { hosts = it }, Modifier.fillMaxWidth(), placeholder = "10.0.0.1, 10.0.0.2") }
            Field("Port", Modifier.weight(1f)) { Input(port, { port = it }, Modifier.fillMaxWidth(), numeric = true) }
        }
        Field("Local datacenter") { Input(localDc, { localDc = it }, Modifier.fillMaxWidth(), placeholder = "Auto-detect") }
        FormRow {
            Field("Username", Modifier.weight(1f)) { Input(username, { username = it }, Modifier.fillMaxWidth()) }
            Field("Password", Modifier.weight(1f)) {
                Input(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    password = true,
                    placeholder = if (initial?.hasPassword == true) "•••••• (unchanged)" else "",
                )
            }
        }
        Checkbox(ssl, { ssl = it }, label = "Use SSL/TLS")
        Field("Color") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COLORS.forEach { c ->
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(c))
                            .border(2.dp, if (color == c) colors.text else Color.Transparent, CircleShape)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { color = c }
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
        testResult?.let { (ok, text) -> TestResult(ok, text) }
    }
}
