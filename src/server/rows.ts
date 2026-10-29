import type cassandra from "cassandra-driver";
import { qualified, quoteId, splitJsonArray } from "./cql";
import { HttpError } from "./connections";
import { getColumns, type ColumnInfo } from "./schema";

/*
 * Every value crosses the API as the JSON text produced by Cassandra's own
 * toJson() and is written back with fromJson(). That keeps all CQL types
 * (bigint, decimal, UDTs, nested collections, vectors...) lossless without
 * re-implementing type conversion here.
 */

export type JsonText = string;

export interface Filter {
  column: string;
  op: "=" | "<" | "<=" | ">" | ">=" | "IN" | "CONTAINS" | "CONTAINS KEY" | "LIKE";
  value: JsonText;
}

export interface RowsQuery {
  keyspace: string;
  table: string;
  filters?: Filter[];
  allowFiltering?: boolean;
  pageSize?: number;
  pageState?: string;
}

const OPS = new Set(["=", "<", "<=", ">", ">=", "IN", "CONTAINS", "CONTAINS KEY", "LIKE"]);

function columnMap(columns: ColumnInfo[]) {
  return new Map(columns.map((c) => [c.name, c]));
}

function assertColumn(cols: Map<string, ColumnInfo>, name: string) {
  const col = cols.get(name);
  if (!col) throw new HttpError(400, `Unknown column ${name}`);
  return col;
}

function buildWhere(filters: Filter[], cols: Map<string, ColumnInfo>) {
  const clauses: string[] = [];
  const params: string[] = [];
  for (const f of filters) {
    assertColumn(cols, f.column);
    if (!OPS.has(f.op)) throw new HttpError(400, `Unsupported operator ${f.op}`);
    if (f.op === "IN") {
      const items = splitJsonArray(f.value);
      clauses.push(`${quoteId(f.column)} IN (${items.map(() => "fromJson(?)").join(", ")})`);
      params.push(...items);
    } else {
      clauses.push(`${quoteId(f.column)} ${f.op} fromJson(?)`);
      params.push(f.value);
    }
  }
  return { where: clauses.length ? ` WHERE ${clauses.join(" AND ")}` : "", params };
}

function keyWhere(key: Record<string, JsonText>, columns: ColumnInfo[]) {
  const pk = columns.filter((c) => c.kind === "partition_key" || c.kind === "clustering");
  const missing = pk.filter((c) => !(c.name in key));
  // Clustering columns may be omitted only for static-only rows; partition key is mandatory.
  if (missing.some((c) => c.kind === "partition_key"))
    throw new HttpError(400, `Missing partition key column(s): ${missing.map((c) => c.name).join(", ")}`);
  const used = pk.filter((c) => c.name in key);
  return {
    where: used.map((c) => `${quoteId(c.name)} = fromJson(?)`).join(" AND "),
    params: used.map((c) => key[c.name]!),
  };
}

export async function queryRows(client: cassandra.Client, q: RowsQuery) {
  const columns = await getColumns(client, q.keyspace, q.table);
  const cols = columnMap(columns);
  const select = columns.map((c) => `toJson(${quoteId(c.name)}) AS ${quoteId(c.name)}`).join(", ");
  const { where, params } = buildWhere(q.filters ?? [], cols);
  const cql = `SELECT ${select} FROM ${qualified(q.keyspace, q.table)}${where}${q.allowFiltering ? " ALLOW FILTERING" : ""}`;
  const started = performance.now();
  const res = await client.execute(cql, params, {
    prepare: true,
    fetchSize: Math.min(Math.max(q.pageSize ?? 100, 1), 5000),
    pageState: q.pageState || undefined,
    autoPage: false,
  });
  return {
    columns,
    rows: res.rows.map((r) => Object.fromEntries(columns.map((c) => [c.name, r.get(c.name) as JsonText | null]))),
    pageState: res.pageState ?? null,
    cql: displayCql(cql, params),
    elapsedMs: Math.round(performance.now() - started),
    warnings: res.info.warnings ?? [],
  };
}

export async function countRows(client: cassandra.Client, q: RowsQuery) {
  const cols = columnMap(await getColumns(client, q.keyspace, q.table));
  const { where, params } = buildWhere(q.filters ?? [], cols);
  const cql = `SELECT COUNT(*) FROM ${qualified(q.keyspace, q.table)}${where}${q.allowFiltering ? " ALLOW FILTERING" : ""}`;
  const res = await client.execute(cql, params, { prepare: true, readTimeout: 120000 });
  return { count: res.first().get(0).toString() as string };
}

