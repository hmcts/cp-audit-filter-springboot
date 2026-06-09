# HMCTS Audit HTTP Starter (Spring Boot 4, Java 21)

A drop-in Spring Boot **starter** that audits every REST interaction by publishing structured audit
events to **ActiveMQ Artemis**.
It captures **request** and (if present) **response** payloads, enriched with headers, query/path
parameters, and metadata.

> Opinionated by default: path parameters are resolved using your **OpenAPI** document. You can swap
> the path-parser if you prefer a different approach.

---

## Documentation

- [Audit Filter Flow](docs/audit-filter-flow.md)
- [Audit Payload — Current Specification](docs/audit-payload-spec-current.md)
- [Audit Payload — Proposed `content` Block](docs/audit-payload-content-proposal.md)
- [Design Concerns & Open Questions](docs/audit-design-concerns.md)

---

## Key Features

- **Zero component scanning**: all beans are created via a single `@AutoConfiguration`.
- **HTTP Audit Filter** (opt-in): enable with `audit.http.enabled=true`.
- **Active–Passive Artemis HA** support (multiple broker endpoints in a single URL).
- **SSL & non-SSL** Artemis connections.
- **Fully externalised connection tuning** (no hardcoded timeouts/retries).
- **Jackson ready** (Java 8 time + Optional modules).
- **Pluggable path-parser** via `RestApiParser` interface.

---

## What’s Captured

- **Request**: body, headers, query params, **path params** (from OpenAPI), context.
- **Response**: body (if any) + request context/headers.
- Exactly **one** (request) to **two** (request + response) events per call, depending on whether
  the response has a body.

---

## Getting Started

### 1) Add the dependency

```gradle
dependencies {
  implementation("uk.gov.hmcts.cp:audit-http-starter:1.0.0")
}
```

### 2) Minimal configuration

```yaml
# Enable the HTTP audit filter and provide your OpenAPI file location (on classpath)
audit:
  http:
    enabled: true
    openapi-rest-spec: "openapi.yaml"

# Artemis (single or HA)
cp:
  audit:
    hosts:
      - artemis-primary.internal
      - artemis-secondary.internal   # optional (HA)
    port: 61616
    user: ${ARTEMIS_USER}
    password: ${ARTEMIS_PASSWORD}
    ssl-enabled: false               # set true for TLS
    # TLS-only (when ssl-enabled: true)
    # truststore: /opt/app/truststore.jks
    # truststore-password: ${TRUSTSTORE_PASSWORD}

    # JMS tuning (all externalised)
    jms:
      reconnect-attempts: -1
      initial-connect-attempts: 10
      retry-interval-ms: 2000
      retry-multiplier: 1.5
      max-retry-interval-ms: 30000
      connection-ttl-ms: 60000
      call-timeout-ms: 15000
```

### 3) OpenAPI requirement (for path parameters)

Place an OpenAPI document on the classpath (e.g., `src/main/resources/openapi.yaml`).
Path parameters are extracted to produce `{key -> value}` pairs in the audit event. If you prefer a
different approach, implement your own `RestApiParser` and register it as a bean.

**Minimal example:**

```yaml
openapi: 3.0.1
info:
  title: Example
  version: "1.0.0"
paths:
  /cases/{caseId}:
    get:
      parameters:
        - name: caseId
          in: path
          required: true
          schema: { type: string }
      responses:
        "200": { description: OK }
```

---

## How It Works (Architecture)

- `ArtemisAuditAutoConfiguration` creates:
    - `ActiveMQConnectionFactory` with **HA URL** (comma-separated broker endpoints) and
      property-driven timeouts/retries.
    - `JmsTemplate` (topic mode, persistent delivery).
    - `ObjectMapper` with JavaTime & Jdk8 modules.
    - `AuditService` (JMS publisher).
    - **HTTP `AuditFilter`** (only when `audit.http.enabled=true`).
    - OpenAPI helpers: `ClasspathResourceLoader`, `OpenAPIParser`, `OpenApiSpecificationParser`,
      path param extractors/services.

- The filter wraps each request/response using `ContentCaching*Wrapper`, builds a structured
  `AuditPayload`, and posts to `jms.topic.auditing.event`.

