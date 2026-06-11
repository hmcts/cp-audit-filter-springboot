# Audit Filter — Design Concerns & Open Questions

**Status:** Under active discussion — redesign in progress on `feat/audit-filter-redesign`
**Relates to:** `cp-audit-filter-springboot`

> These are items that need a decision from the team or the Audit service owners before
> the implementation can be considered complete. Pending conversation with Riaz (Audit tech lead).

---

## Questions for David

| # | Question | Detail |
|---|---|---|
| 1 | **PII — should we block all headers and body fields by default?** | The filter currently forwards all headers (including auth tokens) and the full request/response body. This feels contrary to data protection principles — potentially storing all service data in a single audit bucket. A safer default would be to send nothing sensitive unless explicitly configured. |
| 2 | **Transport — should we move from Artemis to Azure Storage?** | David Edwards' POC evaluated four options and recommends writing audit events directly to Azure Storage (Option 3) as it is cloud native, reduces infrastructure, and lowers cost. Do we proceed with this direction? |
| 3 | **Synchronous or asynchronous audit publishing?** | Matt Rich suggests audit should be blocking (synchronous). If async, events could be silently lost (e.g. via a logger queue). If synchronous, a broker/storage outage fails the HTTP request. Which behaviour is required? This decision also drives the guaranteed delivery approach. |
| 4 | **Should the Audit spec be improved to better handle HTTP events?** | The current Audit Event Store schema was designed for CP CQRS/event-sourcing flows. HTTP audit events are infrastructure-level observations, not domain events. Fields like `causation`, `stream.id`, `stream.version` may not apply. Should HTTP audit events conform to the same schema or be treated as a separate category? |
| 5 | **Should consuming services be required to configure the audit payload per endpoint?** | The filter currently audits all endpoints generically with no per-endpoint configuration. Business-specific fields (e.g. `caseId`) cannot be reliably included without knowing which endpoints they apply to. Should the filter block audit events for unconfigured endpoints, forcing consuming services to explicitly declare what to capture? |

---

## ⚠ Transport — moving from Artemis JMS to Azure Storage direct write

> **Status:** Direction agreed. POC (David Edwards) evaluated four options and selected **Option 3 — write directly to Azure Storage**.
> Implementation approach and interface design still to be agreed.

A POC (David Edwards) evaluated four transport options:

| | Option 1 | Option 2 | Option 3 ✅ Chosen | Option 4 |
|---|---|---|---|---|
| **Description** | Replace Artemis with RabbitMQ | RabbitMQ + Azure Service Bus | Write directly to Azure Storage | Azure Event Hubs + Azure Functions |
| **Cloud native** | ❌ | Partial | ✅ | ✅ |
| **Local dev (DX)** | ✅ Good | ✅ Good | ✅ | ❌ Not possible locally |
| **Infrastructure** | New broker to support | New broker + Service Bus complexity | Less infra, reduces Artemis usage | Complex |
| **Implementation** | Simplest (drop-in AMQP) | Drop-in AMQP + Service Bus config | Moderate | Complex |
| **Cost** | Reduced operational cost | Additional Service Bus cost | Reduced cost | Possible higher cost |
| **Risk** | Scaling concerns | High throughput needs async handling | High throughput needs async handling | Complexity |

**Option 3 chosen** — write directly to Azure Storage. Introduces no new technologies,
simplifies the design, and reduces cost.

### Impact on this library

The current `AuditService` (JMS publisher) and `spring-boot-starter-artemis` dependency will be
replaced with an Azure Storage client. The filter itself remains largely unchanged — only the
transport layer changes.

### Open questions

- Does writing to Azure Storage change the **blocking vs non-blocking** decision? Azure Storage
  is highly durable so the risk of silent loss is much lower than with a broker — this may make
  synchronous writes more acceptable.
- Should the library abstract the transport behind an interface so the backend can be swapped
  without changing the filter or consuming services?
  e.g. `AuditEventPublisher` interface with `JmsAuditEventPublisher` and
  `AzureStorageAuditEventPublisher` implementations — low cost to do now, future-proofs the library.
- What Azure Storage format — Blob Storage (one file per event) or Azure Table Storage (structured rows)?
- How does the Audit service consume from Azure Storage compared to a JMS topic?
- High-throughput behaviour — does the write need to be async to spread load, or is synchronous acceptable?

> Action: agree Azure Storage format and consumption model with Riaz and David Edwards.
> Action: decide on transport abstraction interface before implementation begins.

---

## ⚠ AuditEventMapper needs rethinking

The `AuditEventMapper` (in `mapper/AuditEventMapper.java`) was built against the now-superseded
proposed top-level structure. Since only `payload.content` is under our control, the mapper should
instead produce a structured `content` block that slots into the existing `AuditPayload`.

See [`audit-payload-content-proposal.md`](audit-payload-content-proposal.md) for what the new
`content` block should look like.

> Action: revisit `AuditEventMapper` once the `content` block structure is agreed with Riaz.

---

## ⚠ Should the audit filter block HTTP requests? — Decision needed (Riaz)

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

## Audit payload content — alignment with the Audit spec

