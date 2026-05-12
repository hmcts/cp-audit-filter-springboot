# Audit Filter — Request / Response Flow

> Shows how the `cp-audit-filter-springboot` library intercepts HTTP traffic and publishes
> audit events to the ActiveMQ Artemis topic, which the downstream Audit service then
> forwards to Fabric for incident queries.

---

```mermaid
sequenceDiagram
    autonumber

    actor Client
    participant Filter as AuditFilter<br/>(OncePerRequestFilter)
    participant App as Service Application<br/>(Spring Boot)
    participant JMS as ActiveMQ Artemis<br/>(jms.topic.auditing.event)
    participant Audit as Audit Service
    participant Fabric as Fabric / Incident<br/>Query Store

    Client->>Filter: HTTP Request

    Note over Filter: Wrap request body in<br/>AuditServletRequestWrapper<br/>to allow re-reading

    Filter->>JMS: Publish REQUEST audit event<br/>{ direction: REQUEST, http.method,<br/>  http.url, identity, domain, payload }

    Filter->>App: filterChain.doFilter(request, response)

    App-->>Filter: HTTP Response

    Note over Filter: Read response status from<br/>ContentCachingResponseWrapper

    Filter->>JMS: Publish RESPONSE audit event<br/>{ direction: RESPONSE, http.statusCode,<br/>  same http.requestId as REQUEST event }

    Filter-->>Client: HTTP Response (pass-through)

    JMS-->>Audit: Consume audit events<br/>(REQUEST + RESPONSE linked by requestId)

    Audit-->>Fabric: Forward to Fabric<br/>for incident queries
```

---

## Event Linking

The `http.requestId` field (a UUID generated once per HTTP request) is stamped on **both** the
REQUEST and RESPONSE events. This is the key that allows the Audit service to correlate the
two events into a single interaction record.

---

## What This Library Owns

| Concern | Owner |
|---|---|
| Intercepting HTTP traffic | `AuditFilter` (`OncePerRequestFilter`) |
| Building the audit payload | `AuditPayloadGenerationService` |
| Publishing to Artemis | `AuditEventPublisher` |
| Body buffering (request) | `AuditServletRequestWrapper` |
| Body buffering (response) | `ContentCachingResponseWrapper` |
| Path param extraction | OpenAPI spec + `OpenApiPathParamExtractor` |
| PII suppression switch | `audit.http.include-payload-body` config property |

## What This Library Does Not Own

| Concern | Owner |
|---|---|
| Consuming from the Artemis topic | Audit Service |
| Forwarding to Fabric | Audit Service |
| Incident query interface | Fabric / downstream |
