import { useEffect, useMemo, useState } from "react";
import { api, run, type Permission, type Role } from "../api";
import { applicableFor, APPLICABLE, PERMISSIONS, qualified, quoteId, quoteStr, resourceLabel, type ResourceKind } from "../cql";
import { useWorkspace } from "../state";
import { CodeBlock, ErrorBox, Modal, Spinner, useConfirm, useLoader, useToast } from "./ui";

export function RolesView() {
  const ws = useWorkspace();
  const auth = useLoader(() => api.auth(ws.conn.id), [ws.conn.id]);
  const roles = useLoader(() => api.roles(ws.conn.id), [ws.conn.id]);
  const [selected, setSelected] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (!selected && roles.data?.length) setSelected(roles.data.find((r) => r.name === ws.conn.username)?.name ?? roles.data[0]!.name);
  }, [roles.data, selected, ws.conn.username]);

  const authDisabled = auth.data && (auth.data.authenticationEnabled === false || auth.data.authorizationEnabled === false);
  const role = roles.data?.find((r) => r.name === selected) ?? null;
  const shown = (roles.data ?? []).filter((r) => r.name.toLowerCase().includes(search.toLowerCase()));

  return (
    <div className="page roles-page">
      <div className="page-head">
        <div>
          <h2>🔐 Roles &amp; permissions</h2>
          <div className="muted small">
            {auth.data && (
              <>
                authenticator <span className="mono">{auth.data.authenticator}</span> · authorizer{" "}
                <span className="mono">{auth.data.authorizer}</span>
              </>
            )}
          </div>
        </div>
        <div className="row gap">
          <button className="btn" onClick={() => roles.reload()}>
            ⟳
          </button>
          <button className="btn btn-primary" onClick={() => setCreating(true)} disabled={!roles.data}>
            + New role
          </button>
        </div>
      </div>

      {authDisabled && (
        <div className="banner warn-banner">
          <div>
            <b>Authentication/authorization is disabled on this cluster.</b> Roles and permissions can't be managed (or aren't
            enforced). To enable them, set in <span className="mono">cassandra.yaml</span> on every node:
          </div>
          <CodeBlock code={"authenticator: PasswordAuthenticator\nauthorizer: CassandraAuthorizer\nrole_manager: CassandraRoleManager"} />
          <div>
            Then restart the nodes and connect with the default <span className="mono">cassandra / cassandra</span> superuser (change
            its password right away).
          </div>
        </div>
      )}

      {roles.loading && !roles.data && <Spinner />}
      {roles.error && !authDisabled && <ErrorBox error={roles.error} onRetry={roles.reload} />}

      {roles.data && (
        <div className="roles-layout">
          <div className="roles-list">
            <input placeholder="Search roles…" value={search} onChange={(e) => setSearch(e.target.value)} />
            {shown.map((r) => (
              <button key={r.name} className={`role-item ${r.name === selected ? "active" : ""}`} onClick={() => setSelected(r.name)}>
                <span className="role-ico">{r.superuser ? "★" : r.login ? "👤" : "👥"}</span>
                <span className="role-name">{r.name}</span>
                {r.name === ws.conn.username && <span className="tag">you</span>}
              </button>
            ))}
            <div className="muted small pad-s">★ superuser · 👤 can login (user) · 👥 group role</div>
          </div>
          <div className="role-detail">
            {role ? (
              <RoleDetail
                key={role.name}
                authorizationEnabled={auth.data?.authorizationEnabled !== false}
                role={role}
                roles={roles.data}
                onChanged={roles.reload}
                onDropped={() => {
                  setSelected(null);
                  roles.reload();
                }}
              />
            ) : (
              <div className="muted center pad">Select a role.</div>
            )}
          </div>
        </div>
      )}

      {creating && roles.data && (
        <RoleDialog
          roles={roles.data}
          onClose={() => setCreating(false)}
          onDone={(name) => {
            setCreating(false);
            roles.reload();
            setSelected(name);
          }}
        />
      )}
    </div>
  );
}

