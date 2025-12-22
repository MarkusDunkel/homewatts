# PV Management Platform

A production-ready stack for monitoring photovoltaic sites that ingests live data from the GoodWe SEMS API, stores refined snapshots in PostgreSQL, exposes secured REST APIs, and serves a React dashboard with real-time, historical, and demo access flows. The backend now includes a dedicated ingestion profile/worker, demo key lifecycle management, and hardened authentication with rotating refresh tokens.

## Project Layout
```
├── backend/                          # Spring Boot service + collector profile + Dockerfile
│   ├── src/main/java/com/pvmanagement/demo      # Demo access, key issuance, rate limiting
│   ├── src/main/java/com/pvmanagement/scheduler # Collector-only SEMS scheduler
│   ├── src/main/resources/db/migration          # V1..V3 Flyway migrations
│   └── src/main/resources/application-collector.yml # Non-web worker profile
├── frontend/                         # Vite + React 18 dashboard, shadcn/ui, Recharts, demo routes
│   └── src/store                      # Zustand stores for auth, dashboard, settings/i18n
├── infrastructure/README.md          # GCE rebuild + deployment runbook
├── docker-compose.yml                # Traefik + backend API + collector worker + frontend + Postgres
├── Makefile                          # `make backend`, `make frontend`, `make docker-up/down`
└── README.md                         # You are here
```

## Tech Stack & Key Libraries
- **Backend:** Java 17, Spring Boot 3.3 (Web, Security, Data JPA, Validation, Actuator, WebFlux), Flyway, PostgreSQL 15, Resilience4j retry, Auth0 JWT, Bucket4j + Caffeine (demo throttling), Maven build + multi-stage Dockerfile.
- **Frontend:** React 18, TypeScript, Vite 5 with `@vitejs/plugin-basic-ssl`, React Router v6, Zustand, Axios, shadcn/ui + TailwindCSS + Sass modules, date-fns, Recharts 3, lucide-react icons, ESLint flat config + Prettier.
- **Ops:** Docker Compose with Traefik v3 TLS termination, Nginx 1.28 runtime for the SPA, Google Artifact Registry images, GCE automation steps captured in `infrastructure/README.md`.

## Configuration & Environment
Copy the sample file and fill in secrets before running anything:
```
cp .env.example .env
```

### Backend / Compose variables
| Variable | Description |
|----------|-------------|
| `JWT_SECRET` / `JWT_TTL_SECONDS` / `JWT_REFRESH_TTL_SECONDS` / `JWT_REFRESH_COOKIE_SECURE` | HMAC secret + access/refresh lifetimes (seconds) and whether the refresh cookie is marked Secure. |
| `SEMS_ACCOUNT`, `SEMS_PASSWORD`, `SEMS_STATION_ID`, `SEMS_BASE_URL`, `SEMS_REFRESH_INTERVAL_MS` | Credentials + optional overrides passed into `SemsAuthService` and the ingestion scheduler. |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_PORT` | Postgres connection settings shared across Maven + Compose. |
| `APP_ADMIN_EMAIL`, `APP_ADMIN_PASSWORD` | Optional bootstrap admin account consumed by Spring on startup. |
| `DEMO_SECRET`, `DEMO_SESSION_MAX_AGE_HOURS`, `DEMO_DEFAULT_MAX_ACTIVATIONS`, `DEMO_KEY_VALID_DAYS` | Powers `DemoAccessProperties`: token signing secret, cookie TTL, default activation cap, and rolling expiry applied when a key is first redeemed. |
| `APP_HOST`, `ACME_EMAIL` | Used by Traefik to request Let's Encrypt certificates and to build the router rule that fronts the SPA. |

### Frontend variables
| Variable | Description |
|----------|-------------|
| `VITE_API_BASE_URL` | Optional override for the Axios base URL. Defaults to `/api`, which relies on the dev proxy (Vite) or the production Nginx/Traefik wiring.

## Running Locally
You can mix and match the options below depending on whether you want to run everything in Docker or keep the UI/API hot-reloading locally.

### Docker Compose (full stack)
```
docker compose up --build
```
This launches Traefik (80/443), Postgres, the API container (`SPRING_PROFILES_ACTIVE=prod`), the headless collector (`collector` profile + Actuator on 8081 for health checks), and the Nginx-hosted frontend. Traefik routes HTTPS traffic for `APP_HOST` to the frontend container; the backend remains internal on the bridge network.

### Backend API (web profile)
```
cd backend
mvn spring-boot:run
```
The API expects Postgres at `localhost:${DB_PORT:-5432}` and reads credentials from your environment. Flyway migrations (`V1__init_schema.sql`, `V2__add_refresh_tokens.sql`, `V3__demo_access.sql`) run automatically. Use `SPRING_PROFILES_ACTIVE=local` if you also want the SEMS scheduler turned on while running the web tier locally.

### Collector worker (ingestion profile)
The SEMS polling worker is the same jar running with the `collector` profile and the alternate config at `backend/src/main/resources/application-collector.yml`:
```
cd backend
SPRING_PROFILES_ACTIVE=collector mvn spring-boot:run
```
This disables the web server, enables scheduling + Actuator on port 8081, and keeps Flyway off (so only the API service owns schema migrations). The schedule respects `sems.refresh-interval-ms` (default 180 s).

### Frontend dev server
```
cd frontend
npm install
npm run dev
```
Vite serves the SPA on `https://localhost:5173` (because of `@vitejs/plugin-basic-ssl`) and proxies `/api` to `http://127.0.0.1:8080`. Override the API origin with `VITE_API_BASE_URL` when pointing at a remote backend.