> **Status:** Under discussion. Matt Rich has reviewed and provided initial feedback.
> Spec reference: [Audit Design — Confluence](https://tools.hmcts.net/confluence/spaces/CTP/pages/109183562/Audit+Design)

Matt Rich's feedback:
- The Audit service **does not need** `http.url` or `http.statusCode` in the payload.
- They **do need** the metadata fields as defined in the existing Audit spec.
- The `content` section is open for improvement and is up for discussion.
- A filter cannot easily include **business-specific fields** without per-endpoint configuration —
  Matt Rich agrees this is a constraint.

### Background — the spec was designed for CP domain events

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

### Event Store schema — field mapping

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

## Per-endpoint field configuration — block by default?

The filter intercepts all HTTP traffic generically. Business-specific fields (e.g. `caseId`, `caseUrn`)
can only be included if the filter knows which path parameters are meaningful for a given endpoint.

**Proposal:** block audit events by default for endpoints that have no configuration, and only emit
events for endpoints that have been explicitly registered with the fields to capture. This avoids
emitting incomplete or misleading audit records.

> Action: agree the opt-in vs opt-out model with Riaz and the consuming teams.

---

## Missing HTTP context fields

The current payload does not capture:

| Missing field | Why it matters |
|---|---|
| `http.requestId` | No way to correlate the REQUEST event with its RESPONSE event |
| `http.clientIp` | No caller IP for security forensics |

> Note: Matt Rich has confirmed `http.url` and `http.statusCode` are **not required** by the Audit service.
> The proposed payload structure will be revised to reflect this.

---

## Guaranteed delivery — linked to blocking decision above

The library currently publishes to Artemis using a `JmsTemplate` in a **fire-and-forget** manner.
If the broker is unavailable at the moment a request is audited, the event is silently lost.

Questions to raise with the Audit team:

- Is **at-least-once delivery** required? If so, a local outbox / persistent retry queue should be considered.
- Does the Artemis topic use **durable subscriptions**? If the Audit consumer is offline, are messages held
  until it reconnects, or dropped?
- Is there an SLA on audit latency that would rule out async retry approaches?

---

## Request body buffering and memory pressure

Wrapping every request with `AuditServletRequestWrapper` to allow the body to be read twice holds the
full body in memory. Large payloads (file uploads, bulk APIs) could cause memory pressure.
Consider enforcing a configurable **max body size** for auditing, with truncation or suppression above that threshold.

---

## ⚠ PII and sensitive data — do not send blindly

The filter currently captures and forwards headers, body fields, path params, and query params
with no filtering whatsoever. This is a significant risk — PII and credentials can appear in all
four of these locations and must not be sent to the Audit store unchecked.

### Layer 1 — Headers

Headers are captured in full. Known sensitive headers that **must not** be forwarded:

| Header | Why it is sensitive |
|---|---|
| `Authorization` | Bearer tokens, Basic auth credentials |
| `Cookie` | Session tokens |
| `X-API-Key` | API credentials |
| `X-Auth-Token` | Auth tokens |
| `Proxy-Authorization` | Proxy credentials |

**Current state:** All headers are forwarded. No filtering exists.

**Proposal:** Introduce a **header denylist** with the above defaults, configurable via
`audit.http.excluded-headers` so consuming services can extend it. Only the headers
explicitly needed for audit (`CJSCPPUID`, `x-correlation-id`, `x-client-id`, `x-subscription-id`)
should be forwarded — consider switching to an **allowlist** approach instead.

> Action: agree header allowlist vs denylist approach with the team.

---

### Layer 2 — Request / response body

The body can contain any PII — names, dates of birth, National Insurance numbers, addresses,
legal case details, etc.

**Current state:** The body is forwarded in full unless `audit.http.include-payload-body=false`
is set, which suppresses it entirely (all or nothing).

**Proposal:** Three-tier approach:
1. **Off** — `audit.http.include-payload-body=false` suppresses the body entirely (already implemented)
2. **Field-level denylist** — forward the body but strip known sensitive fields (e.g. `dateOfBirth`,
   `nationalInsuranceNumber`, `address`) configurable per service
3. **Field-level allowlist** — only forward explicitly declared safe fields per endpoint

The all-or-nothing switch is a reasonable starting point but field-level control will be needed
for endpoints where some body fields are safe to audit and others are not.

> Action: agree the body filtering strategy with the Audit team and data governance.

---

### Layer 3 — Path parameters

Path parameters are currently forwarded in full (e.g. `/persons/{nino}` would expose a
National Insurance number in the audit event).

**Current state:** All path params captured and forwarded with no filtering.

**Proposal:** Link to the per-endpoint config model — each endpoint declares which path params
are safe to include. Params not declared are suppressed by default.

> Action: tied to the per-endpoint config decision — see section below.

---

### Layer 4 — Query parameters

Query strings can contain PII (e.g. `?name=John+Smith&dob=1990-01-01`).

**Current state:** All query params captured and forwarded with no filtering.

**Proposal:** Same approach as path params — per-endpoint config declares safe fields,
everything else is suppressed by default.

> Action: tied to the per-endpoint config decision — see section below.

---

### Summary — current risk vs proposed mitigations

| Data location | Current risk | Proposed mitigation |
|---|---|---|
| Headers | 🔴 All headers forwarded including auth tokens | Allowlist of safe headers only |
| Body | 🟡 All-or-nothing switch exists but defaults to on | Field-level denylist or allowlist |
| Path params | 🔴 All params forwarded unfiltered | Per-endpoint safe-field declaration |
| Query params | 🔴 All params forwarded unfiltered | Per-endpoint safe-field declaration |

> Action: a data classification review should be completed before this library is used in
> production on any endpoint that handles personal data.

---

## Headers may contain sensitive tokens

> ⚠ This section is superseded by the PII section above which covers headers in full.

---

## OpenAPI spec coupling

Path parameter names are extracted by matching the request URI against the OpenAPI spec.
If the spec is absent, stale, or mismatched, path parameters are silently lost from the audit event.
A Spring MVC `HandlerMapping` approach (resolving `{pathVariable}` names at filter time) would be more
reliable and would remove the OpenAPI dependency entirely.