---

## SSL & HA Examples

### Non-SSL (single or HA)

The starter builds one factory URL like:

```
tcp://brokerA:61616?ha=true,tcp://brokerB:61616?ha=true
```

### SSL (single or HA)

```
tcp://brokerA:61617?sslEnabled=true;trustStorePath=/opt/app/trust.jks;trustStorePassword=*****,
tcp://brokerB:61617?sslEnabled=true;trustStorePath=/opt/app/trust.jks;trustStorePassword=*****;ha=true
```

> All connection tuning (reconnect attempts, intervals, TTL, call timeout) comes from
`cp.audit.jms.*` properties.

---

## Configuration Reference

### `audit.http.*`

| Property                       | Type    | Default | Purpose                                                                                                         |
|--------------------------------|---------|---------|-----------------------------------------------------------------------------------------------------------------|
| `audit.http.enabled`           | boolean | `false` | Toggles the HTTP filter & OpenAPI parsing.                                                                      |
| `audit.http.openapi-rest-spec` | string  |         | Classpath resource or pattern (`openapi.yaml`, `openapi/*.yaml`) to load the OpenAPI spec used for path params. |

### `cp.audit.*`

| Property                       | Type         | Default | Purpose                                   |
|--------------------------------|--------------|---------|-------------------------------------------|
| `cp.audit.hosts`               | list<string> |         | One or more Artemis hosts (HA supported). |
| `cp.audit.port`                | int          | 61616   | Broker port.                              |
| `cp.audit.user`                | string       |         | Username.                                 |
| `cp.audit.password`            | string       |         | Password.                                 |
| `cp.audit.ssl-enabled`         | boolean      | `false` | Enable TLS.                               |
| `cp.audit.truststore`          | path         |         | JKS path (TLS only).                      |
| `cp.audit.truststore-password` | string       |         | JKS password (TLS only).                  |

### `cp.audit.jms.*`

| Property                   | Type   | Default         |
|----------------------------|--------|-----------------|
| `reconnect-attempts`       | int    | `-1` (infinite) |
| `initial-connect-attempts` | int    | `10`            |
| `retry-interval-ms`        | long   | `2000`          |
| `retry-multiplier`         | double | `1.5`           |
| `max-retry-interval-ms`    | long   | `30000`         |
| `connection-ttl-ms`        | long   | `60000`         |
| `call-timeout-ms`          | long   | `15000`         |

---

## Testing Guidance

### Option A — Full auto-config in tests (recommended)

Provide properties and a tiny OpenAPI file under `src/test/resources`:

```java

@SpringBootTest(classes = ArtemisAuditAutoConfiguration.class)
@TestPropertySource(properties = {
        "audit.http.enabled=true",
        "audit.http.openapi-rest-spec=test-openapi.yaml",
        "cp.audit.hosts=localhost",
        "cp.audit.port=61616",
        "cp.audit.user=guest",
        "cp.audit.password=guest",
        "cp.audit.ssl-enabled=false"
})
class ContextTest {
    @Autowired
    ApplicationContext ctx;

    @Test
    void contextLoads() {
        assertNotNull(ctx);
    }
}
```

`src/test/resources/test-openapi.yaml`:

```yaml
openapi: 3.0.1
info: { title: Test, version: "1.0.0" }
paths:
  /ping/{id}:
    get:
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses: { "200": { description: OK } }
```

### Option B — Skip HTTP filter/OpenAPI in tests

```properties
audit.http.enabled=false
```

You can still create or mock the JMS beans if you want to test the publisher in isolation.

> For end-to-end verification, spin up Artemis in Docker and consume messages from
`jms.topic.auditing.event` using a test listener, or view via the Artemis console.

---

## Extensibility

- Replace OpenAPI parsing by providing your own bean implementing:
  ```java
  public interface RestApiParser {
    Map<String, Pattern> getPathPatterns();
  }
  ```
  The filter will continue to enrich events with whatever path-params your parser exposes.

---

## Migration Notes (What Changed)

- **No `@Component`/`@Service` classes** in the library; everything is created in
  `ArtemisAuditAutoConfiguration`.
- **Filter enablement moved** from annotation on class to **`@ConditionalOnProperty` on the bean
  method**.
