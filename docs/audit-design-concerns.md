# Audit Filter — Design Concerns & Open Questions

**Status:** Under active discussion — redesign in progress on `feat/audit-filter-redesign`
**Relates to:** `cp-audit-filter-springboot`
**Spec reference:** [Audit Design — David Edwards (Confluence)](https://tools.hmcts.net/confluence/spaces/CPPGM/pages/1899790847/Audit)

---

## Questions for David

| # | Question | Status | Outcome |
|---|---|---|---|
| 1 | **PII — should we block all headers and body fields?** | ✅ Resolved | David agrees — do not send body or headers. Only send the specific domain fields defined in the Audit spec. |
| 2 | **Transport — move from Artemis to Azure Storage?** | ✅ Resolved | Stay with Artemis JMS. The audit2dsl service handles writing to Azure Storage — that is not our concern. |
| 3 | **Synchronous or asynchronous audit publishing?** | ✅ Resolved | FR.03 confirms audit must be **blocking**. If the audit event cannot be published, the user's request must be rejected. |
| 4 | **Should the spec be improved to better handle HTTP events?** | ✅ Resolved | David's spec page covers HTTP audit requirements directly. The message format applies. |
| 5 | **Should consuming services be required to configure per endpoint?** | ✅ Resolved | David agrees — filter should block by default. Configuration via **controller annotations**. |
| 6 | **HTTP endpoint vs direct Artemis posting?** | 🔄 For discussion | An HTTP endpoint in front of Artemis may be simpler for consumers. Decision on ownership (central vs per-domain) and proximity to caller. Discuss with **Matt Jenkins** (data engineering). |
| 7 | **Service-to-service calls — no user identity available** | 🔄 For discussion | Our APIs are service-to-service and do not always carry the requesting user's identity. `_metadata.context.user` is mandatory in the spec. See section below. |

---

## ✅ Audit must block — FR.03 confirmed

David's spec is explicit:

> *"If a technical problem prevents an audit event from being recorded, the user's attempted action must be rejected. Audit failures must never be silently swallowed."*

This is a **MUST** requirement. The filter must publish to Artemis synchronously and return an error to the caller if the publish fails. The current fire-and-forget implementation does not meet this requirement.

---

## ✅ Transport — Artemis JMS (unchanged)

The filter continues to post audit events to the Artemis JMS topic (`jms.topic.auditing.event`).
The downstream `audit2dsl` service reads from Artemis and writes to Azure Blob Storage — that pipeline
is owned by the data engineering team and is not the concern of this library.

---

## ✅ Per-endpoint configuration via controller annotations

The filter blocks by default for any endpoint without an audit annotation. Consuming services must
explicitly annotate each auditable controller method to declare:

- The `action` (e.g. Download, Create, Update, Delete)
- The `component` type (`COMMAND_API`, `QUERY_API` etc.)
- The event name following the convention `<service>.<entity>-<verb>`
- Any field name mappings where the endpoint uses non-standard parameter names

See [`audit-payload-content-proposal.md`](audit-payload-content-proposal.md) for the proposed
annotation design.

---

## ✅ PII — only send spec-defined domain fields

The Audit spec defines a closed set of `content` fields. The filter must only send fields from
this set — no raw body, no headers, no arbitrary query params.

**Agreed approach:**
- No request/response body forwarded
- No headers forwarded (other than `CJSCPPUID` for the user identity)
- Only the standard domain fields (`caseId`, `hearingId`, `materialId` etc.) extracted from
  path/query params by name, or mapped via annotation for non-standard naming

This fully addresses the PII concern — the audit record contains only identifiers, not personal data.

See [`audit-payload-content-proposal.md`](audit-payload-content-proposal.md) for field details.

---

## 🔄 Service-to-service calls — user identity concern

Our APIs are predominantly service-to-service. The requesting user's identity (`CJSCPPUID` header)
is not always present — one service calling another on behalf of a user may not propagate the
original user's UUID.

The Audit spec requires `_metadata.context.user` as a **mandatory** field (CP platform user UUID).

This creates a conflict:

| Scenario | Issue |
|---|---|
| UI → Service A | User UUID is present in the request — no problem |
| Service A → Service B (on behalf of user) | User UUID may not be forwarded — field would be missing |
| Scheduled/system job | No user at all — explicitly out of scope per FR.05 |

**Questions for David:**
- For service-to-service calls triggered by a user action, should the originating user UUID be
  propagated through the call chain (e.g. via a standard header)?
- If no user UUID is available, should the audit event be suppressed, or should the service
  identity be used as a fallback?
- FR.05 says system-to-system events with no user attribution are **out of scope** — does this
  mean service-to-service calls should simply not be audited when there is no user UUID?

---

## 🔄 HTTP endpoint vs direct Artemis posting

Currently consuming services post directly to Artemis, requiring each to manage JMS client
configuration, SSL certificates, and connection tuning.

An HTTP endpoint in front of Artemis would be simpler for consumers:
- No JMS dependency
- Standard HTTP — works with any language or framework
- Easier to test locally
- Centralises validation and format enforcement

**Open questions:**
- Would the data engineering team own and run a centralised HTTP audit endpoint?
- Or would each domain team run their own instance (keeping it close to the caller for low latency)?
- FR.03 requires blocking — a remote centralised endpoint adds latency and failure modes to the
  HTTP request path. Proximity to the caller matters.

> Action: discuss with Matt Jenkins (data engineering team).

---

## ✅ OpenAPI spec dependency — dropped

The current implementation uses an OpenAPI spec to resolve path parameter names from the
request URI. This is overly complex — Spring already resolves path variable names at request
time via `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`, giving a `Map<String, String>`
of name→value for free.

The new implementation uses `HandlerMapping` instead. No OpenAPI spec file required.
Path param names come directly from the `@PathVariable` annotations the developer already wrote.

Benefits:
- Removes `io.swagger.parser.v3:swagger-parser` dependency entirely
- No spec file to maintain or keep in sync
- Works correctly with any number of path params (e.g. `/{subId}/{caseId}`)
- More reliable — not affected by spec drift or missing files

---

## ✅ `httpMethod` replaces `action`

The spec defines an `action` field (e.g. `Download`, `Create`). For a generic HTTP filter,
deriving a meaningful `action` label is not reliable. Instead the filter records `httpMethod`
directly (`GET`, `POST`, `PUT`, `DELETE`) — precise, automatic, and requires no configuration.

`component` is also derived from the HTTP method (`GET` → `QUERY_API`, others → `COMMAND_API`)
and can be overridden via annotation for edge cases (e.g. a `POST` that is semantically a search).

> Confirm with David that `httpMethod` in place of `action` is acceptable.

---

## ✅ Request body buffering — no longer needed

Now that the body is not forwarded in the audit event, `AuditServletRequestWrapper` (which
buffers the full body in memory to allow it to be read twice) is no longer necessary for
the standard flow. It is still needed only when `@AuditField(from = SourceType.BODY)` is
declared, in which case only that specific field is extracted — not the full body.
