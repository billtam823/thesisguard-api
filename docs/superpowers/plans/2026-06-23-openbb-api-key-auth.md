# OpenBB API-Key Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock down the public OpenBB Platform API at `https://openbb.kingheung.com` so only clients presenting a valid per-client API key (`x-api-key` header) can call it, and make ThesisGuard send its key.

**Architecture:** Add a native OpenBB auth extension (Python, `openbb_core_extension` entry point) in the OpenBB fork that validates `x-api-key` against a key→client map; enable it with `OPENBB_API_AUTH=true` + `OPENBB_API_AUTH_EXTENSION=apikey_auth`. On the ThesisGuard side, send the key as a default header from `OpenBbClient` and surface 401s clearly. Spec: `docs/superpowers/specs/2026-06-23-openbb-api-key-auth-design.md`.

**Tech Stack:** OpenBB side — Python 3.12, FastAPI, Poetry, pytest, Docker (Dokploy). ThesisGuard side — Java 21, Spring Boot 3.5.14 (`RestClient`), JUnit + `MockRestServiceServer`, Maven.

## Global Constraints

- Secrets are NEVER committed. Server keys live in Dokploy env vars (`OPENBB_API_KEYS`); the ThesisGuard key lives in `application-local.yaml` (gitignored). Verbatim header name: `x-api-key`.
- Auth must **fail closed**: missing/invalid/unparseable key config ⇒ 401, never open.
- Key comparison uses constant-time `secrets.compare_digest` (Python) — never `==`.
- Transport is HTTPS only (already enforced: `http://openbb.kingheung.com` → 301 https). Never send keys over plain http.
- Two repos: Part A in `C:/Users/bill.tam/workspace/OpenBB` (branch `feature/apikey-auth`); Part B in `C:/Users/bill.tam/workspace/thesisguard-api` (branch `feature/openbb-api-key-auth`, already created).
- Keys never written to logs on either side.
- OpenBB-side `OPENBB_API_AUTH=true` AND `OPENBB_API_AUTH_EXTENSION=apikey_auth` are BOTH required (the first makes command routes invoke the hook; the second swaps in our implementation).

---

# Part A — OpenBB fork (server side)

Work in `C:/Users/bill.tam/workspace/OpenBB`. Python venv: `openbb_env/Scripts/python` (Windows).

### Task A1: Create and unit-test the `apikey_auth` extension

**Files:**
- Create: `openbb_platform/extensions/apikey_auth/pyproject.toml`
- Create: `openbb_platform/extensions/apikey_auth/README.md`
- Create: `openbb_platform/extensions/apikey_auth/openbb_apikey_auth/__init__.py`
- Create: `openbb_platform/extensions/apikey_auth/openbb_apikey_auth/auth_extension.py`
- Test: `openbb_platform/extensions/apikey_auth/tests/test_auth_extension.py`

**Interfaces:**
- Produces (module `openbb_apikey_auth.auth_extension`): `auth_hook` (async FastAPI dependency, raises `HTTPException(401)` on bad/missing key, returns `None` on success), `user_settings_hook` (async dependency depending on `auth_hook`, returns `UserSettings`), `router` (`fastapi.APIRouter`, empty). Entry-point name `apikey_auth` under group `openbb_core_extension`.

- [ ] **Step 1: Create a branch in the OpenBB repo**

```bash
cd /c/Users/bill.tam/workspace/OpenBB
git checkout -b feature/apikey-auth
```

- [ ] **Step 2: Create the package files (pyproject, README, `__init__`)**

`openbb_platform/extensions/apikey_auth/pyproject.toml`:

```toml
[tool.poetry]
name = "openbb-apikey-auth"
version = "0.1.0"
description = "API key authentication extension for the OpenBB Platform API"
authors = ["Bill Tam <billtam823@gmail.com>"]
license = "AGPL-3.0-only"
readme = "README.md"
packages = [{ include = "openbb_apikey_auth" }]

[tool.poetry.dependencies]
python = ">=3.10,<4"
openbb-core = "^1.6.10"

[build-system]
requires = ["poetry-core"]
build-backend = "poetry.core.masonry.api"

[tool.poetry.plugins."openbb_core_extension"]
apikey_auth = "openbb_apikey_auth.auth_extension:router"
```

