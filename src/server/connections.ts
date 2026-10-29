import cassandra from "cassandra-driver";
import { mkdir } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

export interface ConnectionProfile {
  id: string;
  name: string;
  contactPoints: string[];
  port: number;
  localDataCenter?: string;
  username?: string;
  password?: string;
  ssl?: boolean;
  color?: string;
}

export type PublicProfile = Omit<ConnectionProfile, "password"> & { hasPassword: boolean; connected: boolean };

const CONFIG_PATH =
  process.env.CASSANDRA_CLIENT_CONFIG ?? join(homedir(), ".config", "cassandra-client", "connections.json");

const DEFAULT_PROFILES: ConnectionProfile[] = [
  { id: "local", name: "Local", contactPoints: ["127.0.0.1"], port: 9042, color: "#3b82f6" },
];

let profiles: ConnectionProfile[] | null = null;
const clients = new Map<string, cassandra.Client>();
const pending = new Map<string, Promise<cassandra.Client>>();

async function load(): Promise<ConnectionProfile[]> {
  if (profiles) return profiles;
  const file = Bun.file(CONFIG_PATH);
  profiles = (await file.exists()) ? ((await file.json()) as ConnectionProfile[]) : structuredClone(DEFAULT_PROFILES);
  return profiles;
}

async function save() {
  await mkdir(dirname(CONFIG_PATH), { recursive: true });
  await Bun.write(CONFIG_PATH, JSON.stringify(profiles, null, 2));
  // Profiles may hold passwords: keep the file private to the current user.
  await Bun.$`chmod 600 ${CONFIG_PATH}`.quiet().nothrow();
}

const toPublic = ({ password, ...p }: ConnectionProfile): PublicProfile => ({
  ...p,
  hasPassword: !!password,
  connected: clients.has(p.id),
});

export async function listProfiles() {
  return (await load()).map(toPublic);
}

export async function getProfile(id: string) {
  const p = (await load()).find((x) => x.id === id);
  if (!p) throw new HttpError(404, `Connection ${id} not found`);
  return p;
}

export async function upsertProfile(input: Omit<ConnectionProfile, "id"> & { id?: string }) {
  const list = await load();
  const existing = input.id ? list.find((x) => x.id === input.id) : undefined;
  if (existing) {
    // An omitted password keeps the stored one; an empty string clears it.
    const password = input.password === undefined ? existing.password : input.password || undefined;
    Object.assign(existing, { ...input, password });
    await disconnect(existing.id);
    await save();
    return toPublic(existing);
  }
  const profile: ConnectionProfile = { ...input, id: crypto.randomUUID(), password: input.password || undefined };
  list.push(profile);
  await save();
  return toPublic(profile);
}

export async function deleteProfile(id: string) {
  await disconnect(id);
  profiles = (await load()).filter((x) => x.id !== id);
  await save();
}

function clientOptions(p: ConnectionProfile, dc?: string): cassandra.ClientOptions {
  return {
    contactPoints: p.contactPoints,
    protocolOptions: { port: p.port },
    localDataCenter: dc,
    credentials: p.username ? { username: p.username, password: p.password ?? "" } : undefined,
    sslOptions: p.ssl ? { rejectUnauthorized: false } : undefined,
    socketOptions: { connectTimeout: 8000, readTimeout: 60000 },
    queryOptions: { prepare: false },
    policies: dc
      ? undefined
      : { loadBalancing: new cassandra.policies.loadBalancing.RoundRobinPolicy() },
  };
}

/** Connects using the profile's DC, auto-detecting it from system.local when not configured. */
export async function openClient(p: ConnectionProfile): Promise<cassandra.Client> {
  let dc = p.localDataCenter?.trim() || undefined;
  if (!dc) {
    const probe = new cassandra.Client(clientOptions(p));
    try {
      await probe.connect();
      dc = (await probe.execute("SELECT data_center FROM system.local")).first()?.get("data_center");
    } finally {
      await probe.shutdown().catch(() => {});
    }
  }
  const client = new cassandra.Client(clientOptions(p, dc));
  try {
    await client.connect();
  } catch (e) {
    await client.shutdown().catch(() => {});
    throw e;
  }
  return client;
}

export async function getClient(id: string): Promise<cassandra.Client> {
  const existing = clients.get(id);
  if (existing) return existing;
  let promise = pending.get(id);
  if (!promise) {
    promise = getProfile(id)
      .then(openClient)
      .then((c) => {
        clients.set(id, c);
        return c;
      })
      .finally(() => pending.delete(id));
    pending.set(id, promise);
  }
  return promise;
}

export async function disconnect(id: string) {
  const c = clients.get(id);
  clients.delete(id);
  await c?.shutdown().catch(() => {});
}

export async function disconnectAll() {
  await Promise.all([...clients.keys()].map(disconnect));
}

export class HttpError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
