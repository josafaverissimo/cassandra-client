import { useMemo, useState } from "react";
import { run, type Column, type Keyspace } from "../api";
import { qualified, quoteId, quoteStr, TYPE_SUGGESTIONS } from "../cql";
import { useWorkspace } from "../state";
import { CodeBlock, Modal, useToast } from "./ui";

function TypeList({ keyspace }: { keyspace?: Keyspace }) {
  return (
    <datalist id="cql-types">
      {[...TYPE_SUGGESTIONS, ...(keyspace?.types.map((t) => `frozen<${t.name}>`) ?? [])].map((t) => (
        <option key={t} value={t} />
      ))}
    </datalist>
  );
}

/** Shared footer: shows the generated CQL and runs it. */
function useRunner(onDone: () => void) {
  const ws = useWorkspace();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const execute = async (cql: string, ok: string) => {
    setBusy(true);
    try {
      await run(ws.conn.id, cql);
      toast({ kind: "ok", message: ok, detail: cql });
      await ws.refreshSchema();
      onDone();
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
    } finally {
      setBusy(false);
    }
  };
  return { busy, execute };
}

/* ---------- Keyspace ---------- */

export function replicationCql(strategy: string, rf: string, dcs: Record<string, string>) {
  if (strategy === "SimpleStrategy") return `{'class': 'SimpleStrategy', 'replication_factor': ${Number(rf) || 1}}`;
  const parts = Object.entries(dcs)
    .filter(([, v]) => v.trim() !== "")
    .map(([dc, v]) => `${quoteStr(dc)}: ${Number(v) || 0}`);
  return `{'class': 'NetworkTopologyStrategy'${parts.length ? ", " + parts.join(", ") : ""}}`;
}

export function CreateKeyspaceDialog({ onClose, existing }: { onClose: () => void; existing?: Keyspace }) {
  const ws = useWorkspace();
  const datacenters = [...new Set(ws.cluster.nodes.map((n) => n.datacenter))];
  const cls = existing?.replication.class ?? "";
  const [name, setName] = useState(existing?.name ?? "");
  const [strategy, setStrategy] = useState(
    existing ? (cls.endsWith("SimpleStrategy") ? "SimpleStrategy" : "NetworkTopologyStrategy") : "NetworkTopologyStrategy",
  );
  const [rf, setRf] = useState(existing?.replication.replication_factor ?? "1");
  const [dcs, setDcs] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      datacenters.map((dc) => [dc, existing?.replication[dc] ?? (existing ? "" : String(Math.min(3, ws.cluster.nodes.filter((n) => n.datacenter === dc).length)))]),
    ),
  );
  const [durable, setDurable] = useState(existing?.durableWrites ?? true);
  const { busy, execute } = useRunner(onClose);

  const cql = existing
    ? `ALTER KEYSPACE ${quoteId(existing.name)} WITH replication = ${replicationCql(strategy, rf, dcs)} AND durable_writes = ${durable};`
    : `CREATE KEYSPACE ${quoteId(name || "my_keyspace")} WITH replication = ${replicationCql(strategy, rf, dcs)} AND durable_writes = ${durable};`;

  return (
    <Modal
      title={existing ? `Alter keyspace ${existing.name}` : "New keyspace"}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            disabled={busy || (!existing && !name.trim())}
            onClick={() => execute(cql, existing ? "Keyspace altered" : "Keyspace created")}
          >
            {existing ? "Alter keyspace" : "Create keyspace"}
          </button>
        </>
      }
    >
      <div className="form-grid">
        {!existing && (
          <label className="field span-2">
            <span>Name</span>
            <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="my_keyspace" />
          </label>
        )}
        <label className="field span-2">
          <span>Replication strategy</span>
          <select value={strategy} onChange={(e) => setStrategy(e.target.value)}>
            <option>NetworkTopologyStrategy</option>
            <option>SimpleStrategy</option>
          </select>
        </label>
        {strategy === "SimpleStrategy" ? (
          <label className="field">
            <span>Replication factor</span>
            <input value={rf} onChange={(e) => setRf(e.target.value)} inputMode="numeric" />
          </label>
        ) : (
          Object.keys(dcs).map((dc) => (
            <label className="field" key={dc}>
              <span>RF in {dc}</span>
              <input value={dcs[dc]} onChange={(e) => setDcs({ ...dcs, [dc]: e.target.value })} inputMode="numeric" />
            </label>
          ))
        )}
        <label className="check span-2">
          <input type="checkbox" checked={durable} onChange={(e) => setDurable(e.target.checked)} /> Durable writes
        </label>
      </div>
      <CodeBlock code={cql} />
    </Modal>
  );
}

/* ---------- Table ---------- */

interface ColDraft {
  id: number;
  name: string;
  type: string;
  role: "pk" | "ck" | "static" | "regular";
  order: "ASC" | "DESC";
}

let nextId = 1;
const draft = (name: string, type: string, role: ColDraft["role"]): ColDraft => ({ id: nextId++, name, type, role, order: "ASC" });

