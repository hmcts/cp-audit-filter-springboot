# HMCTS Audit HTTP Starter (Spring Boot 4, Java 21)

A drop-in Spring Boot **starter** that audits every REST interaction by publishing structured audit
events to **ActiveMQ Artemis**.
It captures **request** and (if present) **response** payloads, enriched with headers, query/path
parameters, and metadata.

> Opinionated by default: path parameters are resolved using your **OpenAPI** document. You can swap
> the path-parser if you prefer a different approach.

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

## License

MIT (or HMCTS standard) — update as appropriate.