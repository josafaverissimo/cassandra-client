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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cassandraclient.core.APPLICABLE
import cassandraclient.core.PERMISSIONS
import cassandraclient.core.Permission
import cassandraclient.core.Role
import cassandraclient.core.applicableFor
import cassandraclient.core.qualified
import cassandraclient.core.quoteId
import cassandraclient.core.quoteStr
import cassandraclient.core.resourceLabel
import kotlinx.coroutines.launch

@Composable
fun RolesView() {
    val ws = LocalWorkspace.current
    val auth = rememberLoader(ws.connId) { ws.api.auth(ws.connId) }
    val roles = rememberLoader(ws.connId) { ws.api.roles(ws.connId) }
    var selected by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(roles.data, selected) {
        val data = roles.data
        if (selected == null && !data.isNullOrEmpty()) {
            selected = data.find { it.name == ws.conn.username }?.name ?: data.first().name
        }
    }

    val authData = auth.data
    val authDisabled = authData != null && (authData.authenticationEnabled == false || authData.authorizationEnabled == false)
    val role = roles.data?.find { it.name == selected }
    val shown = roles.data.orEmpty().filter { it.name.contains(search, ignoreCase = true) }

    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(Icons.Lock, colors.muted, size = 16.dp)
                    Heading("Roles & permissions")
                }
                if (authData != null) Muted("authenticator ${authData.authenticator.orEmpty()} · authorizer ${authData.authorizer.orEmpty()}", maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("", roles::reload, icon = Icons.Refresh)
                Btn("New role", { creating = true }, kind = BtnKind.Primary, enabled = roles.data != null, icon = Icons.Plus)
            }
        }

        if (authDisabled) {
            Column(Modifier.fillMaxWidth().clip(Radius).background(colors.warnSoft).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Authentication/authorization is disabled on this cluster.", bold = true)
                Txt("Roles and permissions can't be managed (or aren't enforced). To enable them, set in cassandra.yaml on every node:")
                CodeBlock("authenticator: PasswordAuthenticator\nauthorizer: CassandraAuthorizer\nrole_manager: CassandraRoleManager")
                Txt("Then restart the nodes and connect with the default cassandra / cassandra superuser (change its password right away).")
            }
        }

        if (roles.loading && roles.data == null) Spinner()
        val rolesError = roles.error
        if (rolesError != null && !authDisabled) ErrorBox(rolesError, roles::reload)

        val data = roles.data
        if (data != null) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(240.dp).fillMaxHeight().clip(Radius).border(1.dp, colors.border, Radius).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Input(search, { search = it }, Modifier.fillMaxWidth(), placeholder = "Search roles…", small = true)
                    ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                        shown.forEach { r ->
                            key(r.name) { RoleItem(r, r.name == selected, r.name == ws.conn.username) { selected = r.name } }
                        }
                    }
                    Muted("★ superuser · person: can login · people: group role")
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (role != null) {
                        key(role.name) {
                            RoleDetail(
                                role = role,
                                roles = data,
                                authorizationEnabled = authData?.authorizationEnabled != false,
                                onChanged = roles::reload,
                                onDropped = {
                                    selected = null
                                    roles.reload()
                                },
                            )
                        }
                    } else {
                        EmptyListRow("Select a role.")
                    }
                }
            }
        }
    }

    if (creating && roles.data != null) {
        RoleDialog(
            existing = null,
            roles = roles.data.orEmpty(),
            onClose = { creating = false },
            onDone = { name ->
                creating = false
                roles.reload()
                selected = name
            },
        )
    }
}