export function buildCreateTable(
  ks: string,
  name: string,
  cols: ColDraft[],
  opts: { comment: string; ttl: string; ifNotExists: boolean; extra: string },
) {
  const valid = cols.filter((c) => c.name.trim() && c.type.trim());
  const pk = valid.filter((c) => c.role === "pk");
  const ck = valid.filter((c) => c.role === "ck");
  const lines = valid.map((c) => `  ${quoteId(c.name.trim())} ${c.type.trim()}${c.role === "static" ? " STATIC" : ""}`);
  const pkExpr = pk.length > 1 ? `(${pk.map((c) => quoteId(c.name)).join(", ")})` : pk.map((c) => quoteId(c.name)).join("");
  lines.push(`  PRIMARY KEY (${[pkExpr, ...ck.map((c) => quoteId(c.name))].filter(Boolean).join(", ")})`);
  const withs: string[] = [];
  if (ck.length) withs.push(`CLUSTERING ORDER BY (${ck.map((c) => `${quoteId(c.name)} ${c.order}`).join(", ")})`);
  if (opts.comment) withs.push(`comment = ${quoteStr(opts.comment)}`);
  if (opts.ttl && Number(opts.ttl) > 0) withs.push(`default_time_to_live = ${Number(opts.ttl)}`);
  if (opts.extra.trim()) withs.push(opts.extra.trim());
  return (
    `CREATE TABLE ${opts.ifNotExists ? "IF NOT EXISTS " : ""}${qualified(ks, name || "my_table")} (\n${lines.join(",\n")}\n)` +
    (withs.length ? ` WITH ${withs.join("\n  AND ")}` : "") +
    ";"
  );
}

