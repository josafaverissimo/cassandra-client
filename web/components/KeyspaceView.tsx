import { useState } from "react";
import { api, run } from "../api";
import { qualified, quoteId } from "../cql";
import { useWorkspace } from "../state";
import { CreateKeyspaceDialog, CreateTableDialog } from "./SchemaDialogs";
import { CodeBlock, ErrorBox, Spinner, Tabs, useConfirm, useLoader, useToast } from "./ui";

export function KeyspaceView({ keyspace }: { keyspace: string }) {
  const ws = useWorkspace();
  const confirm = useConfirm();
  const toast = useToast();
  const ks = ws.keyspaces.find((k) => k.name === keyspace);
  const [tab, setTab] = useState<"tables" | "types" | "functions" | "ddl">("tables");
  const [alter, setAlter] = useState(false);
  const [createTable, setCreateTable] = useState(false);
  const ddl = useLoader(() => (ks?.virtual ? Promise.resolve({ ddl: "-- virtual keyspace" }) : api.keyspaceDdl(ws.conn.id, keyspace)), [
    ws.conn.id,
    keyspace,
    tab === "ddl",
    ws.keyspaces,
  ]);

  if (!ks) return <ErrorBox error={`Keyspace ${keyspace} no longer exists.`} />;

  const exec = async (cql: string, ok: string) => {
    try {
      await run(ws.conn.id, cql);
      toast({ kind: "ok", message: ok, detail: cql });
      await ws.refreshSchema();
      return true;
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
      return false;
    }
  };

  const dropKeyspace = async () => {
    const cql = `DROP KEYSPACE ${quoteId(keyspace)};`;
    if (
      await confirm({
        title: `Drop keyspace ${keyspace}`,
        message: "This permanently deletes every table and all data in this keyspace.",
        cql,
        danger: true,
        confirmLabel: "Drop keyspace",
        typeToConfirm: keyspace,
      })
    ) {
      if (await exec(cql, "Keyspace dropped")) ws.closeTab(`keyspace:${keyspace}`);
    }
  };

  const dropTable = async (table: string) => {
    const cql = `DROP TABLE ${qualified(keyspace, table)};`;
    if (await confirm({ title: `Drop table ${table}`, message: "The table and all its data will be deleted.", cql, danger: true, confirmLabel: "Drop table", typeToConfirm: table })) {
      if (await exec(cql, "Table dropped")) ws.closeTab(`table:${keyspace}.${table}`);
    }
  };

  const truncate = async (table: string) => {
    const cql = `TRUNCATE ${qualified(keyspace, table)};`;
    if (await confirm({ title: `Truncate ${table}`, message: "All rows will be removed from the table.", cql, danger: true, confirmLabel: "Truncate", typeToConfirm: table }))
      await exec(cql, "Table truncated");
  };

  const dropType = async (name: string) => {
    const cql = `DROP TYPE ${qualified(keyspace, name)};`;
    if (await confirm({ title: `Drop type ${name}`, cql, danger: true, confirmLabel: "Drop type" })) await exec(cql, "Type dropped");
  };

  const replication = Object.entries(ks.replication)
    .map(([k, v]) => (k === "class" ? v.replace("org.apache.cassandra.locator.", "") : `${k}: ${v}`))
    .join(" · ");

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>
            <span className="ico-ks" /> {ks.name}
            {ks.virtual && <span className="tag">virtual</span>}
            {ks.system && !ks.virtual && <span className="tag">system</span>}
          </h2>
          <div className="muted small">
            {replication || "—"} {!ks.virtual && <>· durable writes {ks.durableWrites ? "on" : "off"}</>}
          </div>
        </div>
        <div className="row gap">
          <button className="btn" onClick={() => ws.openTab({ type: "query", title: `Query ${keyspace}`, keyspace })}>
            ▶ Query
          </button>
          {!ks.system && (
            <>
              <button className="btn btn-primary" onClick={() => setCreateTable(true)}>
                + New table
              </button>
              <button className="btn" onClick={() => setAlter(true)}>
                Replication…
              </button>
              <button className="btn btn-danger-ghost" onClick={dropKeyspace}>
                Drop
              </button>
            </>
          )}
        </div>
      </div>

      <Tabs
        active={tab}
        onChange={setTab}
        tabs={[
          { id: "tables", label: `Tables (${ks.tables.length + ks.views.length})` },
          { id: "types", label: `Types (${ks.types.length})` },
          { id: "functions", label: `Functions (${ks.functions.length + ks.aggregates.length})` },
          { id: "ddl", label: "DDL" },
        ]}
      />

      {tab === "tables" && (
        <table className="list-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Kind</th>
              <th>Comment</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {ks.tables.map((t) => (
              <tr key={t.name}>
                <td>
                  <a onClick={() => ws.openTab({ type: "table", keyspace, table: t.name })}>{t.name}</a>
                </td>
                <td className="muted">table</td>
                <td className="muted">{t.comment}</td>
                <td className="actions">
                  {!ks.system && (
                    <>
                      <button className="btn btn-xs" onClick={() => truncate(t.name)}>
                        Truncate
                      </button>
                      <button className="btn btn-xs btn-danger-ghost" onClick={() => dropTable(t.name)}>
                        Drop
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
            {ks.views.map((v) => (
              <tr key={v.name}>
                <td>
                  <a onClick={() => ws.openTab({ type: "table", keyspace, table: v.name })}>{v.name}</a>
                </td>
                <td className="muted">materialized view</td>
                <td className="muted">on {v.baseTable}</td>
                <td className="actions">
                  <button
                    className="btn btn-xs btn-danger-ghost"
                    onClick={async () => {
                      const cql = `DROP MATERIALIZED VIEW ${qualified(keyspace, v.name)};`;
                      if (await confirm({ title: `Drop view ${v.name}`, cql, danger: true, confirmLabel: "Drop view" })) exec(cql, "View dropped");
                    }}
                  >
                    Drop
                  </button>
                </td>
              </tr>
            ))}
            {ks.tables.length + ks.views.length === 0 && (
              <tr>
                <td colSpan={4} className="muted center">
                  No tables yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}

      {tab === "types" && (
        <table className="list-table">
          <thead>
            <tr>
              <th>Type</th>
              <th>Fields</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {ks.types.map((t) => (
              <tr key={t.name}>
                <td className="mono">{t.name}</td>
                <td className="mono small">{t.fields.map((f) => `${f.name} ${f.type}`).join(", ")}</td>
                <td className="actions">
                  {!ks.system && (
                    <button className="btn btn-xs btn-danger-ghost" onClick={() => dropType(t.name)}>
                      Drop
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {ks.types.length === 0 && (
              <tr>
                <td colSpan={3} className="muted center">
                  No user-defined types. Create one with <code>CREATE TYPE</code> in the query editor.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}

      {tab === "functions" && (
        <table className="list-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Kind</th>
              <th>Signature</th>
            </tr>
          </thead>
          <tbody>
            {[...ks.functions.map((f) => ({ ...f, kind: "function" })), ...ks.aggregates.map((f) => ({ ...f, kind: "aggregate" }))].map((f) => (
              <tr key={f.kind + f.name + f.argumentTypes.join()}>
                <td className="mono">{f.name}</td>
                <td className="muted">{f.kind}</td>
                <td className="mono small">
                  ({f.argumentTypes.join(", ")}) → {f.returnType}
                </td>
              </tr>
            ))}
            {ks.functions.length + ks.aggregates.length === 0 && (
              <tr>
                <td colSpan={3} className="muted center">
                  No user-defined functions or aggregates.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}

      {tab === "ddl" &&
        (ddl.loading ? <Spinner /> : ddl.error ? <ErrorBox error={ddl.error} onRetry={ddl.reload} /> : <CodeBlock code={ddl.data!.ddl} />)}

      {alter && <CreateKeyspaceDialog existing={ks} onClose={() => setAlter(false)} />}
      {createTable && <CreateTableDialog keyspace={keyspace} onClose={() => setCreateTable(false)} />}
    </div>
  );
}