- **Artemis connection tuning externalised** to `cp.audit.jms.*`.
- **Active–Passive HA** supported by multiple hosts in one URL.
- **Jackson** is centralised; the starter provides an `ObjectMapper` with `JavaTimeModule` and
  `Jdk8Module`.

---

## Security & Observability

- Avoid logging payloads at INFO in production; the library logs concise send results and failures.
- Use your platform’s secret manager for credentials/truststore passwords.
- Consider adding Micrometer counters on success/failure and a health indicator for broker
  reachability (optional).

---

## Design Concerns & Open Questions

See [docs/audit-design-concerns.md](docs/audit-design-concerns.md) for the full list of open decisions,
the Audit spec field mapping, and actions pending from the conversation with Riaz.

### ⚠ Should the audit filter block HTTP requests? — Decision needed (Riaz)

> **Status:** Under discussion. Matt Rich suggests audit should be blocking. To be confirmed with Riaz (Audit tech lead).

This is a foundational decision that drives several others:

| If auditing is **blocking** (synchronous) | If auditing is **non-blocking** (async/fire-and-forget) |
|---|---|
| A broker outage causes HTTP requests to fail | HTTP requests are unaffected by broker outages |
| Simpler to reason about — no events are silently lost | Events may be lost if the broker is unavailable |
| Aligns with Matt Rich's suggestion | Lower operational risk to consuming services |
| Requires synchronous JMS publish | Could use async publisher or a local outbox |

**Impact on guaranteed delivery:** If blocking is chosen, guaranteed delivery comes naturally (the HTTP
request fails if the event cannot be published). If non-blocking is chosen, an outbox/retry mechanism
is needed to avoid silent loss.

**Impact on async logger:** A non-blocking design would suit an async Artemis publisher, but this
complicates delivery guarantees. The two decisions need to be made together.

> Action: confirm with Riaz before implementation.

---

### Audit payload content — alignment with the Audit spec