export function CreateTableDialog({ keyspace, onClose }: { keyspace: string; onClose: () => void }) {
  const ws = useWorkspace();
  const ks = ws.keyspaces.find((k) => k.name === keyspace);
  const [name, setName] = useState("");
  const [cols, setCols] = useState<ColDraft[]>([draft("id", "uuid", "pk"), draft("created_at", "timestamp", "regular")]);
  const [comment, setComment] = useState("");
  const [ttl, setTtl] = useState("");
  const [extra, setExtra] = useState("");
  const [ifNotExists, setIfNotExists] = useState(false);
  const { busy, execute } = useRunner(() => {
    onClose();
    if (name) ws.openTab({ type: "table", keyspace, table: name });
  });

  const update = (id: number, patch: Partial<ColDraft>) => setCols((cs) => cs.map((c) => (c.id === id ? { ...c, ...patch } : c)));
  const move = (id: number, dir: -1 | 1) =>
    setCols((cs) => {
      const i = cs.findIndex((c) => c.id === id);
      const j = i + dir;
      if (j < 0 || j >= cs.length) return cs;
      const next = cs.slice();
      [next[i], next[j]] = [next[j]!, next[i]!];
      return next;
    });

  const cql = useMemo(
    () => buildCreateTable(keyspace, name, cols, { comment, ttl, ifNotExists, extra }),
    [keyspace, name, cols, comment, ttl, ifNotExists, extra],
  );
  const hasPk = cols.some((c) => c.role === "pk" && c.name.trim());

  return (
    <Modal
      wide
      title={`New table in ${keyspace}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={busy || !name.trim() || !hasPk} onClick={() => execute(cql, "Table created")}>
            Create table
          </button>
        </>
      }
    >
      <TypeList keyspace={ks} />
      <div className="form-grid">
        <label className="field span-2">
          <span>Table name</span>
          <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="my_table" />
        </label>
      </div>
      <table className="designer">
        <thead>
          <tr>
            <th>Column</th>
            <th>Type</th>
            <th>Key</th>
            <th>Order</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {cols.map((c) => (
            <tr key={c.id}>
              <td>
                <input value={c.name} onChange={(e) => update(c.id, { name: e.target.value })} placeholder="column_name" />
              </td>
              <td>
                <input list="cql-types" value={c.type} onChange={(e) => update(c.id, { type: e.target.value })} placeholder="text" />
              </td>
              <td>
                <select value={c.role} onChange={(e) => update(c.id, { role: e.target.value as ColDraft["role"] })}>
                  <option value="pk">Partition key</option>
                  <option value="ck">Clustering</option>
                  <option value="static">Static</option>
                  <option value="regular">Regular</option>
                </select>
              </td>
              <td>
                {c.role === "ck" && (
                  <select value={c.order} onChange={(e) => update(c.id, { order: e.target.value as ColDraft["order"] })}>
                    <option>ASC</option>
                    <option>DESC</option>
                  </select>
                )}
              </td>
              <td className="nowrap">
                <button className="icon-btn" title="Move up" onClick={() => move(c.id, -1)}>
                  ↑
                </button>
                <button className="icon-btn" title="Move down" onClick={() => move(c.id, 1)}>
                  ↓
                </button>
                <button className="icon-btn danger" title="Remove" onClick={() => setCols((cs) => cs.filter((x) => x.id !== c.id))}>
                  ✕
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button className="btn btn-sm" onClick={() => setCols((cs) => [...cs, draft("", "text", "regular")])}>
        + Add column
      </button>
      <p className="muted small">Key column order matters: partition keys and clustering columns are used in the order listed.</p>
      <div className="form-grid">
        <label className="field">
          <span>Comment</span>
          <input value={comment} onChange={(e) => setComment(e.target.value)} />
        </label>
        <label className="field">
          <span>Default TTL (seconds)</span>
          <input value={ttl} onChange={(e) => setTtl(e.target.value)} inputMode="numeric" placeholder="0" />
        </label>
        <label className="field span-2">
          <span>Extra options</span>
          <input
            value={extra}
            onChange={(e) => setExtra(e.target.value)}
            placeholder="compaction = {'class': 'UnifiedCompactionStrategy'}"
          />
        </label>
        <label className="check span-2">
          <input type="checkbox" checked={ifNotExists} onChange={(e) => setIfNotExists(e.target.checked)} /> IF NOT EXISTS
        </label>
      </div>
      <CodeBlock code={cql} />
    </Modal>
  );
}

/* ---------- Columns & indexes ---------- */

export function AddColumnDialog({ keyspace, table, onClose, onDone }: { keyspace: string; table: string; onClose: () => void; onDone: () => void }) {
  const ws = useWorkspace();
  const [name, setName] = useState("");
  const [type, setType] = useState("text");
  const [isStatic, setStatic] = useState(false);
  const { busy, execute } = useRunner(() => {
    onDone();
    onClose();
  });
  const cql = `ALTER TABLE ${qualified(keyspace, table)} ADD ${quoteId(name || "new_column")} ${type}${isStatic ? " STATIC" : ""};`;
  return (
    <Modal
      title="Add column"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={busy || !name.trim() || !type.trim()} onClick={() => execute(cql, "Column added")}>
            Add column
          </button>
        </>
      }
    >
      <TypeList keyspace={ws.keyspaces.find((k) => k.name === keyspace)} />
      <div className="form-grid">
        <label className="field">
          <span>Name</span>
          <input autoFocus value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span>Type</span>
          <input list="cql-types" value={type} onChange={(e) => setType(e.target.value)} />
        </label>
        <label className="check span-2">
          <input type="checkbox" checked={isStatic} onChange={(e) => setStatic(e.target.checked)} /> Static column
        </label>
      </div>
      <CodeBlock code={cql} />
    </Modal>
  );
}

export function CreateIndexDialog({
  keyspace,
  table,
  columns,
  onClose,
  onDone,
}: {
  keyspace: string;
  table: string;
  columns: Column[];
  onClose: () => void;
  onDone: () => void;
}) {
  const [column, setColumn] = useState(columns.find((c) => c.kind === "regular")?.name ?? columns[0]?.name ?? "");
  const [target, setTarget] = useState("");
  const [name, setName] = useState("");
  const [using, setUsing] = useState("sai");
  const { busy, execute } = useRunner(() => {
    onDone();
    onClose();
  });
  const col = columns.find((c) => c.name === column);
  const isMap = !!col && /^(frozen<)?map</.test(col.type);
  const isCollection = !!col && /^(list|set|map)</.test(col.type);
  const targetExpr = target ? `${target}(${quoteId(column)})` : quoteId(column);
  const cql =
    `CREATE INDEX ${name.trim() ? quoteId(name.trim()) + " " : ""}ON ${qualified(keyspace, table)} (${targetExpr})` +
    (using === "sai" ? " USING 'sai'" : using === "sasi" ? " USING 'org.apache.cassandra.index.sasi.SASIIndex'" : "") +
    ";";
  return (
    <Modal
      title="Create index"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={busy || !column} onClick={() => execute(cql, "Index created")}>
            Create index
          </button>
        </>
      }
    >
      <div className="form-grid">
        <label className="field">
          <span>Column</span>
          <select value={column} onChange={(e) => (setColumn(e.target.value), setTarget(""))}>
            {columns.map((c) => (
              <option key={c.name} value={c.name}>
                {c.name} ({c.type})
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Implementation</span>
          <select value={using} onChange={(e) => setUsing(e.target.value)}>
            <option value="sai">Storage-attached (SAI)</option>
            <option value="legacy">Legacy secondary index</option>
            <option value="sasi">SASI</option>
          </select>
        </label>
        {isCollection && (
          <label className="field">
            <span>Index target</span>
            <select value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">{isMap ? "VALUES (default)" : "VALUES"}</option>
              {isMap && <option value="KEYS">KEYS</option>}
              {isMap && <option value="ENTRIES">ENTRIES</option>}
              <option value="FULL">FULL (frozen only)</option>
            </select>
          </label>
        )}
        <label className="field">
          <span>Index name (optional)</span>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder={`${table}_${column}_idx`} />
        </label>
      </div>
      <CodeBlock code={cql} />
    </Modal>
  );
}
