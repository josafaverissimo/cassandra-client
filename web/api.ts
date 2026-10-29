export interface Connection {
  id: string;
  name: string;
  contactPoints: string[];
  port: number;
  localDataCenter?: string;
  username?: string;
  ssl?: boolean;
  color?: string;
  hasPassword: boolean;
  connected: boolean;
}

export type ConnectionInput = Omit<Connection, "id" | "hasPassword" | "connected"> & { id?: string; password?: string };

export interface Keyspace {
  name: string;
  virtual: boolean;
  system: boolean;
  durableWrites: boolean;
  replication: Record<string, string>;
  tables: { name: string; comment: string }[];
  views: { name: string; baseTable: string }[];
  types: { name: string; fields: { name: string; type: string }[] }[];
  functions: { name: string; argumentTypes: string[]; returnType: string }[];
  aggregates: { name: string; argumentTypes: string[]; returnType: string }[];
}

export interface Column {
  name: string;
  type: string;
  kind: "partition_key" | "clustering" | "static" | "regular";
  position: number;
  clusteringOrder: string;
}

export interface TableInfo {
  keyspace: string;
  name: string;
  kind: "table" | "view" | "virtual";
  columns: Column[];
  options: Record<string, unknown>;
  isCounter: boolean;
  indexes: { name: string; kind: string; options: Record<string, string> }[];
  views: string[];
  ddl: string;
}

/** Cell values are the JSON text produced by Cassandra's toJson(). */
export type Row = Record<string, string | null>;

export interface Filter {
  column: string;
  op: string;
  value: string;
}

export interface RowsResult {
  columns: Column[];
  rows: Row[];
  pageState: string | null;
  cql: string;
  elapsedMs: number;
  warnings: string[];
}

export interface StatementResult {
  statement: string;
  columns?: { name: string; type: string }[];
  rows?: unknown[][];
  pageState?: string | null;
  elapsedMs: number;
  warnings?: string[];
  queriedHost?: string;
  trace?: {
    traceId: string;
    coordinator?: string;
    durationMicros?: number;
    events?: { activity: string; source: string; elapsedMicros: number; thread: string }[];
    error?: string;
  } | null;
  error: string | null;
}

export interface AuthInfo {
  authenticator: string | null;
  authorizer: string | null;
  roleManager: string | null;
  authenticationEnabled: boolean | null;
  authorizationEnabled: boolean | null;
}

export interface ClusterInfo {
  clusterName: string;
  partitioner: string;
  cqlVersion: string;
  nativeProtocolVersion: string;
  keyspace: string | null;
  nodes: {
    address: string;
    nativeAddress: string;
    datacenter: string;
    rack: string;
    version: string;
    hostId: string;
    tokens: number;
    up: boolean | null;
    local: boolean;
  }[];
  auth: AuthInfo;
}

export interface Role {
  name: string;
  login: boolean;
  superuser: boolean;
  memberOf: string[];
  options: Record<string, unknown> | null;
  datacenters: string | null;
}

export interface Permission {
  grantedTo: string;
  inherited: boolean;
  resource: string;
  resourceCql: string;
  permission: string;
}

export class ApiError extends Error {}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: body !== undefined ? { "content-type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok || (data && typeof data === "object" && "error" in data && data.error)) {
    throw new ApiError(data?.error ?? `${res.status} ${res.statusText}`);
  }
  return data as T;
}

const enc = encodeURIComponent;

export const api = {
  connections: () => request<Connection[]>("GET", "/connections"),
  saveConnection: (c: ConnectionInput) =>
    c.id ? request<Connection>("PUT", `/connections/${c.id}`, c) : request<Connection>("POST", "/connections", c),
  deleteConnection: (id: string) => request("DELETE", `/connections/${id}`),
  testConnection: (c: ConnectionInput) =>
    request<{ clusterName: string; version: string; datacenter: string }>("POST", "/connections/test", c),

  connect: (conn: string) => request<ClusterInfo>("POST", `/c/${conn}/connect`),
  disconnect: (conn: string) => request("POST", `/c/${conn}/disconnect`),
  cluster: (conn: string) => request<ClusterInfo>("GET", `/c/${conn}/cluster`),
  settings: (conn: string) => request<{ name: string; value: string | null }[]>("GET", `/c/${conn}/cluster/settings`),
  clients: (conn: string) => request<Record<string, unknown>[]>("GET", `/c/${conn}/cluster/clients`),

  keyspaces: (conn: string) => request<Keyspace[]>("GET", `/c/${conn}/keyspaces`),
  keyspaceDdl: (conn: string, ks: string) => request<{ ddl: string }>("GET", `/c/${conn}/keyspaces/${enc(ks)}/ddl`),
  table: (conn: string, ks: string, table: string) =>
    request<TableInfo>("GET", `/c/${conn}/tables/${enc(ks)}/${enc(table)}`),
  describe: (conn: string, target: string) => request<{ ddl: string }>("POST", `/c/${conn}/describe`, { target }),

  queryRows: (
    conn: string,
    q: { keyspace: string; table: string; filters: Filter[]; allowFiltering: boolean; pageSize: number; pageState?: string | null },
  ) => request<RowsResult>("POST", `/c/${conn}/rows/query`, q),
  countRows: (conn: string, q: { keyspace: string; table: string; filters: Filter[]; allowFiltering: boolean }) =>
    request<{ count: string }>("POST", `/c/${conn}/rows/count`, q),
  insertRow: (conn: string, m: { keyspace: string; table: string; values: Row; ttl?: number }) =>
    request<{ cql: string }>("POST", `/c/${conn}/rows/insert`, m),
  updateRow: (conn: string, m: { keyspace: string; table: string; key: Record<string, string>; values: Row }) =>
    request<{ cql: string }>("POST", `/c/${conn}/rows/update`, m),
  incrementCounter: (
    conn: string,
    m: { keyspace: string; table: string; key: Record<string, string>; column: string; delta: string },
  ) => request<{ cql: string }>("POST", `/c/${conn}/rows/increment`, m),
  deleteRows: (conn: string, m: { keyspace: string; table: string; keys: Record<string, string>[] }) =>
    request<{ cql: string }>("POST", `/c/${conn}/rows/delete`, m),

  execute: (
    conn: string,
    req: {
      cql: string;
      keyspace?: string | null;
      pageSize?: number;
      pageState?: string | null;
      consistency?: string;
      trace?: boolean;
      continueOnError?: boolean;
    },
  ) => request<{ results: StatementResult[]; keyspace: string | null }>("POST", `/c/${conn}/execute`, req),

  auth: (conn: string) => request<AuthInfo>("GET", `/c/${conn}/auth`),
  roles: (conn: string) => request<Role[]>("GET", `/c/${conn}/roles`),
  permissions: (conn: string, role: string) =>
    request<Permission[]>("GET", `/c/${conn}/roles/${enc(role)}/permissions`),
};

/** Executes CQL and throws on the first failing statement. */
export async function run(conn: string, cql: string) {
  const { results } = await api.execute(conn, { cql });
  const failed = results.find((r) => r.error);
  if (failed) throw new ApiError(failed.error!);
  return results;
}
