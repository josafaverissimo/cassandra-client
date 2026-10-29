import type cassandra from "cassandra-driver";
import { qualified, quoteId, toPlain } from "./cql";
import { HttpError } from "./connections";

const SYSTEM_KEYSPACES = new Set([
  "system",
  "system_auth",
  "system_schema",
  "system_distributed",
  "system_traces",
  "system_views",
  "system_virtual_schema",
]);

export interface ColumnInfo {
  name: string;
  type: string;
  kind: "partition_key" | "clustering" | "static" | "regular";
  position: number;
  clusteringOrder: string;
}

const KIND_ORDER = { partition_key: 0, clustering: 1, static: 2, regular: 3 };

export async function listKeyspaces(client: cassandra.Client) {
  const q = (cql: string) => client.execute(cql).then((r) => r.rows);
  const [keyspaces, tables, views, udts, functions, aggregates, vKeyspaces, vTables] = await Promise.all([
    q("SELECT keyspace_name, durable_writes, replication FROM system_schema.keyspaces"),
    q("SELECT keyspace_name, table_name, comment FROM system_schema.tables"),
    q("SELECT keyspace_name, view_name, base_table_name FROM system_schema.views"),
    q("SELECT keyspace_name, type_name, field_names, field_types FROM system_schema.types"),
    q("SELECT keyspace_name, function_name, argument_types, return_type FROM system_schema.functions"),
    q("SELECT keyspace_name, aggregate_name, argument_types, return_type FROM system_schema.aggregates"),
    q("SELECT keyspace_name FROM system_virtual_schema.keyspaces").catch(() => []),
    q("SELECT keyspace_name, table_name, comment FROM system_virtual_schema.tables").catch(() => []),
  ]);

  const byKs = <T>(rows: cassandra.types.Row[], ks: string, map: (r: cassandra.types.Row) => T) =>
    rows.filter((r) => r.keyspace_name === ks).map(map);
  const sortByName = <T extends { name: string }>(xs: T[]) => xs.sort((a, b) => a.name.localeCompare(b.name));

  const regular = keyspaces.map((k) => {
    const ks = k.keyspace_name as string;
    return {
      name: ks,
      virtual: false,
      system: SYSTEM_KEYSPACES.has(ks),
      durableWrites: k.durable_writes as boolean,
      replication: toPlain(k.replication) as Record<string, string>,
      tables: sortByName(byKs(tables, ks, (r) => ({ name: r.table_name as string, comment: r.comment as string }))),
      views: sortByName(byKs(views, ks, (r) => ({ name: r.view_name as string, baseTable: r.base_table_name as string }))),
      types: sortByName(
        byKs(udts, ks, (r) => ({
          name: r.type_name as string,
          fields: (r.field_names as string[]).map((n, i) => ({ name: n, type: (r.field_types as string[])[i] })),
        })),
      ),
      functions: sortByName(
        byKs(functions, ks, (r) => ({
          name: r.function_name as string,
          argumentTypes: r.argument_types as string[],
          returnType: r.return_type as string,
        })),
      ),
      aggregates: sortByName(
        byKs(aggregates, ks, (r) => ({
          name: r.aggregate_name as string,
          argumentTypes: r.argument_types as string[],
          returnType: r.return_type as string,
        })),
      ),
    };
  });

  const virtual = vKeyspaces.map((k) => {
    const ks = k.keyspace_name as string;
    return {
      name: ks,
      virtual: true,
      system: true,
      durableWrites: false,
      replication: {},
      tables: sortByName(byKs(vTables, ks, (r) => ({ name: r.table_name as string, comment: r.comment as string }))),
      views: [],
      types: [],
      functions: [],
      aggregates: [],
    };
  });

  return [...regular, ...virtual].sort(
    (a, b) => Number(a.system) - Number(b.system) || a.name.localeCompare(b.name),
  );
}

export async function getColumns(client: cassandra.Client, ks: string, table: string): Promise<ColumnInfo[]> {
  const cql = "SELECT column_name, type, kind, position, clustering_order FROM %s WHERE keyspace_name = ? AND table_name = ?";
  let rows = (await client.execute(cql.replace("%s", "system_schema.columns"), [ks, table])).rows;
  if (rows.length === 0) {
    rows = (await client.execute(cql.replace("%s", "system_virtual_schema.columns"), [ks, table]).catch(() => null))
      ?.rows ?? [];
  }
  if (rows.length === 0) throw new HttpError(404, `Table ${ks}.${table} not found`);
  return rows
    .map((r) => ({
      name: r.column_name as string,
      type: r.type as string,
      kind: r.kind as ColumnInfo["kind"],
      position: r.position as number,
      clusteringOrder: r.clustering_order as string,
    }))
    .sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind] || a.position - b.position || a.name.localeCompare(b.name));
}

export async function describeTable(client: cassandra.Client, ks: string, table: string) {
  const [columns, tableRow, viewRow, virtualRow, indexes, views] = await Promise.all([
    getColumns(client, ks, table),
    client.execute("SELECT * FROM system_schema.tables WHERE keyspace_name = ? AND table_name = ?", [ks, table]),
    client.execute("SELECT * FROM system_schema.views WHERE keyspace_name = ? AND view_name = ?", [ks, table]),
    client
      .execute("SELECT * FROM system_virtual_schema.tables WHERE keyspace_name = ? AND table_name = ?", [ks, table])
      .catch(() => null),
    client.execute("SELECT index_name, kind, options FROM system_schema.indexes WHERE keyspace_name = ? AND table_name = ?", [
      ks,
      table,
    ]),
    client.execute("SELECT view_name FROM system_schema.views WHERE keyspace_name = ? AND base_table_name = ? ALLOW FILTERING", [
      ks,
      table,
    ]),
  ]);
  const kind = viewRow.rowLength ? "view" : virtualRow?.rowLength && !tableRow.rowLength ? "virtual" : "table";
  const optionsRow = tableRow.first() ?? viewRow.first() ?? virtualRow?.first();
  const options: Record<string, unknown> = {};
  if (optionsRow) {
    for (const key of optionsRow.keys()) {
      if (!["keyspace_name", "table_name", "view_name", "id", "base_table_id"].includes(key))
        options[key] = toPlain(optionsRow.get(key));
    }
  }

  return {
    keyspace: ks,
    name: table,
    kind,
    columns,
    options,
    isCounter: columns.some((c) => c.type === "counter"),
    indexes: indexes.rows.map((r) => ({
      name: r.index_name as string,
      kind: r.kind as string,
      options: toPlain(r.options) as Record<string, string>,
    })),
    views: views.rows.map((r) => r.view_name as string),
    ddl: await describe(client, `${kind === "view" ? "MATERIALIZED VIEW" : "TABLE"} ${qualified(ks, table)}`).catch(
      (e) => `-- ${e.message}`,
    ),
  };
}

/** Runs server-side DESCRIBE (Cassandra 4.0+) and joins the create statements. */
export async function describe(client: cassandra.Client, target: string) {
  const res = await client.execute(`DESCRIBE ${target}`);
  return res.rows.map((r) => r.create_statement as string).join("\n\n");
}

export async function describeKeyspace(client: cassandra.Client, ks: string) {
  return describe(client, `KEYSPACE ${quoteId(ks)}`);
}
