import { useState } from "react";
import { api, run, type TableInfo } from "../api";
import { qualified, quoteId, quoteStr } from "../cql";
import { useWorkspace } from "../state";
import { DataGrid } from "./DataGrid";
import { AddColumnDialog, CreateIndexDialog } from "./SchemaDialogs";
import { CodeBlock, ErrorBox, KindBadge, Modal, Spinner, Tabs, useConfirm, useLoader, useToast } from "./ui";

type Sub = "data" | "structure" | "indexes" | "options" | "ddl";

export function TableView({ keyspace, table }: { keyspace: string; table: string }) {
  const ws = useWorkspace();
  const [sub, setSub] = useState<Sub>("data");
  const { data: info, error, loading, reload } = useLoader(() => api.table(ws.conn.id, keyspace, table), [ws.conn.id, keyspace, table]);

  if (loading && !info) return <Spinner label="Loading table…" />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!info) return null;

  const pk = info.columns.filter((c) => c.kind === "partition_key").map((c) => c.name);
  const ck = info.columns.filter((c) => c.kind === "clustering").map((c) => c.name);

  return (
    <div className="page page-table">
      <div className="page-head">
        <div>
          <h2>
            <span className={info.kind === "view" ? "ico-view" : "ico-table"} />
            <span className="muted">{keyspace}.</span>
            {table}
            {info.kind !== "table" && <span className="tag">{info.kind === "view" ? "materialized view" : "virtual"}</span>}
            {info.isCounter && <span className="tag">counter</span>}
          </h2>
          <div className="muted small mono">
            PRIMARY KEY (({pk.join(", ")}){ck.length ? `, ${ck.join(", ")}` : ""})
          </div>
        </div>
        <div className="row gap">
          <button
            className="btn"
            onClick={() =>
              ws.openTab({
                type: "query",
                title: `Query ${table}`,
                keyspace,
                initialCql: `SELECT * FROM ${qualified(keyspace, table)} LIMIT 100;`,
              })
            }
          >
            ▶ Query
          </button>
        </div>
      </div>
      <Tabs
        active={sub}
        onChange={setSub}
        tabs={[
          { id: "data", label: "Data" },
          { id: "structure", label: `Structure (${info.columns.length})` },
          { id: "indexes", label: `Indexes (${info.indexes.length})` },
          { id: "options", label: "Options" },
          { id: "ddl", label: "DDL" },
        ]}
      />
      <div className="sub-panel" hidden={sub !== "data"}>
        <DataGrid info={info} />
      </div>
      {sub === "structure" && <Structure info={info} onChanged={reload} />}
      {sub === "indexes" && <Indexes info={info} onChanged={reload} />}
      {sub === "options" && <Options info={info} onChanged={reload} />}
      {sub === "ddl" && <CodeBlock code={info.ddl} />}
    </div>
  );
}

function useExec(onChanged: () => void) {
  const ws = useWorkspace();
  const toast = useToast();
  return async (cql: string, ok: string) => {
    try {
      await run(ws.conn.id, cql);
      toast({ kind: "ok", message: ok, detail: cql });
      onChanged();
      ws.refreshSchema();
      return true;
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
      return false;
    }
  };
}

