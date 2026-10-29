import { Elysia, t } from "elysia";
import { resolve, sep } from "node:path";
import { authInfo, clusterClients, clusterInfo, clusterSettings, errorMessage, execute, listRoles, rolePermissions } from "./admin";
import {
  deleteProfile,
  disconnect,
  disconnectAll,
  getClient,
  getProfile,
  HttpError,
  listProfiles,
  openClient,
  upsertProfile,
} from "./connections";
import { countRows, deleteRows, incrementCounter, insertRow, queryRows, updateRow } from "./rows";
import { describe, describeKeyspace, describeTable, listKeyspaces } from "./schema";

const HOST = process.env.HOST ?? "127.0.0.1";
const PORT = Number(process.env.PORT ?? 4310);
const DIST = resolve(import.meta.dir, "../../dist");

const profileBody = t.Object({
  id: t.Optional(t.String()),
  name: t.String({ minLength: 1 }),
  contactPoints: t.Array(t.String({ minLength: 1 }), { minItems: 1 }),
  port: t.Number(),
  localDataCenter: t.Optional(t.String()),
  username: t.Optional(t.String()),
  password: t.Optional(t.String()),
  ssl: t.Optional(t.Boolean()),
  color: t.Optional(t.String()),
});

const jsonText = t.String();
const filter = t.Object({ column: t.String(), op: t.String(), value: jsonText });
const tableRef = { keyspace: t.String(), table: t.String() };
const rowsQuery = t.Object({
  ...tableRef,
  filters: t.Optional(t.Array(filter)),
  allowFiltering: t.Optional(t.Boolean()),
  pageSize: t.Optional(t.Number()),
  pageState: t.Optional(t.Nullable(t.String())),
});

const api = new Elysia({ prefix: "/api" })
  .get("/connections", () => listProfiles())
  .post("/connections", ({ body }) => upsertProfile(body), { body: profileBody })
  .put("/connections/:id", ({ params, body }) => upsertProfile({ ...body, id: params.id }), { body: profileBody })
  .delete("/connections/:id", async ({ params }) => {
    await deleteProfile(params.id);
    return { ok: true };
  })
  .post(
    "/connections/test",
    async ({ body }) => {
      // Testing an existing profile without retyping its password reuses the stored one.
      const stored = body.id && body.password === undefined ? await getProfile(body.id).catch(() => null) : null;
      const client = await openClient({ ...body, id: "test", password: body.password ?? stored?.password });
      try {
        const local = (await client.execute("SELECT cluster_name, release_version, data_center FROM system.local")).first();
        return { clusterName: local.cluster_name, version: local.release_version, datacenter: local.data_center };
      } finally {
        await client.shutdown().catch(() => {});
      }
    },
    { body: profileBody },
  )

  .group("/c/:conn", (c) =>
    c
      .resolve(async ({ params }) => ({ client: await getClient(params.conn) }))
      .post("/connect", ({ client }) => clusterInfo(client))
      .post("/disconnect", async ({ params }) => {
        await disconnect(params.conn);
        return { ok: true };
      })
      .get("/cluster", ({ client }) => clusterInfo(client))
      .get("/cluster/settings", ({ client }) => clusterSettings(client))
      .get("/cluster/clients", ({ client }) => clusterClients(client))

      .get("/keyspaces", ({ client }) => listKeyspaces(client))
      .get("/keyspaces/:ks/ddl", async ({ client, params }) => ({ ddl: await describeKeyspace(client, params.ks) }))
      .get("/tables/:ks/:table", ({ client, params }) => describeTable(client, params.ks, params.table))
      .post("/describe", async ({ client, body }) => ({ ddl: await describe(client, body.target) }), {
        body: t.Object({ target: t.String() }),
      })

      .post("/rows/query", ({ client, body }) => queryRows(client, body as any), { body: rowsQuery })
      .post("/rows/count", ({ client, body }) => countRows(client, body as any), { body: rowsQuery })
      .post("/rows/insert", ({ client, body }) => insertRow(client, body), {
        body: t.Object({ ...tableRef, values: t.Record(t.String(), t.Nullable(jsonText)), ttl: t.Optional(t.Number()) }),
      })
      .post("/rows/update", ({ client, body }) => updateRow(client, body), {
        body: t.Object({
          ...tableRef,
          key: t.Record(t.String(), jsonText),
          values: t.Record(t.String(), t.Nullable(jsonText)),
          ttl: t.Optional(t.Number()),
        }),
      })
      .post("/rows/increment", ({ client, body }) => incrementCounter(client, body), {
        body: t.Object({ ...tableRef, key: t.Record(t.String(), jsonText), column: t.String(), delta: t.String() }),
      })
      .post("/rows/delete", ({ client, body }) => deleteRows(client, body), {
        body: t.Object({ ...tableRef, keys: t.Array(t.Record(t.String(), jsonText), { minItems: 1 }) }),
      })

      .post("/execute", ({ client, body }) => execute(client, body), {
        body: t.Object({
          cql: t.String(),
          keyspace: t.Optional(t.Nullable(t.String())),
          pageSize: t.Optional(t.Number()),
          pageState: t.Optional(t.Nullable(t.String())),
          consistency: t.Optional(t.String()),
          trace: t.Optional(t.Boolean()),
          continueOnError: t.Optional(t.Boolean()),
        }),
      })

      .get("/auth", ({ client }) => authInfo(client))
      .get("/roles", ({ client }) => listRoles(client))
      .get("/roles/:role/permissions", ({ client, params }) => rolePermissions(client, decodeURIComponent(params.role))),
  );

const app = new Elysia()
  .onError(({ error, code, set }) => {
    if (error instanceof HttpError) set.status = error.status;
    else if (code === "VALIDATION") set.status = 422;
    else if (code === "NOT_FOUND") set.status = 404;
    else set.status = 400;
    const message = code === "VALIDATION" ? (error as any).message : errorMessage(error);
    return { error: message };
  })
  .use(api)
  .get("/*", async ({ path }) => {
    const target = resolve(DIST, "." + decodeURIComponent(path));
    if (target.startsWith(DIST + sep)) {
      const file = Bun.file(target);
      if (await file.exists()) return file;
    }
    const index = Bun.file(resolve(DIST, "index.html"));
    if (await index.exists()) return index;
    return new Response("UI not built. Run `bun run build` first.", { status: 503 });
  })
  .listen({ hostname: HOST, port: PORT });

console.log(`Cassandra Client running at http://${app.server?.hostname}:${app.server?.port}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    await disconnectAll();
    process.exit(0);
  });
}
