# Design: Secure the ThesisGuard ↔ OpenBB connection with per-client API keys

**Date:** 2026-06-23
**Status:** Approved (pending spec review)
**Repos touched:** `OpenBB` (fork, server side) and `thesisguard-api` (client side)

## Problem

The self-hosted OpenBB Platform API at `https://openbb.kingheung.com` is currently
**open to the public** — any caller can hit every `/api/v1/...` endpoint. ThesisGuard's
`OpenBbClient` connects with only a base URL and sends no credentials. We want to lock the
API down so only authorized clients can use it, with **per-client API keys that can be
revoked independently** (revoking one key must not affect the others).

## Goals

- Require a valid credential on every OpenBB API request.
- Support multiple independent clients, each with its own key.
- Revoke a single client's key without rotating the others.
- Keep all secrets out of git (consistent with the existing `application-local.yaml` / `.env` pattern).
- Minimal, maintainable change that survives OpenBB upstream merges.

## Non-goals (YAGNI)

- Runtime revocation without redeploy (no admin API / datastore).
- Per-key rate limiting or quotas.
- OAuth/JWT/session auth.
- Authn for human/browser users (the consumers are backend apps; a browser can still send the header).

## Decisions

| Decision | Choice | Rationale |
| --- | --- | --- |
| Enforcement point | **Native OpenBB auth extension** (`openbb_core_extension` entry point) | Every command route already depends on `AuthService().auth_hook`; this is the designed extension point, enforced everywhere with no route edits. Self-contained in the fork. |
| Credential type | **API key** in `x-api-key` header | Simple machine-to-machine; per-client keys. (Alternative `Authorization: Bearer <key>` noted; trivially swappable in one place.) |
| Key storage | **`OPENBB_API_KEYS` env var** = JSON map `{client: key}` (option: `OPENBB_API_KEYS_FILE`) | Easiest to manage via Dokploy env; revoke = remove entry + redeploy. |
| Revocation | Remove the entry from `OPENBB_API_KEYS` + redeploy | Independent per-client revocation without touching other keys. |
| Transport | HTTPS only | Already in place: `http://openbb.kingheung.com` returns `301 → https`; Traefik/Dokploy terminates TLS. Keys never travel in cleartext. |

## Key technical findings (verified in the OpenBB source)

- OpenBB has a custom-auth extension mechanism. `AuthService` (`openbb_core/app/service/auth_service.py`)
  loads `OPENBB_API_AUTH_EXTENSION` from the `openbb_core_extension` entry-point group and reads three
  attributes off the entry module: `auth_hook`, `user_settings_hook`, `router`.
- Data command routes only invoke the hook **when `Env().API_AUTH` is true** (`commands.py:132`): that
  branch injects `__authenticated_user_settings` via `Depends(AuthService().user_settings_hook)`.
  **Therefore both `OPENBB_API_AUTH=true` and `OPENBB_API_AUTH_EXTENSION=apikey_auth` are required** — the
  first makes routes call the hook, the second swaps in our key-checking implementation.
- Env vars are read by `openbb_core/env.py`: `OPENBB_API_AUTH`, `OPENBB_API_AUTH_EXTENSION`
  (plus the built-in Basic-auth `OPENBB_API_USERNAME`/`OPENBB_API_PASSWORD`, which we do not use).
- Default Basic auth lives in `openbb_core/api/auth/user.py` (single shared username/password) — insufficient
  for per-client keys, hence the extension.

## Architecture & data flow

```
ThesisGuard (Spring OpenBbClient)
   │  GET /api/v1/... + header  x-api-key: <thesisguard key>
   ▼
Traefik / Dokploy  ── TLS terminate (HTTPS only)
   ▼
OpenBB API (openbb-api, fork)
   • OPENBB_API_AUTH=true             → command routes inject user_settings_hook dependency
   • OPENBB_API_AUTH_EXTENSION=apikey_auth → AuthService loads our hooks
   ▼
apikey_auth.auth_hook → validate x-api-key against key→client map (constant-time)
   → valid: 200 + data     → missing/unknown: 401
```

## Component 1 — OpenBB auth extension (server, in the fork)

Location: `openbb_platform/extensions/apikey_auth/`

```
apikey_auth/
├── pyproject.toml
└── openbb_apikey_auth/
    ├── __init__.py
    └── auth_extension.py
```

`pyproject.toml` registers the entry point:

```toml
[tool.poetry.plugins."openbb_core_extension"]
apikey_auth = "openbb_apikey_auth.auth_extension:router"
```

`auth_extension.py` exposes exactly the three attributes the loader reads:

- `auth_hook` — async FastAPI dependency:
  - Reads the `x-api-key` request header.
  - Loads the configured key map once (module-level) from `OPENBB_API_KEYS` (JSON) or `OPENBB_API_KEYS_FILE`.
  - Validates with `secrets.compare_digest` against each configured key (constant-time; avoids early-exit timing leaks).
  - Raises `HTTPException(401)` on missing/unknown key. **Fails closed:** empty/unparseable config ⇒ reject all.
  - Never logs key values.
- `user_settings_hook` — async dependency: `Depends(auth_hook)` then returns `UserService().read_from_file()`
  so the server's provider credentials/settings continue to drive command execution after auth passes.
- `router` — a minimal `APIRouter` with no extra endpoints (the loader requires the attribute to exist;
  we deliberately do not re-expose the default `/user/me`).

## Component 2 — Deployment (OpenBB Dockerfile + Dokploy)

- Dockerfile: add a `COPY` of the extension dir and
  `pip install --no-cache-dir --no-deps ./openbb_platform/extensions/apikey_auth/`
  (mirrors the existing core/federal_reserve install lines). Entrypoint/launch command unchanged.
- Secrets supplied as **Dokploy environment variables** (not baked into the image):
  - `OPENBB_API_AUTH=true`
  - `OPENBB_API_AUTH_EXTENSION=apikey_auth`
  - `OPENBB_API_KEYS={"thesisguard":"<key>","<other>":"<key>"}`

## Component 3 — ThesisGuard client (this repo)

- `OpenBbProperties`: add optional `String apiKey` (nullable; not `@NotBlank`).
- `OpenBbClient` constructor: when `apiKey` is present, add `.defaultHeader("x-api-key", apiKey)` to the
  `RestClient.Builder`. When absent, send no header (keeps tests and any unauthenticated/local instance working).
- Config:
  - `application.yaml`: documented `openbb.api-key:` placeholder (no value).
  - `application-local.yaml` (gitignored): real `openbb.api-key: <thesisguard key>`.
  - Update secrets docs in `README.md` and `CLAUDE.md`.

## Error handling

- Invalid/missing key ⇒ OpenBB returns `401`.
- `OpenBbClient` maps a `401` to a clear `ApiException` (HTTP 502 upstream) with message
  "OpenBB authentication failed — check openbb.api-key", and logs a warning.
- `fetchProfile` / `fetchFundamentals` currently swallow errors to `null`/empty. Add a warning log on `401`
  there so a misconfigured key does not fail silently (exchange auto-detect / fundamentals degrade quietly,
  but the cause is visible in logs).

## Testing

- **Extension (pytest):** valid key → passes; unknown key → 401; missing header → 401; empty/unparseable
  config → 401 (fail-closed). Constant-time path covered by behavior tests.
- **ThesisGuard (JUnit + MockRestServiceServer):** asserts `x-api-key` header is sent when configured and
  omitted when not; asserts a 401 response surfaces the clear `ApiException` and logs a warning.

## Rollout order (avoids locking ThesisGuard out)

1. Build & deploy the OpenBB image **with the extension installed but `OPENBB_API_AUTH=false`** (still open).
2. Generate keys; put ThesisGuard's key in its `application-local.yaml`; deploy ThesisGuard so it **already
   sends** `x-api-key` (ignored while auth is off).
3. Flip OpenBB to `OPENBB_API_AUTH=true` + `OPENBB_API_AUTH_EXTENSION=apikey_auth` + `OPENBB_API_KEYS`;
   redeploy. Enforcement turns on while ThesisGuard is already authenticated → no break.
4. Verify: a no-key request → `401`; ThesisGuard → `200`.

## Security considerations

- Keys only over HTTPS (verified: http→https 301).
- Keys never committed: env var / gitignored file on the server; `application-local.yaml` on the client.
- Constant-time comparison; fail closed on misconfiguration.
- Keys never written to logs on either side.

## Open items deferred to implementation plan

- Exact key-map JSON schema and parsing/validation helper.
- Whether to also exempt a health endpoint from auth (e.g. for Dokploy health checks) — confirm Dokploy's
  health probe path and decide allowlist.