@Composable
private fun RoleItem(role: Role, active: Boolean, isYou: Boolean, onClick: () -> Unit) {
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
        Ico(if (role.superuser) Icons.Star else if (role.login) Icons.User else Icons.Users, if (role.superuser) colors.warn else colors.muted, size = 13.dp)
        Txt(role.name, Modifier.weight(1f), color = if (active) colors.accent else colors.text, maxLines = 1)
        if (isYou) Tag("you")
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Radius).border(1.dp, colors.border, Radius).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(title, bold = true, size = 14.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleDetail(role: Role, roles: List<Role>, authorizationEnabled: Boolean, onChanged: () -> Unit, onDropped: () -> Unit) {
    val ws = LocalWorkspace.current
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner(maskPasswords = true, refreshSchema = false)
    val perms = rememberLoader(ws.connId, role.name, authorizationEnabled) {
        if (authorizationEnabled) ws.api.permissions(ws.connId, role.name) else null
    }
    var editing by remember { mutableStateOf(false) }
    var addRole by remember { mutableStateOf("") }
    val members = roles.filter { role.name in it.memberOf }
    val r = quoteStr(role.name)

    ScrollColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading(role.name, size = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (role.superuser) Pill("SUPERUSER", colors.warn, colors.warnSoft)
                    Pill(if (role.login) "LOGIN" else "NOLOGIN", if (role.login) colors.ok else colors.muted)
                    role.datacenters?.let { Pill("DCs: $it") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Edit…", { editing = true }, size = BtnSize.Sm)
                Btn("Drop", {
                    scope.launch {
                        val cql = "DROP ROLE $r;"
                        if (confirmer.confirm(ConfirmOptions("Drop role ${role.name}", "The role, its grants and memberships will be removed.", cql, "Drop role", danger = true, typeToConfirm = role.name))) {
                            if (exec(cql, "Role dropped")) onDropped()
                        }
                    }
                }, size = BtnSize.Sm, kind = BtnKind.DangerGhost, enabled = role.name != ws.conn.username)
            }
        }

        Box(Modifier.padding(end = 12.dp)) {
            Card("Member of") {
                Muted("Roles granted to ${role.name}; their permissions are inherited.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    role.memberOf.forEach { m ->
                        Chip(m) {
                            scope.launch {
                                val cql = "REVOKE ${quoteStr(m)} FROM $r;"
                                if (confirmer.confirm(ConfirmOptions("Revoke role", cql = cql, confirmLabel = "Revoke", danger = true))) {
                                    if (exec(cql, "Role revoked")) {
                                        onChanged()
                                        perms.reload()
                                    }
                                }
                            }
                        }
                    }
                    if (role.memberOf.isEmpty()) Muted("None")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = listOf("") + roles.filter { it.name != role.name && it.name !in role.memberOf }.map { it.name }
                    Select(addRole, options, { addRole = it }, Modifier.width(220.dp), label = { it.ifEmpty { "Grant role…" } }, small = true)
                    Btn("Grant", {
                        scope.launch {
                            if (exec("GRANT ${quoteStr(addRole)} TO $r;", "Role granted")) {
                                addRole = ""
                                onChanged()
                                perms.reload()
                            }
                        }
                    }, size = BtnSize.Sm, enabled = addRole.isNotEmpty())
                }
                if (members.isNotEmpty()) Muted("Granted to: ${members.joinToString(", ") { it.name }}")
            }
        }

        Box(Modifier.padding(end = 12.dp)) {
            Card("Permissions") {
                if (role.superuser) {
                    Txt("Superusers implicitly hold every permission on every resource.", Modifier.fillMaxWidth().clip(Radius).background(colors.accentSoft).padding(8.dp), size = 12.sp)
                }
                if (!authorizationEnabled) Muted("Permissions are unavailable while the authorizer is AllowAllAuthorizer.")
                if (authorizationEnabled && perms.loading && perms.data == null) Spinner()
                perms.error?.let { ErrorBox(it, perms::reload) }
                perms.data?.let { PermissionMatrix(role, it, roles, perms::reload) }
            }
        }
    }

    if (editing) {
        RoleDialog(
            existing = role,
            roles = roles,
            onClose = { editing = false },
            onDone = {
                editing = false
                onChanged()
            },
        )
    }
}

@Composable
private fun Chip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(colors.bg3).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Txt(text, size = 12.sp, maxLines = 1)
        IconBtn(Icons.Close, onRemove, size = 18.dp)
    }
}

/* ---------- Permission matrix ---------- */

private data class ResourceRow(val resourceCql: String, val label: String)

private val ResourceColumnWidth = 220.dp
private val PermColumnWidth = 66.dp
private val ActionsColumnWidth = 190.dp