### Makefile shortcuts
- `make backend` → `mvn spring-boot:run`
- `make frontend` → `npm run dev`
- `make docker-up` / `make docker-down` → wrapper around `docker compose up/down`.

## Backend Capabilities
### Authentication, sessions, and roles
- `/api/auth/register`, `/api/auth/login`, `/api/auth/me`, `/api/auth/logout`, `/api/auth/refresh` manage access tokens plus a HTTP-only refresh cookie (`SameSite=None`, `Secure` adjustable via config). Tokens are minted by `JwtService`, persisted refresh tokens live in the `refresh_tokens` table, and are rotated via `RefreshTokenService` so leaked cookies cannot be replayed.
- Users carry standard roles (`ROLE_USER`, `ROLE_ADMIN`) plus the newly introduced `ROLE_DEMO`. Refresh TTLs, secure-cookie flags, and access-token TTLs are configurable through `app.jwt.*` properties.

### Demo access flow
- `/api/auth/demo-login/{slug}` lets prospects redeem a demo key without creating credentials. Each slug must exist in `demo_keys.key_id`; keys capture `org`, optional `expires_at`, current activation counts, and whether they are revoked.
- `DemoKeyIssuer` + `DemoTokenService` use `DEMO_SECRET` to sign and verify the short-lived JWT that represents a redemption. `DemoAccessService` auto-creates a synthetic user (`{slug}@demo.pv`) with `ROLE_USER + ROLE_DEMO`, stamps `demo_expires_at`, and records the attempt in `demo_redemptions` (IP + User-Agent).
- Abuse is mitigated through `DemoRateLimiter` (Bucket4j + Caffeine) which caps demo login calls to 30/min per IP, and through server-side enforcement of `max_activations`, rolling expiries (`default-max-activations`, `key-valid-days`), and the ability to flip the `revoked` flag in the database.

### Power-station & measurement APIs
- `/api/powerstations`, `/api/powerstations/{id}`, `/api/powerstations/{id}/dashboard` expose metadata + the latest `powerflow_snapshot` aggregates through `PowerStationService`.
- `/api/measurements/current/{powerStationId}` returns the freshest snapshot; `/api/measurements/history/{powerStationId}` now expects a POST body with `{ "from": ISO8601, "to": ISO8601 }` and streams ordered history points so the frontend can render multi-day charts.
- `/api/sems/sync` (POST, admin-only) lets operators trigger an immediate SEMS pull when needed.

### SEMS ingestion pipeline
- `SemSyncService` handles the full SEMS flow: fetch monitor detail, persist `powerstation` + `powerflow_snapshot` rows (PV/battery/load/grid/genset/microgrid and statuses), and log each run in `sem_sync_log`.
- `SemSyncScheduler` is only active in the `collector` and `local` profiles so you can scale the ingestion worker separately from the web API. The worker config (`application-collector.yml`) turns off Tomcat/Flyway, enables scheduling, narrows logging, and exposes `/actuator/health` on port 8081 for Compose health checks (`collector` service).
- `SemsClient` is a `WebClient` configured with custom filters that automatically attach/refresh the GoodWe token. It retries 401 responses or the common "authorization has expired" body once by forcing `SemsAuthService` to refresh credentials. 429s and upstream 5xx errors bubble up as `TransientUpstreamException`, which Resilience4j (`resilience4j.retry.instances.semsSync`) retries with jitter/backoff.

## Frontend Highlights
### Routes & authentication UX
- SPA routes live in `frontend/src/routes`: `/login`, `/register`, protected `/dashboard`, `/settings`, plus the new `/demo-access/:slug` (auto redeem + redirect) and `/demo-invalid` fallback. `ProtectedRoute` gates private screens based on the session stored in the Zustand `authStore` (persisted in `sessionStorage`).
- `authApi` + `httpClient` attach `Authorization: Bearer <token>` headers when available and transparently call `/api/auth/refresh` via the refresh cookie when they see a 401, updating the store so subsequent calls succeed.