function Structure({ info, onChanged }: { info: TableInfo; onChanged: () => void }) {
  const confirm = useConfirm();
  const exec = useExec(onChanged);
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<string | null>(null);
  const editable = info.kind === "table";

  const drop = async (name: string) => {
    const cql = `ALTER TABLE ${qualified(info.keyspace, info.name)} DROP ${quoteId(name)};`;
    if (await confirm({ title: `Drop column ${name}`, message: "The column and its data will be removed.", cql, danger: true, confirmLabel: "Drop column" }))
      exec(cql, "Column dropped");
  };

  return (
    <div>
      {editable && (
        <div className="row gap mb">
          <button className="btn btn-primary btn-sm" onClick={() => setAdding(true)}>
            + Add column
          </button>
        </div>
      )}
      <table className="list-table">
        <thead>
          <tr>
            <th>Column</th>
            <th>Type</th>
            <th>Kind</th>
            <th>Clustering order</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {info.columns.map((c) => (
            <tr key={c.name}>
              <td className="mono">
                {c.name} <KindBadge kind={c.kind} />
              </td>
              <td className="mono">{c.type}</td>
              <td className="muted">{c.kind.replace("_", " ")}</td>
              <td className="muted">{c.kind === "clustering" ? c.clusteringOrder.toUpperCase() : ""}</td>
              <td className="actions">
                {editable && (c.kind === "partition_key" || c.kind === "clustering") && (
                  <button className="btn btn-xs" onClick={() => setRenaming(c.name)} title="Only primary key columns can be renamed">
                    Rename
                  </button>
                )}
                {editable && (c.kind === "regular" || c.kind === "static") && (
                  <button className="btn btn-xs btn-danger-ghost" onClick={() => drop(c.name)}>
                    Drop
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {adding && <AddColumnDialog keyspace={info.keyspace} table={info.name} onClose={() => setAdding(false)} onDone={onChanged} />}
      {renaming && (
        <RenameColumn
          from={renaming}
          onClose={() => setRenaming(null)}
          onRename={async (to) => {
            if (await exec(`ALTER TABLE ${qualified(info.keyspace, info.name)} RENAME ${quoteId(renaming)} TO ${quoteId(to)};`, "Column renamed"))
              setRenaming(null);
          }}
        />
      )}
    </div>
  );
}

function RenameColumn({ from, onClose, onRename }: { from: string; onClose: () => void; onRename: (to: string) => void }) {
  const [to, setTo] = useState(from);
  return (
    <Modal
      title={`Rename ${from}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={!to.trim() || to === from} onClick={() => onRename(to.trim())}>
            Rename
          </button>
        </>
      }
    >
      <label className="field">
        <span>New name</span>
        <input autoFocus value={to} onChange={(e) => setTo(e.target.value)} onKeyDown={(e) => e.key === "Enter" && onRename(to.trim())} />
      </label>
    </Modal>
  );
}

function Indexes({ info, onChanged }: { info: TableInfo; onChanged: () => void }) {
  const confirm = useConfirm();
  const exec = useExec(onChanged);
  const [creating, setCreating] = useState(false);
  return (
    <div>
      {info.kind === "table" && (
        <div className="row gap mb">
          <button className="btn btn-primary btn-sm" onClick={() => setCreating(true)}>
            + Create index
          </button>
        </div>
      )}
      <table className="list-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Target</th>
            <th>Kind</th>
            <th>Class</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {info.indexes.map((ix) => (
            <tr key={ix.name}>
              <td className="mono">{ix.name}</td>
              <td className="mono">{ix.options.target}</td>
              <td className="muted">{ix.kind}</td>
              <td className="muted small">{ix.options.class_name?.split(".").pop() ?? "legacy"}</td>
              <td className="actions">
                <button
                  className="btn btn-xs btn-danger-ghost"
                  onClick={async () => {
                    const cql = `DROP INDEX ${qualified(info.keyspace, ix.name)};`;
                    if (await confirm({ title: `Drop index ${ix.name}`, cql, danger: true, confirmLabel: "Drop index" })) exec(cql, "Index dropped");
                  }}
                >
                  Drop
                </button>
              </td>
            </tr>
          ))}
          {info.indexes.length === 0 && (
            <tr>
              <td colSpan={5} className="muted center">
                No secondary indexes.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {info.views.length > 0 && (
        <p className="muted small">
          Materialized views on this table: <span className="mono">{info.views.join(", ")}</span>
        </p>
      )}
      {creating && (
        <CreateIndexDialog keyspace={info.keyspace} table={info.name} columns={info.columns} onClose={() => setCreating(false)} onDone={onChanged} />
      )}
    </div>
  );
}

const EDITABLE_OPTIONS: Record<string, "string" | "number" | "map"> = {
  comment: "string",
  default_time_to_live: "number",
  gc_grace_seconds: "number",
  bloom_filter_fp_chance: "number",
  crc_check_chance: "number",
  memtable_flush_period_in_ms: "number",
  min_index_interval: "number",
  max_index_interval: "number",
  speculative_retry: "string",
  additional_write_policy: "string",
  read_repair: "string",
  caching: "map",
  compaction: "map",
  compression: "map",
};

function optionLiteral(kind: "string" | "number" | "map", value: string) {
  if (kind === "number") return value.trim();
  if (kind === "string") return quoteStr(value);
  const obj = JSON.parse(value) as Record<string, unknown>;
  return `{${Object.entries(obj)
    .map(([k, v]) => `${quoteStr(k)}: ${quoteStr(String(v))}`)
    .join(", ")}}`;
}

function Options({ info, onChanged }: { info: TableInfo; onChanged: () => void }) {
  const exec = useExec(onChanged);
  const initial = Object.fromEntries(
    Object.entries(info.options).map(([k, v]) => [k, typeof v === "object" && v !== null ? JSON.stringify(v) : String(v ?? "")]),
  );
  const [values, setValues] = useState<Record<string, string>>(initial);
  const changed = Object.keys(values).filter((k) => values[k] !== initial[k] && k in EDITABLE_OPTIONS);
  let cql = "";
  let err: string | null = null;
  try {
    cql = changed.length
      ? `ALTER ${info.kind === "view" ? "MATERIALIZED VIEW" : "TABLE"} ${qualified(info.keyspace, info.name)} WITH ${changed
          .map((k) => `${k} = ${optionLiteral(EDITABLE_OPTIONS[k]!, values[k]!)}`)
          .join("\n  AND ")};`
      : "";
  } catch (e) {
    err = `Invalid JSON: ${(e as Error).message}`;
  }
  const editable = info.kind !== "virtual";

  return (
    <div className="options-grid">
      <table className="list-table">
        <tbody>
          {Object.keys(info.options)
            .sort()
            .map((k) => (
              <tr key={k}>
                <td className="mono small" style={{ width: 240 }}>
                  {k}
                </td>
                <td>
                  {editable && k in EDITABLE_OPTIONS ? (
                    <input
                      className={`mono small full ${values[k] !== initial[k] ? "input-changed" : ""}`}
                      value={values[k] ?? ""}
                      onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
                    />
                  ) : (
                    <span className="mono small muted">{initial[k]}</span>
                  )}
                </td>
              </tr>
            ))}
        </tbody>
      </table>
      {err && <div className="test-result fail">{err}</div>}
      {cql && !err && (
        <div className="sticky-bottom">
          <CodeBlock code={cql} />
          <div className="row gap">
            <button className="btn btn-sm" onClick={() => setValues(initial)}>
              Reset
            </button>
            <button className="btn btn-sm btn-primary" onClick={() => exec(cql, "Table options updated")}>
              Apply changes
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
