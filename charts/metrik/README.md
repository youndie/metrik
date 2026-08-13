## Deploying to Kubernetes with Helm

metrik ships with a Helm chart designed for deployment on Kubernetes using
[Traefik](https://traefik.io/) as the Ingress controller. A plain `Ingress` is supported too.

### 1. Prerequisites (Authentication)

metrik has no login of its own and is agnostic to your authentication provider. It relies on a
"trusted handoff" architecture:

1. It sits behind your existing auth layer (SSO, OAuth2 Proxy, Keycloak, etc.).
2. It expects the ingress controller to handle authentication.
3. It reads user details from trusted HTTP headers.

Before installing, ensure you have a **Traefik Middleware** (e.g. connected to `oauth2-proxy`) that
authenticates requests and forwards the following headers:

* `X-Auth-Request-User` (user identifier)
* `X-Auth-Request-Email` (user email)

A route rendered without that middleware publishes the dashboard to anyone who resolves the host —
this is the one prerequisite you cannot skip.

#### Example: Middleware Configuration

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: auth-mw
  namespace: auth
spec:
  forwardAuth:
    address: http://oauth2-proxy.auth.svc.cluster.local:4180
    trustForwardHeader: true
    authResponseHeaders:
      - X-Auth-Request-User
      - X-Auth-Request-Email
```

### 2. Configuration (values.yaml)

Create a `my-values.yaml` for your deployment. metrik stores everything in one SQLite file, so
persistent storage is required.

```yaml
# my-values.yaml

image:
  repository: ghcr.io/youndie/metrik
  tag: latest
  # The tag is mutable, so IfNotPresent would pin a node to the first image it ever pulled.
  pullPolicy: Always

# One ingest key per installation. Pass it with --set from a secret store rather than
# committing it; the server refuses to start without it.
ingestKey: ""

# Emails allowed into /api/admin/**. Empty means every authenticated user is an admin.
admins: ""

# How long minute-resolution windows are kept. Older data survives as hourly and daily rollups.
retentionHours: 48

# The server monitors itself under this name. Empty disables it.
selfService: metrik-server

# Telegram delivery. Leave the token empty and there is no delivery at all: the server installs a
# no-op notifier, and "send a test" on the alerts screen answers "not delivered" rather than
# pretending. Pass the token with --set, like the ingest key — it reaches the pod through a Secret,
# not as a value in the pod spec, because a bot token can write into your chat.
telegram:
  token: ""
  chatId: ""

# The MCP endpoint, for agents and for the terminal client. Leave the token empty and none of it
# comes into existence: no route, no secret, no ingress bypass. That guard matters more here than
# elsewhere — /mcp goes around the authenticating proxy, so "forgot to set a token" must not turn
# into "published it". Pass it with --set, like the others.
# allowedHosts guards against DNS rebinding; empty means traefik.hostname is used.
mcp:
  token: ""
  allowedHosts: ""

# The browser dashboard. Turn it off for installations read from the terminal or through MCP: a
# page pulls html, wasm, fonts and a fistful of API calls at once, and that fan-out is what grows
# the server's thread pool and with it the memory floor. The API and alerting are unaffected.
web:
  enabled: true

persistence:
  size: 5Gi
  storageClass: local-path

traefik:
  enabled: true
  hostname: metrik.example.com
  certResolver: cloudflare
  authMiddleware:
    name: auth-auth-mw@kubernetescrd
  middlewares:
    - name: auth-auth-error-redirect@kubernetescrd
```

Prefer a plain Ingress? Leave `traefik.enabled: false` and fill in the `ingress` block instead.

### 3. Install

```shell
helm upgrade --install metrik ./charts/metrik \
  -f my-values.yaml \
  --set ingestKey=$METRIK_INGEST_KEY \
  --set telegram.token=$METRIK_TELEGRAM_TOKEN \
  --set telegram.chatId=$METRIK_TELEGRAM_CHAT_ID \
  --set mcp.token=$METRIK_MCP_TOKEN \
  --namespace metrik --create-namespace --atomic
```

Already keep the bot token in a Secret of your own? Point the chart at it instead of passing the
value: `telegram.existingSecret` plus `telegram.secretKey`. Same pair as `existingSecret`/`secretKey`
for the ingest key.

One installation delivers to one chat. Two contours that share a chat quickly train everyone to
ignore it — give staging its own.

### 4. Point your services at it

The chart creates two services:

| Service | Port | Purpose |
|---|---|---|
| `<release>-metrik` | 8080 | API, dashboard and `/mcp` — one binary serves all three |
| `<release>-metrik-ingest` | 9999/UDP | agents inside the cluster |

Agents use the UDP one:

```kotlin
endpoint = "metrik-metrik-ingest.metrik.svc:9999"
```

**Never route the ingest port through an ingress.** UDP is trivially spoofed and the key in the
packet only stops accidents. It belongs inside the cluster.

## Notes on the deployment shape

* **One replica, `strategy: Recreate`.** The database is a file on a `ReadWriteOnce` volume; a
  second replica would write into the same one.
* **A mutable tag needs a restart.** `helm upgrade` does not recreate a pod when the rendered spec
  is unchanged, so a fresh `:latest` will not land on its own. Either pin an immutable tag or run
  `kubectl rollout restart deployment/<release>-metrik` after the upgrade.
* **One container serves everything.** The dashboard bundle ships inside the server image and is
  served by the binary itself, so API and UI can never drift apart across a release. `staticFiles`
  does not exist on Kotlin/Native, so MIME types, ETags and precompressed twins are written by hand
  — the `.gz` files are produced once at image build, because there is no compression plugin for
  native either.
* **The MCP route bypasses the auth middleware, and only exists with a token.** A machine cannot
  fill in a login form, so `/mcp` is guarded by a bearer token instead of the proxy. Leave
  `mcp.token` empty and the route, the secret and the bypass are all absent — an unset value has to
  mean closed.
* **Small by design, but do not squeeze the memory limit.** Requests of 30m CPU and 32Mi are plenty
  and the process idles near 35Mi, but the resting figure is not what to size against. Concurrent
  requests grow a thread pool that never shrinks, and measurement puts the cost at roughly 1.9MiB
  per thread with a ceiling around 64 threads — so a busy dashboard settles well above its idle
  number and stays there. 128Mi is not survivable (the pod died), 256Mi sits between the floor and
  the peak; **384–512Mi is the working range**. Turning the dashboard off (`web.enabled: false`)
  removes the fan-out that causes this in the first place.
