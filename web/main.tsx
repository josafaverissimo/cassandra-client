import { useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { api, type ClusterInfo, type Connection, type Keyspace } from "./api";
import { ClusterView } from "./components/ClusterView";
import { ConnectionDialog } from "./components/ConnectionDialog";
import { KeyspaceView } from "./components/KeyspaceView";
import { QueryEditor } from "./components/QueryEditor";
import { RolesView } from "./components/RolesView";
import { Sidebar } from "./components/Sidebar";
import { TableView } from "./components/TableView";
import { ConfirmProvider, ErrorBox, Logo, Spinner, ToastProvider } from "./components/ui";
import { tabKey, useWorkspace, WorkspaceCtx, type Tab, type TabInput, type Workspace } from "./state";
import "./styles.css";

function App() {
  const [connections, setConnections] = useState<Connection[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(() => sessionStorage.getItem("cc.active"));
  const [editing, setEditing] = useState<Connection | "new" | null>(null);

  const loadConnections = useCallback(async () => setConnections(await api.connections()), []);
  useEffect(() => {
    loadConnections();
  }, [loadConnections]);

  useEffect(() => {
    if (activeId) sessionStorage.setItem("cc.active", activeId);
    else sessionStorage.removeItem("cc.active");
  }, [activeId]);

  const active = connections?.find((c) => c.id === activeId) ?? null;

  return (
    <>
      {active ? (
        <ConnectedWorkspace
          key={active.id}
          conn={active}
          connections={connections ?? []}
          onSwitch={setActiveId}
          onEdit={(c) => setEditing(c)}
          onNew={() => setEditing("new")}
          onDisconnect={async () => {
            await api.disconnect(active.id).catch(() => {});
            setActiveId(null);
            loadConnections();
          }}
        />
      ) : (
        <Welcome
          connections={connections}
          onOpen={setActiveId}
          onEdit={(c) => setEditing(c)}
          onNew={() => setEditing("new")}
        />
      )}
      {editing && (
        <ConnectionDialog
          initial={editing === "new" ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={async (c) => {
            setEditing(null);
            await loadConnections();
            if (editing === "new") setActiveId(c.id);
          }}
          onDeleted={async (id) => {
            setEditing(null);
            if (id === activeId) setActiveId(null);
            await loadConnections();
          }}
        />
      )}
    </>
  );
}

function Welcome({
  connections,
  onOpen,
  onEdit,
  onNew,
}: {
  connections: Connection[] | null;
  onOpen: (id: string) => void;
  onEdit: (c: Connection) => void;
  onNew: () => void;
}) {
  return (
    <div className="welcome">
      <div className="welcome-inner">
        <div className="brand-lg">
          <Logo /> Cassandra Client
        </div>
        <p className="muted">Choose a connection to browse data, run CQL and manage roles &amp; permissions.</p>
        {!connections ? (
          <Spinner />
        ) : (
          <div className="conn-cards">
            {connections.map((c) => (
              <div key={c.id} className="conn-card" onClick={() => onOpen(c.id)}>
                <span className="conn-color" style={{ background: c.color ?? "#64748b" }} />
                <div className="conn-card-body">
                  <b>{c.name}</b>
                  <span className="muted mono">
                    {c.username ? `${c.username}@` : ""}
                    {c.contactPoints.join(",")}:{c.port}
                  </span>
                </div>
                <button
                  className="icon-btn"
                  title="Edit"
                  onClick={(e) => {
                    e.stopPropagation();
                    onEdit(c);
                  }}
                >
                  ✎
                </button>
              </div>
            ))}
            <button className="conn-card conn-card-new" onClick={onNew}>
              + New connection
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function ConnectedWorkspace({
  conn,
  connections,
  onSwitch,
  onEdit,
  onNew,
  onDisconnect,
}: {
  conn: Connection;
  connections: Connection[];
  onSwitch: (id: string) => void;
  onEdit: (c: Connection) => void;
  onNew: () => void;
  onDisconnect: () => void;
}) {
  const [cluster, setCluster] = useState<ClusterInfo | null>(null);
  const [keyspaces, setKeyspaces] = useState<Keyspace[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [activeTab, setActiveTab] = useState<string | null>(null);

  const connect = useCallback(async () => {
    setError(null);
    try {
      const [c, ks] = await Promise.all([api.connect(conn.id), api.keyspaces(conn.id)]);
      setCluster(c);
      setKeyspaces(ks);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [conn.id]);

  useEffect(() => {
    connect();
  }, [connect]);

  const refreshSchema = useCallback(async () => {
    setKeyspaces(await api.keyspaces(conn.id));
  }, [conn.id]);

  const openTab = useCallback((input: TabInput) => {
    const key = tabKey(input);
    setTabs((ts) => (ts.some((t) => t.key === key) ? ts : [...ts, { ...input, key } as Tab]));
    setActiveTab(key);
  }, []);

  const closeTab = useCallback((key: string) => {
    setTabs((ts) => {
      const idx = ts.findIndex((t) => t.key === key);
      const next = ts.filter((t) => t.key !== key);
      setActiveTab((cur) => (cur === key ? (next[Math.max(0, idx - 1)]?.key ?? null) : cur));
      return next;
    });
  }, []);

  const renameTab = useCallback((key: string, title: string) => {
    setTabs((ts) => ts.map((t) => (t.key === key && t.type === "query" ? { ...t, title } : t)));
  }, []);

  const ws: Workspace | null = useMemo(
    () => (cluster ? { conn, cluster, keyspaces, refreshSchema, openTab, closeTab, renameTab } : null),
    [conn, cluster, keyspaces, refreshSchema, openTab, closeTab, renameTab],
  );

  if (error)
    return (
      <div className="welcome">
        <div className="welcome-inner">
          <h2>Could not connect to {conn.name}</h2>
          <ErrorBox error={error} onRetry={connect} />
          <div className="row gap">
            <button className="btn" onClick={() => onEdit(conn)}>
              Edit connection
            </button>
            <button className="btn" onClick={onDisconnect}>
              Back
            </button>
          </div>
        </div>
      </div>
    );
  if (!ws) return <Spinner label={`Connecting to ${conn.name}…`} />;

  return (
    <WorkspaceCtx.Provider value={ws}>
      <div className="app">
        <Sidebar
          connections={connections}
          onSwitch={onSwitch}
          onEdit={() => onEdit(conn)}
          onNew={onNew}
          onDisconnect={onDisconnect}
          activeTab={activeTab}
        />
        <main className="main">
          <div className="tabbar">
            {tabs.map((t) => (
              <div
                key={t.key}
                className={`tab ${t.key === activeTab ? "active" : ""}`}
                onClick={() => setActiveTab(t.key)}
                onAuxClick={(e) => e.button === 1 && closeTab(t.key)}
                title={tabTitle(t)}
              >
                <span className={`tab-icon tab-icon-${t.type}`} />
                <span className="tab-title">{tabTitle(t)}</span>
                <button
                  className="tab-close"
                  onClick={(e) => {
                    e.stopPropagation();
                    closeTab(t.key);
                  }}
                >
                  ✕
                </button>
              </div>
            ))}
            <button className="tab-new" title="New query (Ctrl+Alt+N)" onClick={() => openTab({ type: "query", title: "Query" })}>
              +
            </button>
          </div>
          <div className="tab-panels">
            {tabs.length === 0 && <EmptyState />}
            {tabs.map((t) => (
              <div key={t.key} className="tab-panel" hidden={t.key !== activeTab}>
                {renderTab(t, t.key === activeTab)}
              </div>
            ))}
          </div>
        </main>
      </div>
    </WorkspaceCtx.Provider>
  );
}

function renderTab(t: Tab, active: boolean) {
  switch (t.type) {
    case "table":
      return <TableView keyspace={t.keyspace} table={t.table} />;
    case "keyspace":
      return <KeyspaceView keyspace={t.keyspace} />;
    case "query":
      return <QueryEditor tabKey={t.key} initialCql={t.initialCql} initialKeyspace={t.keyspace} active={active} />;
    case "roles":
      return <RolesView />;
    case "cluster":
      return <ClusterView />;
  }
}

function tabTitle(t: Tab) {
  switch (t.type) {
    case "table":
      return `${t.keyspace}.${t.table}`;
    case "keyspace":
      return t.keyspace;
    case "query":
      return t.title;
    case "roles":
      return "Roles & permissions";
    case "cluster":
      return "Cluster";
  }
}

function EmptyState() {
  const ws = useWorkspace();
  return (
    <div className="empty-state">
      <h2>{ws.cluster.clusterName}</h2>
      <p className="muted">
        Cassandra {ws.cluster.nodes[0]?.version} · {ws.cluster.nodes.length} node(s) ·{" "}
        {ws.keyspaces.filter((k) => !k.system).length} keyspace(s)
      </p>
      <div className="quick-actions">
        <button className="btn" onClick={() => ws.openTab({ type: "query", title: "Query" })}>
          ▶ New CQL query
        </button>
        <button className="btn" onClick={() => ws.openTab({ type: "roles" })}>
          🔐 Roles &amp; permissions
        </button>
        <button className="btn" onClick={() => ws.openTab({ type: "cluster" })}>
          🖧 Cluster overview
        </button>
      </div>
      <p className="muted small">Pick a table in the sidebar to browse and edit its data.</p>
    </div>
  );
}

const root = createRoot(document.getElementById("root")!);
root.render(
  <ToastProvider>
    <ConfirmProvider>
      <App />
    </ConfirmProvider>
  </ToastProvider>,
);
