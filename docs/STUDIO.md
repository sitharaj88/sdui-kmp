# sdui-kmp Studio

The Studio is sdui-kmp's browser-based admin console — a Compose Multiplatform / Wasm app
(`:studio-web`) that talks to the Studio admin REST API (`:studio-server`). Editors sign in,
browse the catalogue of screens served by the live system, open a screen to see its raw
JSON, and watch the same `SduiHost` the production clients use render that screen in a live
preview pane. This loop ("log in → list screens → open one → see JSON → preview") is the
skeleton phase; the visual drag-and-drop tree editor and inline JSON editing land in S5.

## Running locally

The Studio expects three services in three terminals. Ports are fixed so the bundled CORS
configuration on `:studio-server` works without per-developer overrides.

```bash
# Terminal 1 — end-user sample server (port 8080).
./gradlew :samples:sample-server:run

# Terminal 2 — Studio admin backend (port 8081).
# Local dev boots against an in-memory H2 database whose schema is created from the Exposed
# table definitions (no Postgres/Flyway needed). For a Postgres deployment the schema is owned
# by the Flyway migrations under studio-server/db/migrations/ (see "Database & migrations").
./gradlew :studio-server:run

# Terminal 3 — Studio frontend (Webpack dev server, default port 8082).
./gradlew :studio-web:wasmJsBrowserDevelopmentRun
```

Open the URL the third command prints (typically <http://localhost:8082>). The app boots
into the login screen; log in with the seeded editor account (see below), and the screens
list pulls from `GET /admin/screens` on port 8081.

## Default editor account

A single editor account is seeded so the Studio is usable without an out-of-band user-creation
step (on the Postgres path this lives in `V1__studio_initial.sql`; on the local H2 path it is
seeded at startup).

Credentials (development only — change before deploying to staff machines):

- Email: `editor@studio.local`
- Password: `change-me`
- Role: `editor`

## Screenshot

[ Studio screenshot here once UI is real ]

The skeleton phase ships a Material3 `Scaffold` with a `NavigationRail` (tabs: **Screens**,
**Audit**) and a two-pane editor view (read-only JSON on the left, live `SduiHost` preview
on the right). All visuals will be redone for S5 once the visual editor lands.

## Architecture pointers

- The frontend lives in `studio-web/`. It is a Compose Multiplatform Wasm app — Wasm-only,
  not published to Maven Central.
- The preview pane in `ScreenDetailView.kt` decodes `Screen` JSON via `SduiJson` and feeds
  it into the production `SduiHost` through `staticScreenSource`. That's the entire reason
  the Studio is a Compose-MP app instead of a React/TS frontend: the preview is byte-for-
  byte the same renderer the user runs in production.
- The admin JWT held in `state/AuthState.kt` is intentionally separate from any end-user
  bearer token. The Ktor client lives inside `api/StudioApi.kt` and threads its own token
  via `installBearerAuth`; nothing else on the page can read it.

## Roadmap

- **S5 — Visual editor.** Monaco-style JSON editor with diagnostics, drag-and-drop tree
  view of widget nodes, live diff between draft and published versions, undo/redo, and
  proper Compose-Wasm UI tests once skiko's resource bundling is reliable.
- **S6 — A/B targeting.** Audience rules, percentage rollouts, and scheduled flips wired
  into the publish flow so editors can ship variants without redeploying the server.

Until S5 lands, the Studio is intentionally minimal: it proves the architecture works
end-to-end and gives editors a place to sign in, browse, and confirm what users will see.