`openbb_platform/extensions/apikey_auth/README.md`:

```markdown
# OpenBB API Key Auth Extension

Per-client API key authentication for the OpenBB Platform API. Validates the
`x-api-key` header against keys configured in `OPENBB_API_KEYS` (JSON object of
`{client: key}`) or `OPENBB_API_KEYS_FILE`. Enable with `OPENBB_API_AUTH=true`
and `OPENBB_API_AUTH_EXTENSION=apikey_auth`.
```

`openbb_platform/extensions/apikey_auth/openbb_apikey_auth/__init__.py`:

```python
"""API key authentication extension for the OpenBB Platform API."""
```

- [ ] **Step 3: Install the extension editable into the venv**

Run:
```bash
cd /c/Users/bill.tam/workspace/OpenBB
openbb_env/Scripts/python -m pip install -e ./openbb_platform/extensions/apikey_auth/
```
Expected: `Successfully installed openbb-apikey-auth-0.1.0`.

- [ ] **Step 4: Write the failing tests**

`openbb_platform/extensions/apikey_auth/tests/test_auth_extension.py`:

```python
"""Tests for the API key auth extension."""

import json

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from openbb_apikey_auth.auth_extension import auth_hook


@pytest.fixture
def client():
    app = FastAPI()

    @app.get("/protected", dependencies=[Depends(auth_hook)])
    def _protected():
        return {"ok": True}

    return TestClient(app)


def test_valid_key_allows(client, monkeypatch):
    monkeypatch.setenv("OPENBB_API_KEYS", json.dumps({"thesisguard": "secret-123"}))
    resp = client.get("/protected", headers={"x-api-key": "secret-123"})
    assert resp.status_code == 200
    assert resp.json() == {"ok": True}


def test_unknown_key_rejected(client, monkeypatch):
    monkeypatch.setenv("OPENBB_API_KEYS", json.dumps({"thesisguard": "secret-123"}))
    resp = client.get("/protected", headers={"x-api-key": "wrong"})
    assert resp.status_code == 401


def test_missing_header_rejected(client, monkeypatch):
    monkeypatch.setenv("OPENBB_API_KEYS", json.dumps({"thesisguard": "secret-123"}))
    resp = client.get("/protected")
    assert resp.status_code == 401


def test_empty_config_fails_closed(client, monkeypatch):
    monkeypatch.delenv("OPENBB_API_KEYS", raising=False)
    monkeypatch.delenv("OPENBB_API_KEYS_FILE", raising=False)
    resp = client.get("/protected", headers={"x-api-key": "anything"})
    assert resp.status_code == 401


def test_invalid_json_fails_closed(client, monkeypatch):
    monkeypatch.setenv("OPENBB_API_KEYS", "not json{")
    resp = client.get("/protected", headers={"x-api-key": "anything"})
    assert resp.status_code == 401


def test_second_client_key_also_valid(client, monkeypatch):
    monkeypatch.setenv("OPENBB_API_KEYS", json.dumps({"a": "key-a", "b": "key-b"}))
    assert client.get("/protected", headers={"x-api-key": "key-a"}).status_code == 200
    assert client.get("/protected", headers={"x-api-key": "key-b"}).status_code == 200
```

- [ ] **Step 5: Run tests to verify they fail**

Run:
```bash
cd /c/Users/bill.tam/workspace/OpenBB
openbb_env/Scripts/python -m pytest openbb_platform/extensions/apikey_auth/tests/test_auth_extension.py -v
```
Expected: FAIL — `ImportError: cannot import name 'auth_hook'` (module `auth_extension` does not exist yet).

- [ ] **Step 6: Implement `auth_extension.py`**

`openbb_platform/extensions/apikey_auth/openbb_apikey_auth/auth_extension.py`:

