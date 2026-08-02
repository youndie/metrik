# metrik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-orange?logoColor=white)](https://kotlinlang.org)
[![wasm](https://img.shields.io/badge/Wasm-purple?logoColor=white)](https://kotlinlang.org)
[![metrik agent](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/metrik/agent?name=agent&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/metrik/agent)
[![Docker Image Version](https://img.shields.io/badge/server-latest-blue?logo=docker)](https://github.com/youndie/metrik/pkgs/container/metrik)

Lightweight monitoring for Ktor services, written in Kotlin with a focus on portability and extremely
low overhead.

Unlike traditional monitoring stacks, metrik is one self-contained binary compiled with Kotlin/Native
and one SQLite file. No JVM, no time-series database, no scrape configuration. The agent costs the
monitored service **106 nanoseconds per request** and one UDP packet per minute.

## Overview

metrik answers four questions — how many requests, how many errors, how slow, is memory running
out — and writes to Telegram when the answers get worse.

- Ktor plugin that measures and aggregates in-process, no scraping and no service discovery
- Percentiles from exponential histograms merged across instances, not averaged
- Route templates as series labels (`/users/{id}`), so cardinality stays bounded
- Deploy markers on every chart: "worse" is only useful with "worse after what"
- Alerts on error rate, latency, memory and silence, with hysteresis and a cooldown
- Zero-runtime-dependency deployment via Kotlin/Native
- SQLite storage using sqlx4k for multiplatform database access
- Authentication via reverse proxy (supports OAuth2-Proxy, Traefik, Nginx)
- Compose Multiplatform dashboard, light/dark theme

## Tech Stack

### Agent

- Kotlin Multiplatform (JVM, linuxX64, linuxArm64, macosArm64)
- Ktor server plugin API, ktor-network for UDP
- No JMX: `Runtime` and `ProcessHandle` on the JVM, `/proc` on Linux

### Server

- Ktor (native CIO engine)
- Kotlin/Native
- SQLite (sqlx4k)
- kotlinx.serialization

### Dashboard

- Compose Multiplatform for Wasm — production
- Desktop JVM target — development only, because the wasm build is slow
- MaterialKolor for the Material 3 colour scheme

Everything is served as static files by nginx; the native server exposes only JSON.

## Instrumenting a service

```kotlin
install(Metrik) {
    service = "orders-api"
    apiKey = System.getenv("METRIK_KEY")   // one key per installation, not per service
    endpoint = "metrik-ingest:9999"
    release = System.getenv("APP_VERSION") // optional; draws deploy markers on charts
}
```

That is the whole setup. Services are not registered anywhere — the first packet creates one.

### Add dependencies

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "WipSnapshots"
        url = uri("https://reposilite.kotlin.website/snapshots")
    }
}

dependencies {
    implementation("ru.workinprogress.metrik:agent:$metrik_version")
}
```

The agent never blocks a request and never throws into the host pipeline. If the server is
unreachable, the DNS name does not resolve or the queue overflows, it counts the loss and keeps
serving traffic.

## Running the server

```shell
docker run -p 8080:8080 -p 9999:9999/udp \
  -v ./data:/data \
  -e METRIK_INGEST_KEY=<secret> \
  ghcr.io/youndie/metrik:latest
```

The dashboard is a separate nginx container (`ghcr.io/youndie/metrik-web`) — a native binary has no
way to serve a wasm bundle.

## Authentication

metrik does not implement its own user login. Instead it trusts upstream authentication headers
provided by middleware such as:

- oauth2-proxy
- Traefik ForwardAuth
- NGINX auth_request

metrik reads the following headers:

- `X-Auth-Request-User` — unique user identifier
- `X-Auth-Request-Email` — user email

If these headers are missing, metrik returns 401 Unauthorized. **Do not run it without such a
proxy** — the dashboard would be open to anyone who reaches the port.

`METRIK_ADMINS` narrows the admin routes to a list of emails. Left empty, every authenticated user
is an admin: an installation belongs to one team.

### For Traefik:

```yaml
authResponseHeaders:
  - X-Auth-Request-User
  - X-Auth-Request-Email
```

## Ingest port

The ingest port speaks UDP and is **not** authenticated in any meaningful sense: the key in the
packet stops accidents, not attackers. Keep it inside the cluster and never expose it through an
ingress.

## 🚀 Deployment

metrik is designed to run on Kubernetes. We provide an official Helm chart.

👉 **[Read the Deployment Guide](charts/metrik/README.md)** to learn how to install metrik with
Helm, configure Traefik IngressRoute, and set up SSO integration.

## What metrik is not

- **Not a tracing system.** No spans, no correlation ids. metrik answers "is this service in
  trouble", not "why was this one request slow".
- **Not an SLA reporting tool.** Percentiles come from histogram buckets, so they carry up to 20%
  relative error. Enough for trends and alerts, not for a contract.
- **Not multi-tenant.** One installation belongs to one team. Need isolation? Run a second one —
  it is one binary and one file.

## Documentation

[docs/](docs/README.md) — architecture research with the reasoning behind every decision, features
with BDD scenarios, the wire protocol, and per-service documentation. Written in Russian.

The research document is worth reading before changing anything: several decisions here are
counter-intuitive and were made against evidence, not taste.