function useExec() {
  const ws = useWorkspace();
  const toast = useToast();
  return async (cql: string, ok: string) => {
    try {
      await run(ws.conn.id, cql);
      toast({ kind: "ok", message: ok, detail: cql.replace(/PASSWORD = '[^']*(?:''[^']*)*'/g, "PASSWORD = '•••'") });
      return true;
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
      return false;
    }
  };
}

function RoleDetail({
  role,
  roles,
  authorizationEnabled,
  onChanged,
  onDropped,
}: {
  role: Role;
  roles: Role[];
  authorizationEnabled: boolean;
  onChanged: () => void;
  onDropped: () => void;
}) {
  const ws = useWorkspace();
  const confirm = useConfirm();
  const exec = useExec();
  const perms = useLoader(
    () => (authorizationEnabled ? api.permissions(ws.conn.id, role.name) : Promise.resolve(null)),
    [ws.conn.id, role.name, authorizationEnabled],
  );
  const [editing, setEditing] = useState(false);
  const [addRole, setAddRole] = useState("");
  const members = roles.filter((r) => r.memberOf.includes(role.name));
  const r = quoteStr(role.name);

  const drop = async () => {
    const cql = `DROP ROLE ${r};`;
    if (await confirm({ title: `Drop role ${role.name}`, message: "The role, its grants and memberships will be removed.", cql, danger: true, confirmLabel: "Drop role", typeToConfirm: role.name }))
      if (await exec(cql, "Role dropped")) onDropped();
  };

  return (
    <div>
      <div className="role-head">
        <div>
          <h3>{role.name}</h3>
          <div className="row gap-s">
            {role.superuser && <span className="pill pill-super">SUPERUSER</span>}
            <span className={`pill ${role.login ? "pill-on" : ""}`}>{role.login ? "LOGIN" : "NOLOGIN"}</span>
            {role.datacenters && <span className="pill">DCs: {role.datacenters}</span>}
          </div>
        </div>
        <div className="row gap">
          <button className="btn btn-sm" onClick={() => setEditing(true)}>
            Edit…
          </button>
          <button className="btn btn-sm btn-danger-ghost" onClick={drop} disabled={role.name === ws.conn.username}>
            Drop
          </button>
        </div>
      </div>

      <section className="card">
        <h4>Member of</h4>
        <p className="muted small">Roles granted to {role.name}; their permissions are inherited.</p>
        <div className="chips">
          {role.memberOf.map((m) => (
            <span key={m} className="chip">
              {m}
              <button
                title={`Revoke ${m}`}
                onClick={async () => {
                  const cql = `REVOKE ${quoteStr(m)} FROM ${r};`;
                  if (await confirm({ title: "Revoke role", cql, confirmLabel: "Revoke", danger: true }))
                    if (await exec(cql, "Role revoked")) (onChanged(), perms.reload());
                }}
              >
                ✕
              </button>
            </span>
          ))}
          {role.memberOf.length === 0 && <span className="muted small">None</span>}
        </div>
        <div className="row gap mt">
          <select value={addRole} onChange={(e) => setAddRole(e.target.value)}>
            <option value="">Grant role…</option>
            {roles
              .filter((x) => x.name !== role.name && !role.memberOf.includes(x.name))
              .map((x) => (
                <option key={x.name}>{x.name}</option>
              ))}
          </select>
          <button
            className="btn btn-sm"
            disabled={!addRole}
            onClick={async () => {
              if (await exec(`GRANT ${quoteStr(addRole)} TO ${r};`, "Role granted")) {
                setAddRole("");
                onChanged();
                perms.reload();
              }
            }}
          >
            Grant
          </button>
        </div>
        {members.length > 0 && (
          <p className="small mt">
            <span className="muted">Granted to: </span>
            {members.map((m) => m.name).join(", ")}
          </p>
        )}
      </section>

      <section className="card">
        <h4>Permissions</h4>
        {role.superuser && <p className="banner info-banner small">Superusers implicitly hold every permission on every resource.</p>}
        {!authorizationEnabled && <p className="muted small">Permissions are unavailable while the authorizer is AllowAllAuthorizer.</p>}
        {authorizationEnabled && perms.loading && !perms.data && <Spinner />}
        {perms.error && <ErrorBox error={perms.error} onRetry={perms.reload} />}
        {perms.data && <PermissionMatrix role={role} permissions={perms.data} roles={roles} onChanged={perms.reload} />}
      </section>

      {editing && (
        <RoleDialog
          existing={role}
          roles={roles}
          onClose={() => setEditing(false)}
          onDone={() => {
            setEditing(false);
            onChanged();
          }}
        />
      )}
    </div>
  );
}

/* ---------- Permission matrix ---------- */

interface ResourceRow {
  resourceCql: string;
  label: string;
}

function PermissionMatrix({
  role,
  permissions,
  roles,
  onChanged,
}: {
  role: Role;
  permissions: Permission[];
  roles: Role[];
  onChanged: () => void;
}) {
  const confirm = useConfirm();
  const exec = useExec();
  const [extraRows, setExtraRows] = useState<ResourceRow[]>([]);
  /** Staged changes: `${resourceCql}|${permission}` → true (grant) / false (revoke). */
  const [staged, setStaged] = useState<Record<string, boolean>>({});
  const [adding, setAdding] = useState(false);
  const [applying, setApplying] = useState(false);

  const direct = useMemo(() => new Set(permissions.filter((p) => !p.inherited).map((p) => `${p.resourceCql}|${p.permission}`)), [permissions]);
  const inherited = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const p of permissions.filter((p) => p.inherited)) {
      const k = `${p.resourceCql}|${p.permission}`;
      m.set(k, [...(m.get(k) ?? []), p.grantedTo]);
    }
    return m;
  }, [permissions]);

  const rows: ResourceRow[] = useMemo(() => {
    const seen = new Map<string, ResourceRow>();
    for (const p of permissions) if (!seen.has(p.resourceCql)) seen.set(p.resourceCql, { resourceCql: p.resourceCql, label: resourceLabel(p.resource) });
    for (const r of extraRows) if (!seen.has(r.resourceCql)) seen.set(r.resourceCql, r);
    return [...seen.values()];
  }, [permissions, extraRows]);

  const isOn = (k: string) => (k in staged ? staged[k]! : direct.has(k));
  const toggle = (k: string) =>
    setStaged((s) => {
      const next = { ...s };
      const target = !isOn(k);
      if (target === direct.has(k)) delete next[k];
      else next[k] = target;
      return next;
    });

  const statements = Object.entries(staged).map(([k, grant]) => {
    const [res, perm] = k.split("|") as [string, string];
    return grant ? `GRANT ${perm} ON ${res} TO ${quoteStr(role.name)};` : `REVOKE ${perm} ON ${res} FROM ${quoteStr(role.name)};`;
  });

  const apply = async () => {
    setApplying(true);
    const ok = await exec(statements.join("\n"), `${statements.length} permission change(s) applied`);
    setApplying(false);
    if (ok) {
      setStaged({});
      onChanged();
    }
  };

  const setRow = (res: string, on: boolean) =>
    setStaged((s) => {
      const next = { ...s };
      for (const p of applicableFor(res)) {
        const k = `${res}|${p}`;
        if (on === direct.has(k)) delete next[k];
        else next[k] = on;
      }
      return next;
    });

  const revokeAll = async (res: string) => {
    const cql = `REVOKE ALL PERMISSIONS ON ${res} FROM ${quoteStr(role.name)};`;
    if (await confirm({ title: "Revoke all permissions", cql, danger: true, confirmLabel: "Revoke all" })) if (await exec(cql, "Permissions revoked")) onChanged();
  };

  return (
    <div>
      <div className="matrix-scroll">
        <table className="matrix">
          <thead>
            <tr>
              <th>Resource</th>
              {PERMISSIONS.map((p) => (
                <th key={p} className="perm-col">
                  <span>{p.replace("_", " ")}</span>
                </th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const applicable = applicableFor(row.resourceCql);
              return (
                <tr key={row.resourceCql}>
                  <td className="mono small res-cell" title={row.resourceCql}>
                    {row.label}
                  </td>
                  {PERMISSIONS.map((p) => {
                    const k = `${row.resourceCql}|${p}`;
                    if (!applicable.includes(p)) return <td key={p} className="perm-na" />;
                    const from = inherited.get(k);
                    const changed = k in staged;
                    return (
                      <td key={p} className={`perm-cell ${changed ? "perm-changed" : ""}`} title={from ? `Inherited from ${from.join(", ")}` : undefined}>
                        <input type="checkbox" checked={isOn(k)} onChange={() => toggle(k)} />
                        {from && !isOn(k) && <span className="inherited-dot">●</span>}
                      </td>
                    );
                  })}
                  <td className="actions nowrap">
                    <button className="btn btn-xs" onClick={() => setRow(row.resourceCql, true)} title="Tick every applicable permission">
                      All
                    </button>
                    <button className="btn btn-xs" onClick={() => setRow(row.resourceCql, false)} title="Untick every permission">
                      None
                    </button>
                    {permissions.some((x) => !x.inherited && x.resourceCql === row.resourceCql) && (
                      <button className="btn btn-xs btn-danger-ghost" onClick={() => revokeAll(row.resourceCql)}>
                        Revoke all
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={PERMISSIONS.length + 2} className="muted center">
                  No permissions granted. Add a resource to grant access.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="row gap mt">
        <button className="btn btn-sm" onClick={() => setAdding(true)}>
          + Add resource
        </button>
        <span className="muted small">
          <span className="inherited-dot">●</span> inherited from a granted role · greyed cells don't apply to the resource
        </span>
      </div>
      {statements.length > 0 && (
        <div className="staged">
          <CodeBlock code={statements.join("\n")} maxHeight={180} />
          <div className="row gap">
            <button className="btn btn-sm" onClick={() => setStaged({})} disabled={applying}>
              Discard
            </button>
            <button className="btn btn-sm btn-primary" onClick={apply} disabled={applying}>
              Apply {statements.length} change(s)
            </button>
          </div>
        </div>
      )}
      {adding && (
        <ResourcePicker
          roles={roles}
          onClose={() => setAdding(false)}
          onPick={(row) => {
            setExtraRows((rs) => [...rs, row]);
            setAdding(false);
          }}
        />
      )}
    </div>
  );
}

function ResourcePicker({ roles, onClose, onPick }: { roles: Role[]; onClose: () => void; onPick: (r: ResourceRow) => void }) {
  const ws = useWorkspace();
  const userKs = ws.keyspaces.filter((k) => !k.virtual);
  const [kind, setKind] = useState<ResourceKind>("KEYSPACE");
  const [ks, setKs] = useState(userKs.find((k) => !k.system)?.name ?? userKs[0]?.name ?? "");
  const [table, setTable] = useState("");
  const [roleName, setRoleName] = useState(roles[0]?.name ?? "");
  const tables = userKs.find((k) => k.name === ks)?.tables ?? [];

  const build = (): ResourceRow | null => {
    switch (kind) {
      case "KEYSPACE":
        return ks ? { resourceCql: `KEYSPACE ${quoteId(ks)}`, label: `keyspace ${ks}` } : null;
      case "TABLE": {
        const t = table || tables[0]?.name;
        return ks && t ? { resourceCql: `TABLE ${qualified(ks, t)}`, label: `table ${ks}.${t}` } : null;
      }
      case "ROLE":
        return roleName ? { resourceCql: `ROLE ${quoteStr(roleName)}`, label: `role ${roleName}` } : null;
      case "ALL FUNCTIONS IN KEYSPACE":
        return ks ? { resourceCql: `ALL FUNCTIONS IN KEYSPACE ${quoteId(ks)}`, label: `all functions in ${ks}` } : null;
      default:
        return { resourceCql: kind, label: kind.toLowerCase() };
    }
  };
  const picked = build();

  return (
    <Modal
      title="Add resource"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={!picked} onClick={() => picked && onPick(picked)}>
            Add to matrix
          </button>
        </>
      }
    >
      <div className="form-grid">
        <label className="field span-2">
          <span>Resource type</span>
          <select value={kind} onChange={(e) => setKind(e.target.value as ResourceKind)}>
            {(Object.keys(APPLICABLE) as ResourceKind[]).map((k) => (
              <option key={k}>{k}</option>
            ))}
          </select>
        </label>
        {(kind === "KEYSPACE" || kind === "TABLE" || kind === "ALL FUNCTIONS IN KEYSPACE") && (
          <label className="field">
            <span>Keyspace</span>
            <select value={ks} onChange={(e) => (setKs(e.target.value), setTable(""))}>
              {userKs.map((k) => (
                <option key={k.name}>{k.name}</option>
              ))}
            </select>
          </label>
        )}
        {kind === "TABLE" && (
          <label className="field">
            <span>Table</span>
            <select value={table || tables[0]?.name || ""} onChange={(e) => setTable(e.target.value)}>
              {tables.map((t) => (
                <option key={t.name}>{t.name}</option>
              ))}
            </select>
          </label>
        )}
        {kind === "ROLE" && (
          <label className="field">
            <span>Role</span>
            <select value={roleName} onChange={(e) => setRoleName(e.target.value)}>
              {roles.map((r) => (
                <option key={r.name}>{r.name}</option>
              ))}
            </select>
          </label>
        )}
      </div>
      {picked && (
        <p className="small muted">
          Applicable permissions: <span className="mono">{applicableFor(picked.resourceCql).join(", ")}</span>
        </p>
      )}
    </Modal>
  );
}

/* ---------- Create / edit role ---------- */

function RoleDialog({
  existing,
  roles,
  onClose,
  onDone,
}: {
  existing?: Role;
  roles: Role[];
  onClose: () => void;
  onDone: (name: string) => void;
}) {
  const ws = useWorkspace();
  const exec = useExec();
  const datacenters = [...new Set(ws.cluster.nodes.map((n) => n.datacenter))];
  const [name, setName] = useState(existing?.name ?? "");
  const [login, setLogin] = useState(existing?.login ?? true);
  const [superuser, setSuperuser] = useState(existing?.superuser ?? false);
  const [password, setPassword] = useState("");
  const [password2, setPassword2] = useState("");
  const [dcMode, setDcMode] = useState<"keep" | "all" | "some">(existing ? "keep" : "all");
  const [dcs, setDcs] = useState<string[]>([]);
  const [grantRoles, setGrantRoles] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const opts: string[] = [];
  if (!existing || existing.login !== login) opts.push(`LOGIN = ${login}`);
  if (!existing || existing.superuser !== superuser) opts.push(`SUPERUSER = ${superuser}`);
  if (password) opts.push(`PASSWORD = ${quoteStr(password)}`);
  if (dcMode === "all" && (!existing || existing.datacenters !== "ALL")) opts.push("ACCESS TO ALL DATACENTERS");
  if (dcMode === "some" && dcs.length) opts.push(`ACCESS TO DATACENTERS {${dcs.map(quoteStr).join(", ")}}`);

  const roleLit = quoteStr(existing?.name ?? (name.trim() || "new_role"));
  const statements = [
    existing
      ? opts.length
        ? `ALTER ROLE ${roleLit} WITH ${opts.join(" AND ")};`
        : ""
      : `CREATE ROLE ${roleLit}${opts.length ? ` WITH ${opts.join(" AND ")}` : ""};`,
    ...grantRoles.map((g) => `GRANT ${quoteStr(g)} TO ${roleLit};`),
  ].filter(Boolean);
  const preview = statements.join("\n").replace(/PASSWORD = '[^']*(?:''[^']*)*'/g, "PASSWORD = '•••'");
  const mismatch = password !== password2;

  const submit = async () => {
    setBusy(true);
    const ok = await exec(statements.join("\n"), existing ? "Role updated" : "Role created");
    setBusy(false);
    if (ok) onDone(existing?.name ?? name.trim());
  };

  return (
    <Modal
      title={existing ? `Edit role ${existing.name}` : "New role"}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            disabled={busy || (!existing && !name.trim()) || mismatch || statements.length === 0}
            onClick={submit}
          >
            {existing ? "Save" : "Create role"}
          </button>
        </>
      }
    >
      <div className="form-grid">
        {!existing && (
          <label className="field span-2">
            <span>Role name</span>
            <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="app_reader" />
          </label>
        )}
        <label className="check">
          <input type="checkbox" checked={login} onChange={(e) => setLogin(e.target.checked)} /> Can login (user)
        </label>
        <label className="check">
          <input type="checkbox" checked={superuser} onChange={(e) => setSuperuser(e.target.checked)} /> Superuser
        </label>
        <label className="field">
          <span>{existing ? "New password" : "Password"}</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" placeholder={existing ? "unchanged" : ""} />
        </label>
        <label className="field">
          <span>Confirm password</span>
          <input type="password" value={password2} onChange={(e) => setPassword2(e.target.value)} autoComplete="new-password" />
        </label>
        {mismatch && password2 && <div className="span-2 test-result fail">Passwords don't match</div>}
        <label className="field span-2">
          <span>Datacenter access</span>
          <select value={dcMode} onChange={(e) => setDcMode(e.target.value as typeof dcMode)}>
            {existing && <option value="keep">Keep current ({existing.datacenters ?? "ALL"})</option>}
            <option value="all">All datacenters</option>
            <option value="some">Only selected datacenters</option>
          </select>
        </label>
        {dcMode === "some" && (
          <div className="span-2 chips">
            {datacenters.map((dc) => (
              <label key={dc} className="check chip">
                <input type="checkbox" checked={dcs.includes(dc)} onChange={(e) => setDcs((xs) => (e.target.checked ? [...xs, dc] : xs.filter((x) => x !== dc)))} />
                {dc}
              </label>
            ))}
          </div>
        )}
        {!existing && roles.length > 0 && (
          <div className="field span-2">
            <span>Grant roles</span>
            <div className="chips">
              {roles.map((r) => (
                <label key={r.name} className="check chip">
                  <input
                    type="checkbox"
                    checked={grantRoles.includes(r.name)}
                    onChange={(e) => setGrantRoles((xs) => (e.target.checked ? [...xs, r.name] : xs.filter((x) => x !== r.name)))}
                  />
                  {r.name}
                </label>
              ))}
            </div>
          </div>
        )}
      </div>
      {preview && <CodeBlock code={preview} />}
    </Modal>
  );
}