export interface RowMutation {
  keyspace: string;
  table: string;
  /** Primary key of the row (JSON text per column). */
  key?: Record<string, JsonText>;
  /** Column values to write (JSON text, or null to unset). */
  values?: Record<string, JsonText | null>;
  ttl?: number;
}

export async function insertRow(client: cassandra.Client, m: RowMutation) {
  const columns = await getColumns(client, m.keyspace, m.table);
  const cols = columnMap(columns);
  const entries = Object.entries(m.values ?? {}).filter(([, v]) => v !== null && v !== undefined);
  for (const [name] of entries) assertColumn(cols, name);
  const missing = columns.filter((c) => (c.kind === "partition_key" || c.kind === "clustering") && !entries.some(([n]) => n === c.name));
  if (missing.length) throw new HttpError(400, `Missing primary key column(s): ${missing.map((c) => c.name).join(", ")}`);
  const cql =
    `INSERT INTO ${qualified(m.keyspace, m.table)} (${entries.map(([n]) => quoteId(n)).join(", ")}) ` +
    `VALUES (${entries.map(() => "fromJson(?)").join(", ")})${m.ttl ? ` USING TTL ${Math.floor(m.ttl)}` : ""}`;
  const params = entries.map(([, v]) => v as string);
  await client.execute(cql, params, { prepare: true });
  return { cql: displayCql(cql, params) };
}

export async function updateRow(client: cassandra.Client, m: RowMutation) {
  const columns = await getColumns(client, m.keyspace, m.table);
  const cols = columnMap(columns);
  const sets = Object.entries(m.values ?? {});
  if (!sets.length) throw new HttpError(400, "Nothing to update");
  for (const [name] of sets) {
    const col = assertColumn(cols, name);
    if (col.kind === "partition_key" || col.kind === "clustering")
      throw new HttpError(400, `Primary key column ${name} cannot be updated; delete and re-insert the row instead`);
    if (col.type === "counter") throw new HttpError(400, `Counter column ${name} can only be incremented`);
  }
  const k = keyWhere(m.key ?? {}, columns);
  const cql =
    `UPDATE ${qualified(m.keyspace, m.table)}${m.ttl ? ` USING TTL ${Math.floor(m.ttl)}` : ""} ` +
    `SET ${sets.map(([n, v]) => `${quoteId(n)} = ${v === null ? "null" : "fromJson(?)"}`).join(", ")} WHERE ${k.where}`;
  const params = [...(sets.map(([, v]) => v).filter((v) => v !== null) as string[]), ...k.params];
  await client.execute(cql, params, { prepare: true });
  return { cql: displayCql(cql, params) };
}

export async function incrementCounter(
  client: cassandra.Client,
  m: { keyspace: string; table: string; key: Record<string, JsonText>; column: string; delta: string },
) {
  const columns = await getColumns(client, m.keyspace, m.table);
  const col = assertColumn(columnMap(columns), m.column);
  if (col.type !== "counter") throw new HttpError(400, `${m.column} is not a counter`);
  if (!/^-?\d+$/.test(m.delta)) throw new HttpError(400, "Delta must be an integer");
  const k = keyWhere(m.key, columns);
  const c = quoteId(m.column);
  const cql = `UPDATE ${qualified(m.keyspace, m.table)} SET ${c} = ${c} + ${m.delta} WHERE ${k.where}`;
  await client.execute(cql, k.params, { prepare: true });
  return { cql: displayCql(cql, k.params) };
}

export async function deleteRows(
  client: cassandra.Client,
  m: { keyspace: string; table: string; keys: Record<string, JsonText>[] },
) {
  const columns = await getColumns(client, m.keyspace, m.table);
  const statements: string[] = [];
  await Promise.all(
    m.keys.map(async (key) => {
      const k = keyWhere(key, columns);
      const cql = `DELETE FROM ${qualified(m.keyspace, m.table)} WHERE ${k.where}`;
      await client.execute(cql, k.params, { prepare: true });
      statements.push(displayCql(cql, k.params));
    }),
  );
  return { cql: statements.join(";\n") };
}

/** Human readable CQL with bind markers replaced by their fromJson literals (display only). */
function displayCql(cql: string, params: string[]) {
  let i = 0;
  return cql.replace(/fromJson\(\?\)/g, () => {
    const v = params[i++] ?? "";
    return `fromJson('${v.replace(/'/g, "''")}')`;
  });
}
