# Cassandra Client

A web client for Apache Cassandra in the spirit of Prisma Studio, built with **Bun + Elysia** (API) and React + CodeMirror (UI).

## Running

```bash
bun install
bun run build   # bundles the UI into dist/
bun run start   # http://127.0.0.1:4310
```

Development (rebuilds the UI and restarts the server on changes):

```bash
bun run dev
```

Environment variables:

| Var | Default | |
| --- | --- | --- |
| `PORT` | `4310` | HTTP port |
| `HOST` | `127.0.0.1` | Bind address. The API runs arbitrary CQL with the stored credentials, so keep it local. |
| `CASSANDRA_CLIENT_CONFIG` | `~/.config/cassandra-client/connections.json` | Saved connections (chmod 600; passwords are stored in plain text) |

## Features

- **Connections**: multiple saved profiles (hosts, port, DC auto-detection, user/password, SSL, color), with a connection test.
- **Schema browser**: keyspaces, tables, materialized views, UDTs, functions/aggregates, virtual tables (`system_views`).
- **Data grid**:
  - Pagination with paging state, filters (`=`, `<`, `IN`, `CONTAINS`, `CONTAINS KEY`, `LIKE`), and `ALLOW FILTERING`.
  - Inline editing with staged changes ("Save N changes"), adding and deleting rows, and counter columns.
  - Editor for collections, UDTs and vectors as JSON.
  - CSV/JSON export, `COUNT(*)`, and column visibility.
- **Schema management**:
  - Create, alter (replication) and drop keyspaces.
  - Create tables with a visual designer, add, drop and rename columns, edit table options, and truncate or drop tables.
  - Create and drop indexes (SAI, legacy, SASI).
  - Show DDL with server-side `DESCRIBE`.
- **CQL editor**:
  - Highlighting, autocomplete, multiple statements and per-statement results.
  - Consistency level, tracing and "load more" paging.
  - History and export.
- **Roles & permissions**:
  - Create, edit and drop roles (login, superuser, password, datacenter access).
  - Grant and revoke role membership.
  - **Permission matrix** per resource: direct vs inherited grants, staged changes and a CQL preview before applying.
- **Cluster**: nodes (DC, rack, version, status), `system_views.settings`, and connected clients.

Every value goes through Cassandra's own `toJson()` / `fromJson()`, so all CQL types round-trip losslessly (including bigint/varint/decimal beyond JS number precision).

Requires Cassandra 4.0+ (uses server-side `DESCRIBE` and virtual tables); tested against 5.0.
