import { useState } from "react";
import { api } from "../api";
import { plainToText } from "../cql";
import { useWorkspace } from "../state";
import { ErrorBox, Spinner, Tabs, useLoader } from "./ui";

export function ClusterView() {
  const ws = useWorkspace();
  const [tab, setTab] = useState<"nodes" | "settings" | "clients">("nodes");
  const cluster = useLoader(() => api.cluster(ws.conn.id), [ws.conn.id]);
  const settings = useLoader(() => (tab === "settings" ? api.settings(ws.conn.id) : Promise.resolve(null)), [ws.conn.id, tab]);
  const clients = useLoader(() => (tab === "clients" ? api.clients(ws.conn.id) : Promise.resolve(null)), [ws.conn.id, tab]);
  const [q, setQ] = useState("");
  const c = cluster.data ?? ws.cluster;
  const dcs = [...new Set(c.nodes.map((n) => n.datacenter))];

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>🖧 {c.clusterName}</h2>
          <div className="muted small">
            {c.partitioner.split(".").pop()} · CQL {c.cqlVersion} · native protocol v{c.nativeProtocolVersion}
          </div>
        </div>
        <button className="btn" onClick={() => (cluster.reload(), settings.reload(), clients.reload())}>
          ⟳ Refresh
        </button>
      </div>

      <div className="stats">
        <div className="stat">
          <span className="stat-v">{c.nodes.length}</span>
          <span className="stat-l">nodes</span>
        </div>
        <div className="stat">
          <span className="stat-v">{dcs.length}</span>
          <span className="stat-l">datacenters</span>
        </div>
        <div className="stat">
          <span className="stat-v">{ws.keyspaces.filter((k) => !k.system).length}</span>
          <span className="stat-l">user keyspaces</span>
        </div>
        <div className="stat">
          <span className="stat-v">{ws.keyspaces.filter((k) => !k.system).reduce((n, k) => n + k.tables.length, 0)}</span>
          <span className="stat-l">user tables</span>
        </div>
        <div className="stat">
          <span className={`stat-v ${c.auth.authenticationEnabled ? "ok-text" : "warn"}`}>{c.auth.authenticationEnabled ? "On" : "Off"}</span>
          <span className="stat-l">authentication</span>
        </div>
      </div>

      <Tabs
        active={tab}
        onChange={setTab}
        tabs={[
          { id: "nodes", label: "Nodes" },
          { id: "settings", label: "Settings" },
          { id: "clients", label: "Connected clients" },
        ]}
      />

      {tab === "nodes" && (
        <table className="list-table">
          <thead>
            <tr>
              <th>Status</th>
              <th>Address</th>
              <th>Datacenter</th>
              <th>Rack</th>
              <th>Version</th>
              <th>Tokens</th>
              <th>Host ID</th>
            </tr>
          </thead>
          <tbody>
            {c.nodes.map((n) => (
              <tr key={n.hostId}>
                <td>
                  <span className={`dot ${n.up === false ? "dot-down" : n.up ? "dot-up" : ""}`} /> {n.up === false ? "Down" : n.up ? "Up" : "?"}
                  {n.local && <span className="tag">coordinator</span>}
                </td>
                <td className="mono">{n.address}</td>
                <td>{n.datacenter}</td>
                <td>{n.rack}</td>
                <td>{n.version}</td>
                <td className="num">{n.tokens}</td>
                <td className="mono small muted">{n.hostId}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {tab === "settings" &&
        (settings.loading ? (
          <Spinner />
        ) : settings.error ? (
          <ErrorBox error={settings.error} onRetry={settings.reload} />
        ) : (
          <>
            <input className="full mb" placeholder="Search settings…" value={q} onChange={(e) => setQ(e.target.value)} />
            <table className="list-table">
              <tbody>
                {settings.data
                  ?.filter((s) => !q || s.name.includes(q.toLowerCase()) || (s.value ?? "").toLowerCase().includes(q.toLowerCase()))
                  .map((s) => (
                    <tr key={s.name}>
                      <td className="mono small">{s.name}</td>
                      <td className="mono small muted wrap">{s.value ?? "null"}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </>
        ))}

      {tab === "clients" &&
        (clients.loading ? (
          <Spinner />
        ) : clients.error ? (
          <ErrorBox error={clients.error} onRetry={clients.reload} />
        ) : (
          <div className="grid-scroll">
            <table className="list-table">
              <thead>
                <tr>
                  {Object.keys(clients.data?.[0] ?? {}).map((k) => (
                    <th key={k}>{k}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {clients.data?.map((row, i) => (
                  <tr key={i}>
                    {Object.values(row).map((v, j) => (
                      <td key={j} className="small nowrap">
                        {plainToText(v)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
    </div>
  );
}
