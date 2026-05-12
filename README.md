# HMCTS Audit HTTP Starter (Spring Boot 4, Java 21)

A drop-in Spring Boot **starter** that audits every REST interaction by publishing structured audit
events to **ActiveMQ Artemis**.
It captures **request** and (if present) **response** payloads, enriched with headers, query/path
parameters, and metadata.

> Opinionated by default: path parameters are resolved using your **OpenAPI** document. You can swap
> the path-parser if you prefer a different approach.

---

## Documentation

- [Audit Payload Specification](docs/audit-payload-spec.md)

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

This section captures known gaps and open architectural questions about the current audit payload and filter implementation. It is intended as a starting point for wider team discussion.

---

### Audit Payload — What Is Missing

**URL / request path not captured**
The current payload records `origin` (the servlet context path, e.g. `case-data-api`) but not the actual request path (`/cases/CASE-001/documents`) or the full URL. Without this, the audit log cannot answer "which resource was accessed?" and becomes significantly less useful for security investigation or access reporting.

**HTTP method not captured**
There is no record of whether the call was a `GET`, `POST`, `PUT`, `DELETE`, etc. This is fundamental context — a `GET /cases/CASE-001` and a `DELETE /cases/CASE-001` currently produce identical-looking audit events.

**HTTP response status not captured**
The response audit event contains the response body but not the HTTP status code. Without status codes it is impossible to identify failed attempts, repeated `401`/`403` responses that may indicate a probing attack, or `500` errors that correlate with data integrity issues.

**No correlation between request and response audit events**
Two JMS messages are published per HTTP call but there is no shared request ID linking them. Reconstructing a complete request/response pair requires matching on timestamp proximity alone, which is unreliable under load.

**No client IP address**
The caller's IP is not captured. This is a standard field in any access audit and is needed for geo-location analysis, rate-limit investigation, and incident response.

**`_metadata.name` uses `Content-Type` value for request events**
For request audits the `name` field is set to the value of the `Accept` or `Content-Type` header (e.g. `application/json`) rather than a meaningful event name. This is surprising and makes filtering by event type fragile.

**Headers may contain sensitive data**
All request headers are captured including `Authorization` and any bearer tokens. There is currently no allowlist or denylist mechanism to prevent sensitive headers from reaching the audit store.

---

### Service Registration

There is currently no concept of a registered consumer. Each service drops messages onto the topic with no way to associate them with an owning team, a sensitivity classification, a data retention period, or a set of responsible contacts.

A service registration model — where each onboarding service is issued a `subscriptionId` — would allow the audit platform to carry that context automatically on every event without relying on individual services to populate it correctly. Registration metadata could include:

- Service / product name and owning team
- Data sensitivity classification (e.g. Official, Official-Sensitive)
- Permitted data retention period
- GDPR lawful basis for capture
- Contact for data incidents

---

### Filter Implementation

**Artemis adds significant operational complexity**
The current implementation requires consumers to run and connect to an ActiveMQ Artemis broker. This creates a hard infrastructure dependency, makes local development harder, and means audit delivery silently fails if the broker is unavailable (the exception is caught and logged but not re-thrown, so the calling request succeeds regardless).

An HTTP endpoint would be simpler to integrate, easier to mock in tests, and would make the contract between producer and consumer explicit and enforceable with standard tooling (OpenAPI, contract testing).

**No persistent buffer or retry on delivery failure**
If the broker is unreachable at publish time, the audit event is lost with no retry. A persistent local buffer — or replacing Artemis with Azure Service Bus, which provides durable queuing and dead-letter support natively — would give stronger delivery guarantees without requiring consumers to manage broker HA themselves.

**Large request/response bodies are buffered entirely in memory**
Both the request body (`AuditServletRequestWrapper`) and the response body (`ContentCachingResponseWrapper`) are buffered in heap memory for every audited request. There is no size limit. Large file uploads or large API responses will cause significant memory pressure and could cause out-of-memory errors under load.

**The OpenAPI spec requirement is fragile**
Path parameter names (e.g. `caseId`, `caseUrn`) are resolved by matching the request path against patterns in an OpenAPI document. If the spec is out of date, missing an endpoint, or uses different parameter names, path params will be silently dropped from the audit event with no error or warning. This is a hidden correctness dependency that is easy to break during normal development.

A simpler alternative worth considering: Spring MVC already resolves path variables — the filter could read them directly from the `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE` request attribute, removing the need for an OpenAPI spec entirely.

---

### Summary of Recommended Next Steps

| Priority | Item |
|---|---|
| High | Add URL path, HTTP method, and response status to every audit event |
| High | Add a shared request ID to correlate request and response audit events |
| High | Implement a header denylist to prevent `Authorization` tokens reaching the audit store |
| Medium | Introduce service registration / `subscriptionId` to carry ownership and sensitivity metadata |
| Medium | Replace the OpenAPI path-param resolver with Spring MVC's built-in `URI_TEMPLATE_VARIABLES_ATTRIBUTE` |
| Medium | Add a payload size limit and fail-safe behaviour for oversized bodies |
| Low | Evaluate replacing Artemis with an HTTP endpoint + Azure Service Bus for simpler integration and stronger delivery guarantees |

---

## License

MIT (or HMCTS standard) — update as appropriate.