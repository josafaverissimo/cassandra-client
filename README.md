# Cassandra Client

A client for Apache Cassandra in the spirit of Prisma Studio, written in Kotlin with **Compose Multiplatform**. The same UI runs as a **desktop app** and as a **web app** (Kotlin/Wasm).

## Requirements

- JDK 21 (Kotlin and Gradle are provided by the Gradle wrapper)
- A browser with WasmGC support for the web app (current Chrome, Firefox or Safari)

## Running

```bash
./gradlew :app:run         # desktop app
./gradlew :server:run      # web app at http://127.0.0.1:4310
```

Packaging:

```bash
./gradlew :app:createDistributable   # self-contained desktop app in app/build/compose/binaries/main/app
./gradlew :app:packageDeb            # or packageRpm, packageMsi, packageDmg (on the matching OS)
./gradlew :server:installDist        # server plus web UI in server/build/install/server
```

Environment variables:

| Var | Default | |
| --- | --- | --- |
| `PORT` | `4310` | HTTP port (web) |
| `HOST` | `127.0.0.1` | Bind address (web). The API runs arbitrary CQL with the stored credentials, so keep it local. |
| `CASSANDRA_CLIENT_CONFIG` | `~/.config/cassandra-client/connections.json` | Saved connections (chmod 600; passwords are stored in plain text) |

On desktop, UI preferences and query history are kept in `ui-state.json` next to that file; on the web they live in the browser's `localStorage`.

### Tiling compositors

Java ignores the window size assigned by non-reparenting window managers, which leaves part of the window black. The desktop app detects niri, Hyprland, sway, river, dwm, bspwm and xmonad through `XDG_CURRENT_DESKTOP` and restarts itself with `_JAVA_AWT_WM_NONREPARENTING=1`. On any other affected window manager, set that variable yourself.

## Architecture

A browser can't speak Cassandra's native protocol, so the two apps reach the same backend differently:

```
desktop:  Compose UI ──(in-process call)──────────────┐
                                                      ├─► backend ──► Java driver ──► Cassandra
web:      Compose UI (Wasm) ──(HTTP)──► Ktor server ──┘
```

| Module | Target | Contents |
| --- | --- | --- |
| `core` | JVM + Wasm | Models, the `CassandraApi` interface, CQL helpers |
| `backend` | JVM | `CassandraApi` implemented with the Apache Cassandra Java driver |
| `server` | JVM | Ktor server exposing the backend as `/api` and serving the Wasm bundle |
| `app` | JVM + Wasm | The Compose UI (`commonMain`), plus the desktop and web entry points |

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
  - Highlighting, autocomplete (Ctrl+Space), multiple statements and per-statement results.
  - Consistency level, tracing and "load more" paging.
  - History and export.
- **Roles & permissions**:
  - Create, edit and drop roles (login, superuser, password, datacenter access).
  - Grant and revoke role membership.
  - **Permission matrix** per resource: direct vs inherited grants, staged changes and a CQL preview before applying.
- **Cluster**: nodes (DC, rack, version, status), `system_views.settings`, and connected clients.

Every grid value goes through Cassandra's own `toJson()` / `fromJson()`, so all CQL types round-trip losslessly (including bigint/varint/decimal beyond 64-bit precision).

Requires Cassandra 4.0+ (uses server-side `DESCRIBE`, virtual tables and per-request keyspaces from protocol v5); tested against 5.0.

## Web limitations

Compose for Web draws to a canvas, so the browser can't search the page (Ctrl+F), screen-reader support is limited, and only the bundled fonts are available: Inter and JetBrains Mono cover Latin, Greek and Cyrillic, while CJK text and emoji in your data show as empty boxes. The desktop app falls back to system fonts and doesn't have this limitation.

Bundled fonts are under the SIL Open Font License; see `licenses/`.