### Dashboard UI & data handling
- `useDashboardData` polls `/api/powerstations/1/dashboard` every 60 s for the combined summary (current snapshot + history). `useDashboardStore` keeps the current point, historical series, and an optional forecast array ready for the dashboard widgets.
- `FlowChart`, `CurrentInfo`, and the accordion-based layout provide animated power-flow context (grid ↔ PV ↔ load ↔ battery), battery/SOC cards, and a system-status module.
- `TrendChart` is powered by Recharts with custom tooltip + legend components, dual Y-axes (power vs SOC), null-gap detection, and date-range filters that use shadcn/ui date pickers. Ticks and labels adjust based on how many days are visible (`getHourTicks`, `getMidnightTicks`, `daytimeResolvedTickFormatter`).

### Settings, localization, and demo cues
- `useTranslation` + `translations.ts` ship English and German copy. The language is auto-detected (preferring German) and persisted in `localStorage` via `settingsStore`. The Settings page lets users change their display name (`/api/users/me` PATCH) and switch languages instantly.
- The top bar shows a "Demo account" badge whenever the authenticated roles include `ROLE_DEMO`, giving marketing teams a clear way to tell live vs sandbox users apart.

### Build & deploy targets
- `frontend/Dockerfile` builds the Vite bundle with `npm ci` and serves it via Nginx 1.28. The bundled `nginx.conf` enforces caching for hashed assets, disables caching for `index.html`, proxies `/api/` to the backend service, and performs SPA fallbacks.

## Docker & Deployment Notes
- `docker-compose.yml` now provisions five services: Traefik (TLS termination + ACME), Postgres (with `/mnt/db/postgres` mount), the backend API (`europe-west1-docker.pkg.dev/.../backend:latest`), the collector (same image, `collector` profile + health check hitting `http://127.0.0.1:8081/actuator/health`), and the frontend (`.../frontend:latest`). The API containers are internal; only Traefik terminates public traffic and forwards HTTPS to the frontend.
- Compose labels configure HSTS and other security headers, while Traefik obtains certs using `ACME_EMAIL` and exposes only `APP_HOST`.
- `infrastructure/README.md` documents the Google Cloud Compute Engine rebuild: deleting the broken VM, creating a new Debian 12 node, attaching a persistent disk for Postgres, hardening `/etc/fstab` with `nofail`, installing Docker/compose, and pushing/pulling images from Artifact Registry (`europe-west1-docker.pkg.dev/pv-management-app/pv-management-app-repo`).

## Demo Access Operations
1. **Seed a key** – insert a row into `demo_keys` (or update an existing one) with a unique `key_id` and the customer-facing `org`. Optional columns (`expires_at`, `max_activations`) let you tighten or loosen access per key.
2. **Share the link** – send `https://<APP_HOST>/demo-access/{key_id}` to prospects. The frontend will call `/api/auth/demo-login/{key_id}`, which issues a signed JWT via `DemoKeyIssuer`, enforces activations/expiry, rate-limits by IP, and signs the user in.
3. **Audit & revoke** – every redemption lands in `demo_redemptions` (IP + user-agent). Setting `demo_keys.revoked = true` or bumping `activations`/`expires_at` governs future access without code changes.
4. **Session handling** – demo users still get standard access/refresh tokens, but they carry `ROLE_DEMO` and an automatically generated `{slug}@demo.pv` email so the UI can flag them. When the key expires, further attempts fail with a clear message surfaced by the `/demo-invalid` page.

## Database & Migrations
- `V1__init_schema.sql` creates the `powerstation`, `powerflow_snapshot`, `users`, `roles`, `user_roles`, and `sem_sync_log` tables (plus indexes) and seeds `ROLE_USER` and `ROLE_ADMIN`.
- `V2__add_refresh_tokens.sql` adds the `refresh_tokens` table + index so refresh cookies can be rotated safely.
- `V3__demo_access.sql` extends the `users` table with `demo_org`, `demo_expires_at`, `last_login_at`, and adds `demo_keys`, `demo_redemptions`, plus seeds `ROLE_DEMO`.

## Testing & Quality
Automated tests are still TODO. When expanding coverage, target the following first:
- Backend: JUnit + Spring Boot tests that cover `SemSyncService` (use WireMock against the SEMS API), demo access edge cases, `RefreshTokenService` rotation, and the `/api/measurements/history` validation path.
- Frontend: Vitest + Testing Library for the dashboard stores/hooks, `TrendChart` filtering logic, auth store refresh flow, and the demo access route handling of success/error navigation.
- CI/CD: hook Maven + npm lint/tests into GitHub Actions, then push images to Artifact Registry only on green builds.





hello