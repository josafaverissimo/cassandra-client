import cassandra from "cassandra-driver";
import { quoteId, quoteStr, resourceToCql, splitStatements, toPlain } from "./cql";

const { types } = cassandra;

export async function authInfo(client: cassandra.Client) {
  const res = await client
    .execute("SELECT name, value FROM system_views.settings WHERE name IN ('authenticator', 'authorizer', 'role_manager')")
    .catch(() => null);
  const get = (n: string) => (res?.rows.find((r) => r.name === n)?.value as string | undefined) ?? null;
  const authenticator = get("authenticator");
  const authorizer = get("authorizer");
  return {
    authenticator,
    authorizer,
    roleManager: get("role_manager"),
    authenticationEnabled: authenticator ? !/AllowAll/.test(authenticator) : null,
    authorizationEnabled: authorizer ? !/AllowAll/.test(authorizer) : null,
  };
}

export async function listRoles(client: cassandra.Client) {
  const [table, listed] = await Promise.all([
    client.execute("SELECT role, can_login, is_superuser, member_of FROM system_auth.roles").catch(() => null),
    client.execute("LIST ROLES").catch((e: Error) => e),
  ]);
  if (listed instanceof Error && !table) throw listed;
  const extra = new Map(
    (listed instanceof Error ? [] : listed.rows).map((r) => [
      r.role as string,
      { options: toPlain(r.get("options")), datacenters: r.get("datacenters") as string | undefined },
    ]),
  );
  const rows = table?.rows ?? (listed as cassandra.types.ResultSet).rows;
  const roles = rows.map((r) => ({
    name: r.role as string,
    login: (r.can_login ?? r.login) as boolean,
    superuser: (r.is_superuser ?? r.super) as boolean,
    memberOf: ((r.member_of as string[] | null) ?? []).slice().sort(),
    options: extra.get(r.role)?.options ?? null,
    datacenters: extra.get(r.role)?.datacenters ?? null,
  }));
  return roles.sort((a, b) => a.name.localeCompare(b.name));
}

export async function rolePermissions(client: cassandra.Client, role: string) {
  const res = await client.execute(`LIST ALL PERMISSIONS OF ${quoteStr(role)}`);
  return res.rows.map((r) => ({
    grantedTo: r.role as string,
    inherited: r.role !== role,
    resource: r.resource as string,
    resourceCql: resourceToCql(r.resource as string),
    permission: r.permission as string,
  }));
}

export async function clusterInfo(client: cassandra.Client) {
  const [local, peers] = await Promise.all([
    client.execute("SELECT * FROM system.local"),
    client
      .execute("SELECT * FROM system.peers_v2")
      .catch(() => client.execute("SELECT * FROM system.peers")),
  ]);
  const l = local.first();
  const hostsUp = new Map(client.hosts.values().map((h) => [h.hostId?.toString(), h.isUp()]));
  const node = (r: cassandra.types.Row, isLocal: boolean) => ({
    address: String(toPlain(isLocal ? r.get("broadcast_address") ?? r.get("rpc_address") : r.get("peer"))),
    nativeAddress: toPlain(isLocal ? r.get("rpc_address") : r.get("native_address") ?? r.get("rpc_address")),
    datacenter: r.get("data_center") as string,
    rack: r.get("rack") as string,
    version: r.get("release_version") as string,
    hostId: String(toPlain(r.get("host_id"))),
    tokens: ((r.get("tokens") as string[] | null) ?? []).length,
    up: hostsUp.get(String(r.get("host_id"))) ?? null,
    local: isLocal,
  });
  return {
    clusterName: l.get("cluster_name") as string,
    partitioner: l.get("partitioner") as string,
    cqlVersion: l.get("cql_version") as string,
    nativeProtocolVersion: l.get("native_protocol_version") as string,
    keyspace: client.keyspace ?? null,
    nodes: [node(l, true), ...peers.rows.map((r) => node(r, false))],
    auth: await authInfo(client),
  };
}

