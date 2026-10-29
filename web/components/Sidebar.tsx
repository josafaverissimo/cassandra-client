import { useMemo, useState } from "react";
import type { Connection, Keyspace } from "../api";
import { useWorkspace } from "../state";
import { Logo } from "./ui";
import { CreateKeyspaceDialog, CreateTableDialog } from "./SchemaDialogs";

export function Sidebar({
  connections,
  onSwitch,
  onEdit,
  onNew,
  onDisconnect,
  activeTab,
}: {
  connections: Connection[];
  onSwitch: (id: string) => void;
  onEdit: () => void;
  onNew: () => void;
  onDisconnect: () => void;
  activeTab: string | null;
}) {
  const ws = useWorkspace();
  const [search, setSearch] = useState("");
  const [showSystem, setShowSystem] = useState(() => localStorage.getItem("cc.showSystem") === "1");
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [menuOpen, setMenuOpen] = useState(false);
  const [createKs, setCreateKs] = useState(false);
  const [createTableIn, setCreateTableIn] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return ws.keyspaces
      .filter((k) => showSystem || !k.system)
      .map((k) => {
        if (!q || k.name.toLowerCase().includes(q)) return { ks: k, tables: k.tables, views: k.views };
        return {
          ks: k,
          tables: k.tables.filter((t) => t.name.toLowerCase().includes(q)),
          views: k.views.filter((v) => v.name.toLowerCase().includes(q)),
        };
      })
      .filter((x) => !q || x.ks.name.toLowerCase().includes(q) || x.tables.length || x.views.length);
  }, [ws.keyspaces, search, showSystem]);

  const isOpen = (ks: Keyspace) => expanded[ks.name] ?? (!!search.trim() || (!ks.system && filtered.length <= 3));

  return (
    <aside className="sidebar">
      <div className="sidebar-head">
        <div className="brand">
          <Logo />
          <span>Cassandra Client</span>
        </div>
        <div className="conn-switch" onClick={() => setMenuOpen((v) => !v)}>
          <span className="conn-color" style={{ background: ws.conn.color ?? "#64748b" }} />
          <div className="conn-switch-text">
            <b>{ws.conn.name}</b>
            <span className="muted small">
              {ws.cluster.clusterName} · v{ws.cluster.nodes[0]?.version}
            </span>
          </div>
          <span className="caret">▾</span>
          {menuOpen && (
            <div className="menu" onMouseLeave={() => setMenuOpen(false)}>
              {connections.map((c) => (
                <button key={c.id} className="menu-item" onClick={() => onSwitch(c.id)}>
                  <span className="conn-color" style={{ background: c.color ?? "#64748b" }} /> {c.name}
                  {c.id === ws.conn.id && <span className="muted"> ✓</span>}
                </button>
              ))}
              <div className="menu-sep" />
              <button className="menu-item" onClick={onEdit}>
                Edit this connection…
              </button>
              <button className="menu-item" onClick={onNew}>
                New connection…
              </button>
              <button className="menu-item" onClick={onDisconnect}>
                Disconnect
              </button>
            </div>
          )}
        </div>
      </div>

      <nav className="side-nav">
        <button className="side-nav-item" onClick={() => ws.openTab({ type: "query", title: "Query" })}>
          <span className="ico">▶</span> New query
        </button>
        <button
          className={`side-nav-item ${activeTab === "roles" ? "active" : ""}`}
          onClick={() => ws.openTab({ type: "roles" })}
        >
          <span className="ico">🔐</span> Roles &amp; permissions
        </button>
        <button
          className={`side-nav-item ${activeTab === "cluster" ? "active" : ""}`}
          onClick={() => ws.openTab({ type: "cluster" })}
        >
          <span className="ico">🖧</span> Cluster
        </button>
      </nav>

      <div className="tree-head">
        <span>Keyspaces</span>
        <div className="row">
          <button className="icon-btn" title="New keyspace" onClick={() => setCreateKs(true)}>
            ＋
          </button>
          <button
            className={`icon-btn ${refreshing ? "spin" : ""}`}
            title="Refresh schema"
            onClick={async () => {
              setRefreshing(true);
              await ws.refreshSchema().finally(() => setRefreshing(false));
            }}
          >
            ⟳
          </button>
        </div>
      </div>
      <div className="tree-search">
        <input placeholder="Filter tables…" value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>

      <div className="tree">
        {filtered.map(({ ks, tables, views }) => (
          <div key={ks.name} className="tree-ks">
            <div className={`tree-row tree-ks-row ${activeTab === `keyspace:${ks.name}` ? "active" : ""}`}>
              <button className="tree-toggle" onClick={() => setExpanded((e) => ({ ...e, [ks.name]: !isOpen(ks) }))}>
                {isOpen(ks) ? "▾" : "▸"}
              </button>
              <button className="tree-label" onClick={() => ws.openTab({ type: "keyspace", keyspace: ks.name })}>
                <span className="ico-ks" />
                {ks.name}
                {ks.virtual && <span className="tag">virtual</span>}
              </button>
              {!ks.system && (
                <button className="icon-btn tree-action" title="New table" onClick={() => setCreateTableIn(ks.name)}>
                  ＋
                </button>
              )}
            </div>
            {isOpen(ks) && (
              <div className="tree-children">
                {tables.length === 0 && views.length === 0 && <div className="tree-empty">No tables</div>}
                {tables.map((t) => {
                  const key = `table:${ks.name}.${t.name}`;
                  return (
                    <button
                      key={t.name}
                      className={`tree-row tree-leaf ${activeTab === key ? "active" : ""}`}
                      onClick={() => ws.openTab({ type: "table", keyspace: ks.name, table: t.name })}
                      title={t.comment || t.name}
                    >
                      <span className="ico-table" />
                      {t.name}
                    </button>
                  );
                })}
                {views.map((v) => (
                  <button
                    key={v.name}
                    className={`tree-row tree-leaf ${activeTab === `table:${ks.name}.${v.name}` ? "active" : ""}`}
                    onClick={() => ws.openTab({ type: "table", keyspace: ks.name, table: v.name })}
                    title={`Materialized view of ${v.baseTable}`}
                  >
                    <span className="ico-view" />
                    {v.name}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      <label className="sidebar-foot check">
        <input
          type="checkbox"
          checked={showSystem}
          onChange={(e) => {
            setShowSystem(e.target.checked);
            localStorage.setItem("cc.showSystem", e.target.checked ? "1" : "0");
          }}
        />
        Show system keyspaces
      </label>

      {createKs && <CreateKeyspaceDialog onClose={() => setCreateKs(false)} />}
      {createTableIn && <CreateTableDialog keyspace={createTableIn} onClose={() => setCreateTableIn(null)} />}
    </aside>
  );
}