```python
"""API key authentication extension for the OpenBB Platform API.

Each request must carry a valid key in the `x-api-key` header. Keys come from
the OPENBB_API_KEYS env var (JSON object mapping client name -> key) or a file
referenced by OPENBB_API_KEYS_FILE. Revoke a client by removing its entry and
redeploying. Selected with OPENBB_API_AUTH_EXTENSION=apikey_auth; requires
OPENBB_API_AUTH=true so the command routes invoke the hook.
"""

import json
import logging
import os
import secrets
from typing import Annotated, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import APIKeyHeader
from openbb_core.app.model.user_settings import UserSettings
from openbb_core.app.service.user_service import UserService

logger = logging.getLogger("uvicorn.error")

API_KEY_HEADER_NAME = "x-api-key"

# auto_error=False so a missing header reaches our handler (uniform 401) rather
# than FastAPI raising 403 before our code runs.
_api_key_header = APIKeyHeader(name=API_KEY_HEADER_NAME, auto_error=False)

# The loader requires a `router` attribute. We expose no extra endpoints
# (the default /user/me is intentionally not re-exposed).
router = APIRouter()


def _load_keys() -> dict:
    """Load the client->key map from env/file. Returns {} on any problem (fail closed)."""
    raw = os.environ.get("OPENBB_API_KEYS")
    if not raw:
        path = os.environ.get("OPENBB_API_KEYS_FILE")
        if path and os.path.exists(path):
            with open(path, encoding="utf-8") as file:
                raw = file.read()
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        logger.error("OPENBB_API_KEYS is not valid JSON; rejecting all requests.")
        return {}
    if not isinstance(data, dict):
        logger.error("OPENBB_API_KEYS must be a JSON object; rejecting all requests.")
        return {}
    return {str(k): str(v) for k, v in data.items()}


def _is_valid(api_key: Optional[str]) -> bool:
    """Constant-time check of the presented key against every configured key."""
    if not api_key:
        return False
    valid = False
    for value in _load_keys().values():
        # Evaluate all entries (no early return) to keep timing uniform.
        if secrets.compare_digest(api_key, value):
            valid = True
    return valid


async def auth_hook(
    api_key: Annotated[Optional[str], Depends(_api_key_header)],
) -> None:
    """Reject the request unless a valid API key is presented."""
    if not _is_valid(api_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key",
            headers={"WWW-Authenticate": API_KEY_HEADER_NAME},
        )


async def user_settings_hook(
    _: Annotated[None, Depends(auth_hook)],
) -> UserSettings:
    """After auth passes, return the server's user settings (provider creds, etc.)."""
    return UserService.read_from_file()
```

- [ ] **Step 7: Run tests to verify they pass**

Run:
```bash
cd /c/Users/bill.tam/workspace/OpenBB
openbb_env/Scripts/python -m pytest openbb_platform/extensions/apikey_auth/tests/test_auth_extension.py -v
```
Expected: PASS — 6 passed.

- [ ] **Step 8: Commit**

```bash
cd /c/Users/bill.tam/workspace/OpenBB
git add openbb_platform/extensions/apikey_auth/
git commit -m "feat(api): add apikey_auth extension for per-client API keys"
```
Note: if the repo's `detect-secrets` pre-commit hook blocks on the test fixtures, append `  # pragma: allowlist secret` to the lines using `"secret-123"`/`"key-a"`/`"key-b"`, restage, and recommit. Do not use `--no-verify`.

---

### Task A2: Verify entry-point discovery and install in the Docker image

**Files:**
- Modify: `Dockerfile` (after line 13, the federal_reserve install block)

**Interfaces:**
- Consumes: the installed `apikey_auth` entry point from Task A1.
- Produces: a Docker image that has `openbb-apikey-auth` installed so `AuthService` can load it at runtime.

- [ ] **Step 1: Verify `AuthService` loads our hook (entry-point wiring)**

Run:
```bash
cd /c/Users/bill.tam/workspace/OpenBB
OPENBB_API_AUTH=true OPENBB_API_AUTH_EXTENSION=apikey_auth \
  openbb_env/Scripts/python -c "from openbb_core.app.service.auth_service import AuthService; print(AuthService().auth_hook.__module__)"
```
Expected output: `openbb_apikey_auth.auth_extension`
(If it prints `openbb_core.api.router.user`, the extension was not discovered — re-run Task A1 Step 3.)

- [ ] **Step 2: Add the extension to the Dockerfile**