@Composable
private fun PermissionMatrix(role: Role, permissions: List<Permission>, roles: List<Role>, onChanged: () -> Unit) {
    val confirmer = LocalConfirmer.current
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner(maskPasswords = true, refreshSchema = false)
    var extraRows by remember { mutableStateOf(listOf<ResourceRow>()) }

    /** Staged changes: `${resourceCql}|${permission}` → true (grant) / false (revoke). */
    var staged by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var adding by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    val direct = remember(permissions) { permissions.filter { !it.inherited }.map { "${it.resourceCql}|${it.permission}" }.toSet() }
    val inherited = remember(permissions) {
        permissions.filter { it.inherited }.groupBy({ "${it.resourceCql}|${it.permission}" }, { it.grantedTo })
    }
    val rows = remember(permissions, extraRows) {
        val seen = LinkedHashMap<String, ResourceRow>()
        permissions.forEach { p -> seen.getOrPut(p.resourceCql) { ResourceRow(p.resourceCql, resourceLabel(p.resource)) } }
        extraRows.forEach { r -> seen.getOrPut(r.resourceCql) { r } }
        seen.values.toList()
    }

    fun isOn(k: String) = staged[k] ?: (k in direct)

    fun toggle(k: String) {
        val target = !isOn(k)
        staged = if (target == (k in direct)) staged - k else staged + (k to target)
    }

    fun setRow(res: String, on: Boolean) {
        var next = staged
        applicableFor(res).forEach { p ->
            val k = "$res|$p"
            next = if (on == (k in direct)) next - k else next + (k to on)
        }
        staged = next
    }

    val roleLit = quoteStr(role.name)
    val statements = staged.map { (k, grant) ->
        val res = k.substringBefore('|')
        val perm = k.substringAfter('|')
        if (grant) "GRANT $perm ON $res TO $roleLit;" else "REVOKE $perm ON $res FROM $roleLit;"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val matrixScroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().clip(Radius).border(1.dp, colors.border, Radius)) {
        Column(Modifier.fillMaxWidth().horizontalScroll(matrixScroll).padding(bottom = 10.dp)) {
            Row(Modifier.background(colors.bg2).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Resource", Modifier.width(ResourceColumnWidth).padding(horizontal = 8.dp), color = colors.muted, size = 11.sp, bold = true)
                PERMISSIONS.forEach { p ->
                    Txt(p.replace("_", " "), Modifier.width(PermColumnWidth), color = colors.muted, size = 9.sp, bold = true)
                }
                HSpacer(ActionsColumnWidth)
            }
            rows.forEach { row ->
                key(row.resourceCql) {
                    val applicable = applicableFor(row.resourceCql)
                    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(row.label, Modifier.width(ResourceColumnWidth).padding(horizontal = 8.dp), mono = true, size = 11.sp, maxLines = 1)
                        PERMISSIONS.forEach { p ->
                            val k = "${row.resourceCql}|$p"
                            if (p !in applicable) {
                                Box(Modifier.width(PermColumnWidth).fillMaxHeight().background(colors.bg3))
                            } else {
                                val from = inherited[k]
                                Row(
                                    Modifier.width(PermColumnWidth).fillMaxHeight().background(if (k in staged) colors.changed else Color.Transparent).padding(start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Checkbox(isOn(k), { toggle(k) })
                                    if (from != null && !isOn(k)) Txt("●", color = colors.accent, size = 9.sp)
                                }
                            }
                        }
                        Row(Modifier.width(ActionsColumnWidth).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Btn("All", { setRow(row.resourceCql, true) }, size = BtnSize.Xs)
                            Btn("None", { setRow(row.resourceCql, false) }, size = BtnSize.Xs)
                            if (permissions.any { !it.inherited && it.resourceCql == row.resourceCql }) {
                                Btn("Revoke all", {
                                    scope.launch {
                                        val cql = "REVOKE ALL PERMISSIONS ON ${row.resourceCql} FROM $roleLit;"
                                        if (confirmer.confirm(ConfirmOptions("Revoke all permissions", cql = cql, confirmLabel = "Revoke all", danger = true))) {
                                            if (exec(cql, "Permissions revoked")) onChanged()
                                        }
                                    }
                                }, size = BtnSize.Xs, kind = BtnKind.DangerGhost)
                            }
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                Muted("No permissions granted. Add a resource to grant access.", Modifier.padding(16.dp), small = false)
            }
        }
        HScrollbar(rememberScrollbarAdapter(matrixScroll))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Add resource", { adding = true }, size = BtnSize.Sm, icon = Icons.Plus)
            Muted("● inherited from a granted role · greyed cells don't apply to the resource")
        }
        if (statements.isNotEmpty()) {
            CodeBlock(statements.joinToString("\n"), maxHeight = 180.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Discard", { staged = emptyMap() }, size = BtnSize.Sm, enabled = !applying)
                Btn("Apply ${statements.size} change(s)", {
                    scope.launch {
                        applying = true
                        val ok = exec(statements.joinToString("\n"), "${statements.size} permission change(s) applied")
                        applying = false
                        if (ok) {
                            staged = emptyMap()
                            onChanged()
                        }
                    }
                }, size = BtnSize.Sm, kind = BtnKind.Primary, enabled = !applying)
            }
        }
    }
    if (adding) {
        ResourcePicker(roles, onClose = { adding = false }) { row ->
            extraRows = extraRows + row
            adding = false
        }
    }
}

@Composable
private fun ResourcePicker(roles: List<Role>, onClose: () -> Unit, onPick: (ResourceRow) -> Unit) {
    val ws = LocalWorkspace.current
    val userKs = ws.keyspaces.filter { !it.virtual }
    var kind by remember { mutableStateOf("KEYSPACE") }
    var ks by remember { mutableStateOf(userKs.firstOrNull { !it.system }?.name ?: userKs.firstOrNull()?.name.orEmpty()) }
    var table by remember { mutableStateOf("") }
    var roleName by remember { mutableStateOf(roles.firstOrNull()?.name.orEmpty()) }
    val tables = userKs.find { it.name == ks }?.tables.orEmpty()
    val effectiveTable = table.ifEmpty { tables.firstOrNull()?.name.orEmpty() }

    val picked: ResourceRow? = when (kind) {
        "KEYSPACE" -> if (ks.isNotEmpty()) ResourceRow("KEYSPACE ${quoteId(ks)}", "keyspace $ks") else null
        "TABLE" -> if (ks.isNotEmpty() && effectiveTable.isNotEmpty()) ResourceRow("TABLE ${qualified(ks, effectiveTable)}", "table $ks.$effectiveTable") else null
        "ROLE" -> if (roleName.isNotEmpty()) ResourceRow("ROLE ${quoteStr(roleName)}", "role $roleName") else null
        "ALL FUNCTIONS IN KEYSPACE" -> if (ks.isNotEmpty()) ResourceRow("ALL FUNCTIONS IN KEYSPACE ${quoteId(ks)}", "all functions in $ks") else null
        else -> ResourceRow(kind, kind.lowercase())
    }

    Modal(
        title = "Add resource",
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn("Add to matrix", { picked?.let(onPick) }, kind = BtnKind.Primary, enabled = picked != null)
        },
    ) {
        Field("Resource type") { Select(kind, APPLICABLE.keys.toList(), { kind = it }, Modifier.fillMaxWidth()) }
        FormRow {
            if (kind == "KEYSPACE" || kind == "TABLE" || kind == "ALL FUNCTIONS IN KEYSPACE") {
                Field("Keyspace", Modifier.weight(1f)) {
                    Select(ks, userKs.map { it.name }, {
                        ks = it
                        table = ""
                    }, Modifier.fillMaxWidth())
                }
            }
            if (kind == "TABLE") {
                Field("Table", Modifier.weight(1f)) { Select(effectiveTable, tables.map { it.name }, { table = it }, Modifier.fillMaxWidth()) }
            }
            if (kind == "ROLE") {
                Field("Role", Modifier.weight(1f)) { Select(roleName, roles.map { it.name }, { roleName = it }, Modifier.fillMaxWidth()) }
            }
        }
        picked?.let { Muted("Applicable permissions: ${applicableFor(it.resourceCql).joinToString(", ")}") }
    }
}

/* ---------- Create / edit role ---------- */

private enum class DcMode(val id: String) { Keep("keep"), All("all"), Some("some") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleDialog(existing: Role?, roles: List<Role>, onClose: () -> Unit, onDone: (String) -> Unit) {
    val ws = LocalWorkspace.current
    val scope = rememberCoroutineScope()
    val exec = rememberCqlRunner(maskPasswords = true, refreshSchema = false)
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var login by remember { mutableStateOf(existing?.login ?: true) }
    var superuser by remember { mutableStateOf(existing?.superuser ?: false) }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var dcMode by remember { mutableStateOf(if (existing != null) DcMode.Keep else DcMode.All) }
    var dcs by remember { mutableStateOf(listOf<String>()) }
    var grantRoles by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }

    val opts = buildList {
        if (existing == null || existing.login != login) add("LOGIN = $login")
        if (existing == null || existing.superuser != superuser) add("SUPERUSER = $superuser")
        if (password.isNotEmpty()) add("PASSWORD = ${quoteStr(password)}")
        if (dcMode == DcMode.All && (existing == null || existing.datacenters != "ALL")) add("ACCESS TO ALL DATACENTERS")
        if (dcMode == DcMode.Some && dcs.isNotEmpty()) add("ACCESS TO DATACENTERS {${dcs.joinToString(", ") { quoteStr(it) }}}")
    }
    val roleLit = quoteStr(existing?.name ?: name.trim().ifEmpty { "new_role" })
    val withOpts = if (opts.isNotEmpty()) " WITH ${opts.joinToString(" AND ")}" else ""
    val statements = buildList {
        if (existing != null) {
            if (opts.isNotEmpty()) add("ALTER ROLE $roleLit$withOpts;")
        } else {
            add("CREATE ROLE $roleLit$withOpts;")
        }
        grantRoles.forEach { add("GRANT ${quoteStr(it)} TO $roleLit;") }
    }
    val preview = maskPassword(statements.joinToString("\n"))
    val mismatch = password != password2

    Modal(
        title = existing?.let { "Edit role ${it.name}" } ?: "New role",
        onClose = onClose,
        footer = {
            Btn("Cancel", onClose)
            Btn(
                if (existing != null) "Save" else "Create role",
                {
                    scope.launch {
                        busy = true
                        val ok = exec(statements.joinToString("\n"), if (existing != null) "Role updated" else "Role created")
                        busy = false
                        if (ok) onDone(existing?.name ?: name.trim())
                    }
                },
                kind = BtnKind.Primary,
                enabled = !busy && (existing != null || name.isNotBlank()) && !mismatch && statements.isNotEmpty(),
            )
        },
    ) {
        if (existing == null) {
            Field("Role name") { Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "app_reader", autoFocus = true) }
        }
        FormRow {
            Checkbox(login, { login = it }, Modifier.weight(1f), label = "Can login (user)")
            Checkbox(superuser, { superuser = it }, Modifier.weight(1f), label = "Superuser")
        }
        FormRow {
            Field(if (existing != null) "New password" else "Password", Modifier.weight(1f)) {
                Input(password, { password = it }, Modifier.fillMaxWidth(), password = true, placeholder = if (existing != null) "unchanged" else "")
            }
            Field("Confirm password", Modifier.weight(1f)) {
                Input(password2, { password2 = it }, Modifier.fillMaxWidth(), password = true)
            }
        }
        if (mismatch && password2.isNotEmpty()) TestResult(false, "Passwords don't match")
        Field("Datacenter access") {
            Select(
                dcMode,
                if (existing != null) DcMode.entries else listOf(DcMode.All, DcMode.Some),
                { dcMode = it },
                Modifier.fillMaxWidth(),
                label = {
                    when (it) {
                        DcMode.Keep -> "Keep current (${existing?.datacenters ?: "ALL"})"
                        DcMode.All -> "All datacenters"
                        DcMode.Some -> "Only selected datacenters"
                    }
                },
            )
        }
        if (dcMode == DcMode.Some) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ws.datacenters.forEach { dc ->
                    Checkbox(dc in dcs, { on -> dcs = if (on) dcs + dc else dcs - dc }, label = dc)
                }
            }
        }
        if (existing == null && roles.isNotEmpty()) {
            Field("Grant roles") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    roles.forEach { r ->
                        Checkbox(r.name in grantRoles, { on -> grantRoles = if (on) grantRoles + r.name else grantRoles - r.name }, label = r.name)
                    }
                }
            }
        }
        if (preview.isNotEmpty()) CodeBlock(preview)
    }
}