export async function clusterSettings(client: cassandra.Client) {
  const res = await client.execute("SELECT name, value FROM system_views.settings");
  return res.rows.map((r) => ({ name: r.name as string, value: r.value as string | null }));
}

export async function clusterClients(client: cassandra.Client) {
  const res = await client.execute("SELECT * FROM system_views.clients");
  return res.rows.map((r) => Object.fromEntries(r.keys().map((k) => [k, toPlain(r.get(k))])));
}

export interface ExecuteRequest {
  cql: string;
  keyspace?: string | null;
  pageSize?: number;
  pageState?: string | null;
  consistency?: string;
  trace?: boolean;
  continueOnError?: boolean;
}

export async function execute(client: cassandra.Client, req: ExecuteRequest) {
  const statements = splitStatements(req.cql);
  if (req.keyspace && client.keyspace !== req.keyspace) await client.execute(`USE ${quoteId(req.keyspace)}`);
  const consistency = req.consistency
    ? (types.consistencies as unknown as Record<string, number>)[req.consistency]
    : undefined;
  const results = [];
  for (const statement of statements) {
    const started = performance.now();
    try {
      const res = await client.execute(statement, [], {
        fetchSize: Math.min(Math.max(req.pageSize ?? 200, 1), 5000),
        pageState: statements.length === 1 ? req.pageState || undefined : undefined,
        autoPage: false,
        consistency,
        traceQuery: !!req.trace,
      });
      const elapsedMs = Math.round(performance.now() - started);
      const columns = (res.columns ?? []).map((c) => ({ name: c.name, type: typeName(c.type) }));
      const trace = req.trace && res.info.traceId ? await readTrace(client, res.info.traceId) : null;
      results.push({
        statement,
        columns,
        rows: res.rows?.map((r) => r.values().map(toPlain)) ?? [],
        pageState: res.pageState ?? null,
        elapsedMs,
        warnings: res.info.warnings ?? [],
        queriedHost: res.info.queriedHost,
        trace,
        error: null,
      });
    } catch (e) {
      results.push({ statement, error: errorMessage(e), elapsedMs: Math.round(performance.now() - started) });
      if (!req.continueOnError) break;
    }
  }
  return { results, keyspace: client.keyspace ?? null };
}

async function readTrace(client: cassandra.Client, traceId: cassandra.types.Uuid) {
  try {
    const t = await client.metadata.getTrace(traceId);
    return {
      traceId: traceId.toString(),
      coordinator: String(t.coordinator),
      durationMicros: t.duration,
      events: t.events.map((e) => ({
        activity: e.activity,
        source: String(e.source),
        elapsedMicros: e.elapsed,
        thread: e.thread,
      })),
    };
  } catch (e) {
    return { traceId: traceId.toString(), error: errorMessage(e) };
  }
}

function typeName(type: { code: number; info?: unknown; customTypeName?: string }): string {
  const { dataTypes } = types;
  const info = type.info as any;
  if (type.code === dataTypes.udt) return info?.name ?? "udt";
  if (type.code === dataTypes.custom) {
    const raw = type.customTypeName ?? (typeof info === "string" ? info : "custom");
    const vector = raw.match(/VectorType\(org\.apache\.cassandra\.db\.marshal\.(\w+)Type\s*,\s*(\d+)\)/);
    return vector ? `vector<${vector[1]!.toLowerCase()}, ${vector[2]}>` : raw;
  }
  return ((types as any).getDataTypeNameByCode(type) as string | undefined) ?? String(type.code);
}

export function errorMessage(e: unknown): string {
  if (e instanceof cassandra.errors.NoHostAvailableError) {
    const inner = Object.entries(e.innerErrors ?? {})
      .map(([host, err]) => `${host}: ${(err as Error).message}`)
      .join("; ");
    return inner ? `No host available — ${inner}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}