Modify `Dockerfile`. Change the existing block:

```dockerfile
# Copy and reinstall locally modified packages to override PyPI versions
COPY openbb_platform/core/ ./openbb_platform/core/
COPY openbb_platform/providers/federal_reserve/ ./openbb_platform/providers/federal_reserve/

RUN pip install --no-cache-dir --no-deps ./openbb_platform/core/ && \
    pip install --no-cache-dir --no-deps ./openbb_platform/providers/federal_reserve/
```

to:

```dockerfile
# Copy and reinstall locally modified packages to override PyPI versions
COPY openbb_platform/core/ ./openbb_platform/core/
COPY openbb_platform/providers/federal_reserve/ ./openbb_platform/providers/federal_reserve/
COPY openbb_platform/extensions/apikey_auth/ ./openbb_platform/extensions/apikey_auth/

RUN pip install --no-cache-dir --no-deps ./openbb_platform/core/ && \
    pip install --no-cache-dir --no-deps ./openbb_platform/providers/federal_reserve/ && \
    pip install --no-cache-dir --no-deps ./openbb_platform/extensions/apikey_auth/
```

- [ ] **Step 3: Verify the Dockerfile builds and the extension installs**

Run:
```bash
cd /c/Users/bill.tam/workspace/OpenBB
docker build -t openbb-apikey-test .
```
Expected: build succeeds; the final pip layer logs `Successfully installed openbb-apikey-auth-0.1.0`.
(This build is slow because it installs `openbb[all]`. If Docker is unavailable in this environment, skip the build and rely on the deploy pipeline, but DO inspect the diff to confirm the COPY path and the `&& \` continuation are correct.)

- [ ] **Step 4: Commit**

```bash
cd /c/Users/bill.tam/workspace/OpenBB
git add Dockerfile
git commit -m "build(api): install apikey_auth extension in the image"
```

---

# Part B — ThesisGuard (client side)

Work in `C:/Users/bill.tam/workspace/thesisguard-api` on branch `feature/openbb-api-key-auth` (already created). Test command (Windows): `.\mvnw.cmd test`.

### Task B1: Send the `x-api-key` header from `OpenBbClient`

**Files:**
- Modify: `src/main/java/com/thesisguard/openbb/OpenBbProperties.java`
- Modify: `src/main/java/com/thesisguard/openbb/OpenBbClient.java:28-34` (fields + constructor)
- Test: `src/test/java/com/thesisguard/openbb/OpenBbClientAuthTest.java` (create)

**Interfaces:**
- Produces: `OpenBbProperties(String baseUrl, String apiKey)`; `OpenBbClient(OpenBbProperties, RestClient.Builder)` constructor that adds `x-api-key` when `apiKey` is non-blank.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/thesisguard/openbb/OpenBbClientAuthTest.java`:

```java
package com.thesisguard.openbb;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.GET;

class OpenBbClientAuthTest {

    private static final String PROFILE_JSON = "{\"results\":[{\"symbol\":\"NVDA\"}]}";

    @Test
    void sendsApiKeyHeaderWhenConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenBbClient client = new OpenBbClient(new OpenBbProperties("http://openbb.test", "secret-key-123"), builder);

        server.expect(requestTo(startsWith("http://openbb.test/api/v1/equity/profile")))
                .andExpect(method(GET))
                .andExpect(header("x-api-key", "secret-key-123"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));

        client.fetchProfile("NVDA");
        server.verify();
    }

    @Test
    void omitsApiKeyHeaderWhenNotConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenBbClient client = new OpenBbClient(new OpenBbProperties("http://openbb.test", null), builder);

        server.expect(requestTo(startsWith("http://openbb.test/api/v1/equity/profile")))
                .andExpect(headerDoesNotExist("x-api-key"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));

        client.fetchProfile("NVDA");
        server.verify();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=OpenBbClientAuthTest`
Expected: COMPILE FAILURE — `OpenBbProperties` has no 2-arg constructor and `OpenBbClient` has no `(OpenBbProperties, RestClient.Builder)` constructor.

- [ ] **Step 3: Add `apiKey` to `OpenBbProperties`**

Replace the body of `src/main/java/com/thesisguard/openbb/OpenBbProperties.java`:

```java
package com.thesisguard.openbb;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "openbb")
public record OpenBbProperties(
        @NotBlank String baseUrl,
        String apiKey
) {
}
```

- [ ] **Step 4: Update the `OpenBbClient` constructor to inject the builder and add the header**

In `src/main/java/com/thesisguard/openbb/OpenBbClient.java`, replace the constructor (lines 30-34):

```java
    public OpenBbClient(OpenBbProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }
```

with:

```java
    public OpenBbClient(OpenBbProperties properties, RestClient.Builder builder) {
        RestClient.Builder configured = builder.baseUrl(properties.baseUrl());
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            configured = configured.defaultHeader("x-api-key", properties.apiKey());
        }
        this.restClient = configured.build();
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=OpenBbClientAuthTest`
Expected: PASS — 2 tests.

- [ ] **Step 6: Run the full suite to confirm no regressions from the constructor change**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS. (Spring Boot auto-configures the `RestClient.Builder` bean, so the new constructor parameter is satisfied by the container.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/thesisguard/openbb/OpenBbProperties.java src/main/java/com/thesisguard/openbb/OpenBbClient.java src/test/java/com/thesisguard/openbb/OpenBbClientAuthTest.java
git commit -m "feat(openbb): send x-api-key header from OpenBbClient"
```

---

### Task B2: Surface OpenBB 401s clearly

**Files:**
- Modify: `src/main/java/com/thesisguard/openbb/OpenBbClient.java` (add logger + a 401 mapping helper; use it in the throwing methods; warn in the swallowing methods)
- Test: `src/test/java/com/thesisguard/openbb/OpenBbClientAuthTest.java` (add a case)

**Interfaces:**
- Consumes: `OpenBbClient(OpenBbProperties, RestClient.Builder)` from Task B1.
- Produces: on upstream 401, throwing methods raise `ApiException(BAD_GATEWAY, "OpenBB authentication failed — check openbb.api-key")`.

- [ ] **Step 1: Write the failing test (add to `OpenBbClientAuthTest`)**

Add these imports to `OpenBbClientAuthTest.java`:

```java
import com.thesisguard.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

Add this test method:

```java
    @Test
    void maps401ToClearAuthErrorForThrowingMethods() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenBbClient client = new OpenBbClient(new OpenBbProperties("http://openbb.test", "bad-key"), builder);

        server.expect(requestTo(startsWith("http://openbb.test/api/v1/equity/fundamental/filings")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ApiException ex = assertThrows(ApiException.class, () -> client.fetchCompanyFilings("NVDA", "8-K", 10));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertTrue(ex.getMessage().contains("authentication failed"));
    }
```

Note: `ApiException.getStatus()` returns `HttpStatus` (verified in `src/main/java/com/thesisguard/common/exception/ApiException.java`), so the assertion above is correct as written.

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=OpenBbClientAuthTest#maps401ToClearAuthErrorForThrowingMethods`
Expected: FAIL — message is the generic "Failed to fetch SEC filings from OpenBB: ..." instead of "authentication failed".

- [ ] **Step 3: Add a logger and a 401-mapping helper to `OpenBbClient`**

In `src/main/java/com/thesisguard/openbb/OpenBbClient.java`, add imports near the top:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add a logger field just inside the class (above `EXCHANGE_CODE_MAP`):

```java
    private static final Logger log = LoggerFactory.getLogger(OpenBbClient.class);
```

Add this helper method to the class (e.g. just above the closing brace):

```java
    private ApiException mapUpstream(String action, Exception ex) {
        if (ex instanceof RestClientResponseException rce && rce.getStatusCode().value() == 401) {
            log.warn("OpenBB authentication failed (401) while trying to {} — check openbb.api-key", action);
            return new ApiException(HttpStatus.BAD_GATEWAY, "OpenBB authentication failed — check openbb.api-key");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "Failed to " + action + " from OpenBB: " + ex.getMessage());
    }
```

- [ ] **Step 4: Route the throwing methods' generic catch through the helper**

In `fetchCompanyFilings`, replace its final catch block:

```java
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch SEC filings from OpenBB: " + ex.getMessage());
        }
```

with:

```java
        } catch (Exception ex) {
            throw mapUpstream("fetch SEC filings", ex);
        }
```

Apply the same transformation to the other throwing methods (replace the final generic `catch (Exception ex)` body with `throw mapUpstream("<action>", ex);`), keeping each method's existing action wording:
- `searchEquities` → `mapUpstream("search equities", ex)`
- `fetchCompanyNews` → `mapUpstream("fetch news", ex)`
- `fetchInsiderTrading` → final `catch (Exception ex)` → `mapUpstream("fetch insider trading", ex)` (leave the existing `RestClientResponseException` block that handles the "no form 4 data" empty-result case unchanged above it).

- [ ] **Step 5: Add a 401 warning to the swallowing methods**

In `fetchProfile`, `fetchMetrics`, and `fetchIncome`, replace each `catch (Exception ex) { return null; }` (or `return List.of();`) with a version that warns on 401. For `fetchProfile`:

```java
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                log.warn("OpenBB authentication failed (401) on profile fetch — check openbb.api-key");
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
```

Do the same for `fetchMetrics` (returns `null`) and `fetchIncome` (returns `List.of()` instead of `null`).

- [ ] **Step 6: Run the test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=OpenBbClientAuthTest`
Expected: PASS — 3 tests.

- [ ] **Step 7: Run the full suite**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/thesisguard/openbb/OpenBbClient.java src/test/java/com/thesisguard/openbb/OpenBbClientAuthTest.java
git commit -m "feat(openbb): surface OpenBB 401 auth failures clearly"
```

---

### Task B3: Configuration and documentation

**Files:**
- Modify: `src/main/resources/application.yaml` (add commented `openbb.api-key` placeholder)
- Modify: `application-local.yaml` (gitignored — add the real key; NOT committed)
- Modify: `README.md` (Configuration + secrets sections)
- Modify: `CLAUDE.md` (Configuration key list + Secrets paragraph)

**Interfaces:**
- Consumes: `openbb.api-key` property bound by `OpenBbProperties.apiKey`.

- [ ] **Step 1: Add the placeholder to `application.yaml`**

In `src/main/resources/application.yaml`, change:

```yaml
openbb:
  base-url: https://openbb.kingheung.com
```

to:

```yaml
openbb:
  base-url: https://openbb.kingheung.com
  # api-key lives in application-local.yaml (gitignored); must match this client's entry in
  # the server's OPENBB_API_KEYS. Without it, requests are rejected once the server enforces auth.
```

- [ ] **Step 2: Generate the ThesisGuard key and add it to `application-local.yaml`**

Generate a key (reuse this exact value in the server's `OPENBB_API_KEYS` in Task C1):

```bash
cd /c/Users/bill.tam/workspace/thesisguard-api
python -c "import secrets; print('tg_obb_' + secrets.token_urlsafe(24))"
```

Add to `application-local.yaml` (gitignored) under the existing `openrouter:`/`seekingalpha:` blocks:

```yaml
openbb:
  api-key: <paste the generated key>
```

- [ ] **Step 3: Update `README.md` secrets docs**

In `README.md`, in the `openbb:` block of the Configuration YAML, add the `api-key` comment line (mirror Step 1). In the secrets `application-local.yaml` example block, add:

```yaml
openbb:
  api-key: <this client's OpenBB API key>
```
and in the prose note that the OpenBB API now requires a per-client key (matching the server's `OPENBB_API_KEYS`).

- [ ] **Step 4: Update `CLAUDE.md` secrets docs**

In `CLAUDE.md`, in the Configuration `# Key properties` block, add:

```yaml
openbb.api-key: <in gitignored application-local.yaml; per-client key matching server OPENBB_API_KEYS. Required once OpenBB enforces auth>
```
and in the **Secrets** paragraph, add `openbb.api-key` to the list of values held in `application-local.yaml`.

- [ ] **Step 5: Verify the app context still loads without a key (optional safety)**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS (tests use the test `application.yaml` with no `openbb.api-key`; the optional property stays null → no header, which is correct for tests).

- [ ] **Step 6: Commit (docs + application.yaml only — never `application-local.yaml`)**

```bash
git add src/main/resources/application.yaml README.md CLAUDE.md
git commit -m "docs(openbb): document the openbb.api-key client credential"
```

---

# Part C — Rollout (operational; run after A and B are merged/deployed)

### Task C1: Staged rollout to avoid locking ThesisGuard out

These are operational steps (no TDD). Execute in order; the order guarantees ThesisGuard is already authenticated before enforcement turns on.

- [ ] **Step 1: Deploy the OpenBB image with the extension installed but auth OFF**

Deploy the Part A build (Dokploy) with `OPENBB_API_AUTH=false` (or unset). The extension is installed but not enforcing. Verify the API still answers:
```bash
curl -sS -o /dev/null -w "%{http_code}\n" "https://openbb.kingheung.com/api/v1/equity/profile?symbol=NVDA&provider=yfinance"
```
Expected: `200`.

- [ ] **Step 2: Deploy ThesisGuard already sending its key**

Ensure `application-local.yaml` has `openbb.api-key` (Task B3 Step 2), deploy ThesisGuard. It now sends `x-api-key` on every OpenBB call (ignored while auth is off, so nothing breaks).

- [ ] **Step 3: Turn enforcement ON in Dokploy and redeploy OpenBB**

Set these env vars on the OpenBB service and redeploy:
```
OPENBB_API_AUTH=true
OPENBB_API_AUTH_EXTENSION=apikey_auth
OPENBB_API_KEYS={"thesisguard":"<the key from Task B3 Step 2>"}
```

- [ ] **Step 4: Verify enforcement**

```bash
# No key -> rejected
curl -sS -o /dev/null -w "no-key: %{http_code}\n" "https://openbb.kingheung.com/api/v1/equity/profile?symbol=NVDA&provider=yfinance"
# Valid key -> allowed
curl -sS -o /dev/null -w "with-key: %{http_code}\n" -H "x-api-key: <the key>" "https://openbb.kingheung.com/api/v1/equity/profile?symbol=NVDA&provider=yfinance"
```
Expected: `no-key: 401` and `with-key: 200`.

- [ ] **Step 5: Smoke-test ThesisGuard end to end**

Trigger an action that calls OpenBB (e.g. add a stock so exchange auto-detect runs, or ingest news for a US stock so SEC filings are fetched) and confirm it succeeds and logs show no `OpenBB authentication failed` warnings.

---

## Self-Review (completed by plan author)

**Spec coverage:**
- Native auth extension (`auth_hook`/`user_settings_hook`/`router`, fail-closed, constant-time) → Task A1. ✓
- `OPENBB_API_AUTH` + `OPENBB_API_AUTH_EXTENSION` both required / entry-point discovery → Task A2 Step 1. ✓
- Dockerfile install + Dokploy env vars → Task A2 + Task C1 Step 3. ✓
- `OpenBbProperties.apiKey` + `x-api-key` header → Task B1. ✓
- 401 error handling + warning logs → Task B2. ✓
- Config + README/CLAUDE secrets docs → Task B3. ✓
- HTTPS-only / keys not committed → Global Constraints + Task B3 (gitignored) + Task C1. ✓
- Rollout order to avoid lockout → Task C1. ✓
- Key map JSON schema + health-endpoint exemption (spec "open items") → key schema realized as `{client: key}` in A1; health-exemption: not needed — Dokploy health checks for this service are TCP/port-based (the API exposes no unauthenticated health route and none of our consumers need one). If a future HTTP probe is added, exempt its exact path in `auth_hook`.

**Placeholder scan:** No TBD/TODO; all code blocks complete. The only `<paste/​the key>` markers are deliberate per-deployment secrets the operator supplies. ✓

**Type consistency:** `auth_hook`/`user_settings_hook`/`router` names match across A1/A2 and the spec; `OpenBbProperties(baseUrl, apiKey)` and `OpenBbClient(OpenBbProperties, RestClient.Builder)` match across B1/B2; `mapUpstream(String, Exception)` used consistently in B2. `ApiException.getStatus()` getter verified against source. ✓