> **Status:** Under discussion. Matt Rich has reviewed and provided initial feedback.
> Spec reference: [Audit Design — Confluence](https://tools.hmcts.net/confluence/spaces/CTP/pages/109183562/Audit+Design)

Matt Rich's feedback:
- The Audit service **does not need** `http.url` or `http.statusCode` in the payload.
- They **do need** the metadata fields as defined in the existing Audit spec.
- The `content` section is open for improvement and is up for discussion.
- A filter cannot easily include **business-specific fields** without per-endpoint configuration —
  Matt Rich agrees this is a constraint.

#### Background — the spec was designed for CP domain events

The Audit spec was originally designed for the existing CP microservice flows which are entirely
**event-based**, following a **CQRS / event-sourcing** pattern (Command, Query, Update). Every
interaction in CP produces a domain event. Fields like `causation`, `stream.id`, and
`stream.version` are event-sourcing concepts — they track which event caused this one,
which event stream it belongs to, and the version within that stream.

**HTTP audit events from this filter are not CP domain events.** The filter intercepts
cross-cutting HTTP traffic at the infrastructure level — it sits outside the CP event model
entirely. It is therefore unclear whether the event-sourcing envelope fields apply here at all,
or whether HTTP audit events need to be treated as a separate category within the Event Store.

> Action: confirm with Riaz whether HTTP audit events are expected to conform to the same
> Event Store schema as CP domain events, or whether they sit in a separate stream/category.

#### Event Store schema — field mapping

The Audit Event Store has four columns. The table below maps each spec field to what we
currently send and what we propose to send.

**`metadata` column** (event envelope)

| Spec field | What we currently send | What we propose to send | Notes |
|---|---|---|---|
| `metadata.id` | `_metadata.id` (UUID) | `eventId` (UUID) | ✅ covered |
| `metadata.name` | `_metadata.name` = `"audit.events.audit-recorded"` | `eventType` = `"AUDIT_HTTP"` | ⚠ confirm name value with Audit |
| `metadata.context.user` | `_metadata.context.user` (from `CJSCPPUID` header) | `identity.userId` | ✅ covered |
| `metadata.causation[]` | ❌ not sent | ❌ n/a for HTTP events | ⚠ CQRS concept — likely not applicable here |
| `metadata.stream.id` | ❌ not sent | ❌ n/a for HTTP events | ⚠ CQRS concept — likely not applicable here |
| `metadata.stream.version` | ❌ not sent | ❌ n/a for HTTP events | ⚠ CQRS concept — likely not applicable here |

**`name` column** (queryable event name column)

| Spec field | What we currently send | What we propose to send | Notes |
|---|---|---|---|
| `name` | `"audit.events.audit-recorded"` (hardcoded) | `"AUDIT_HTTP"` | ⚠ confirm with Audit — this is what they query against |

**`payload` column** (the full audit event body)

| Spec field | What we currently send | What we propose to send | Notes |
|---|---|---|---|
| `payload.timestamp` | `timestamp` | `occurredAt` | ✅ covered |
| `payload.origin` | `origin` (servlet context path) | `service.name` | ✅ covered |
| `payload.content` | merged JSON of body + path/query params | `payload` section (structured) | ⚠ structure up for discussion with Audit |
| `payload.content._metadata` | duplicated inside `content` | ❌ proposed for removal | ⚠ confirm removal is acceptable |
| `payload.content._metadata.name` | set to `Content-Type` header value | n/a (removed) | Looks like a bug in the current impl |

**Fields we currently send that are not in the spec**

| Current field | Notes |
|---|---|
| `component` | Was `origin + "-api"` — redundant, proposed for removal |

> Action: confirm with Riaz whether `causation` / `stream.id` / `stream.version` are required for HTTP audit events or are CQRS-only.
> Action: confirm the `name` column value — does it stay `"audit.events.audit-recorded"` or move to `"AUDIT_HTTP"`?
> Action: agree the structure of `payload.content` — this is the main open question on the Audit side.

---

### Per-endpoint field configuration — block by default?

The filter intercepts all HTTP traffic generically. Business-specific fields (e.g. `caseId`, `caseUrn`)
can only be included if the filter knows which path parameters are meaningful for a given endpoint.

**Proposal:** block audit events by default for endpoints that have no configuration, and only emit
events for endpoints that have been explicitly registered with the fields to capture. This avoids
emitting incomplete or misleading audit records.

> Action: agree the opt-in vs opt-out model with Riaz and the consuming teams.

---

### Missing HTTP context fields

The current payload does not capture:

| Missing field | Why it matters |
|---|---|
| `http.requestId` | No way to correlate the REQUEST event with its RESPONSE event |
| `http.clientIp` | No caller IP for security forensics |

> Note: Matt Rich has confirmed `http.url` and `http.statusCode` are **not required** by the Audit service.
> The proposed payload structure will be revised to reflect this.

### Guaranteed delivery — linked to blocking decision above

The library currently publishes to Artemis using a `JmsTemplate` in a **fire-and-forget** manner.
If the broker is unavailable at the moment a request is audited, the event is silently lost.

Questions to raise with the Audit team:

- Is **at-least-once delivery** required? If so, a local outbox / persistent retry queue should be considered.
- Does the Artemis topic use **durable subscriptions**? If the Audit consumer is offline, are messages held
  until it reconnects, or dropped?
- Is there an SLA on audit latency that would rule out async retry approaches?

### Request body buffering and memory pressure

Wrapping every request with `AuditServletRequestWrapper` to allow the body to be read twice holds the
full body in memory.
Large payloads (file uploads, bulk APIs) could cause memory pressure.
Consider enforcing a configurable **max body size** for auditing, with truncation or suppression above that threshold.

### Headers may contain sensitive tokens

Request headers are captured in full and included in the audit event.
`Authorization`, `Cookie`, and similar headers are likely to contain bearer tokens or session credentials.
A **header allowlist or denylist** should be agreed with the Audit team before rolling this out broadly.

### OpenAPI spec coupling

Path parameter names are extracted by matching the request URI against the OpenAPI spec.
If the spec is absent, stale, or mismatched, path parameters are silently lost from the audit event.
A Spring MVC `HandlerMapping` approach (resolving `{pathVariable}` names at filter time) would be more
reliable and would remove the OpenAPI dependency entirely.

---

## License

MIT (or HMCTS standard) — update as appropriate.