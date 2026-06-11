# Audit Payload — Current Specification

**Applies to:** `cp-audit-filter-springboot`
**Spec reference:** [Audit Design — David Edwards (Confluence)](https://tools.hmcts.net/confluence/spaces/CPPGM/pages/1899790847/Audit)

> This document describes what the filter currently sends, and how it maps against the
> authoritative Audit spec. See [`audit-payload-content-proposal.md`](audit-payload-content-proposal.md)
> for the proposed improvements.

---

## Authoritative Audit Message Format

The following is the format defined by the Audit spec. All producers must conform to this.

```json
{
  "component": "COMMAND_API",
  "origin": "progression-service",
  "timestamp": "2025-10-03T09:29:20.203Z",
  "content": {
    "_metadata": {
      "id": "3f535641-ed96-4c34-bfca-36b0c19e072e",
      "name": "progression.audit-court-document",
      "context": {
        "user": "31ec3a16-8721-498c-8da5-f099390ee254"
      }
    },
    "action": "Download",
    "courtDocumentId": "54845af8-114f-4b24-8e74-b1be65f6740d",
    "materialId": "55a0ed4b-6719-490b-b5b9-f480a6e0ecde"
  }
}
```

---

## Mandatory fields

| Field | Type | Description |
|---|---|---|
| `component` | String | Logical component: `COMMAND_API`, `QUERY_API`, `EVENT_LISTENER`, `SCHEDULER` |
| `origin` | String | Service deployment name e.g. `progression-service` |
| `timestamp` | ISO 8601 | When the auditable action occurred (not when the message was sent) |
| `content` | Object | The audit payload — see below |
| `content._metadata.id` | UUID String | Unique ID for this audit event — generate per event |
| `content._metadata.name` | String | Event type: `<service>.<entity>-<verb>` e.g. `progression.audit-court-document` |
| `content._metadata.context.user` | UUID String | CP platform UUID of the authenticated user who performed the action |

## Optional fields

| Field | Type | Description |
|---|---|---|
| `content._metadata.createdAt` | ISO 8601 | When the event was created. Falls back to `timestamp` if omitted |
| `content.action` | String | Human-readable label: `Download`, `Create`, `Update`, `Delete`, `Approve` |
| `content.caseId` | UUID String | CP case ID, if the action relates to a case |
| `content.hearingId` | UUID String | CP hearing ID, if the action relates to a hearing |
| `content.materialId` | UUID String | CP material ID, if the action relates to a material item |
| `content.courtDocumentId` | UUID String | CP court document ID, if action relates to a court document |
| `content.caseNumber` | String | Human-readable case number alongside `caseId` |
| `content.caseStatus` | String | Case status at time of action |
| `content.dateOfHearing` | Date String | Relevant hearing date |
| `content.type` | String | Type/category of entity being acted upon |
| `content.format` | String | Document/material format |
| `content.language` | String | Document/material language |

> The `content` object is extensible — additional fields may be included but may not be
> indexed or queryable in the Audit reporting system.

---

## Integration requirements (from the spec)

| Ref | Requirement | Priority |
|---|---|---|
| FR.01 | All attempted user actions must be recorded with enough detail to identify the user, the action, and what it was performed on | Must |
| FR.02 | All authorisation decisions should be recorded | Could |
| FR.03 | If an audit event cannot be recorded, the user's request must be rejected — failures must never be silently swallowed | Must |
| FR.04 | Audit data must be produced in the format above including all mandatory fields | Must |
| FR.05 | Only user-attributable actions should be audited — internal system events with no user attribution are out of scope | Should |
| FR.06 | The set of audited endpoints must be documented with a clear rationale for any exclusions | Must |

---

## What the current implementation sends (gaps vs spec)

| Spec field | Currently sent? | Notes |
|---|---|---|
| `component` | ⚠ Partial | Sent as `origin + "-api"` (e.g. `case-data-api-api`) — does not follow the `COMMAND_API` / `QUERY_API` convention |
| `origin` | ✅ Yes | Derived from servlet context path |
| `timestamp` | ✅ Yes | UTC timestamp |
| `content._metadata.id` | ✅ Yes | UUID generated per event |
| `content._metadata.name` | ⚠ Wrong | Set to `Content-Type` header value on requests — should follow `<service>.<entity>-<verb>` convention |
| `content._metadata.context.user` | ✅ Yes | From `CJSCPPUID` header |
| `content._metadata.createdAt` | ✅ Yes | Populated |
| `content.action` | ❌ Missing | Not captured at all |
| `content.caseId` | ⚠ Incidental | Only present if a path/query param happens to be named `caseId` |
| `content.hearingId` | ❌ Missing | Not captured |
| `content.courtDocumentId` | ❌ Missing | Not captured |
| `content.materialId` | ⚠ Incidental | Only if path/query param named `materialId` |

### Additional problems with the current implementation

- **Body forwarded in full** — the request/response body is sent to the audit store. This risks
  capturing PII. The spec does not require the body — only the domain identifier fields.
- **All headers forwarded** — includes `Authorization`, `Cookie`, and other sensitive headers.
  The spec does not require headers beyond the user identity (`CJSCPPUID`).
- **No per-endpoint configuration** — all endpoints are audited generically with no annotation
  or declaration. FR.06 requires the audited endpoint set to be documented.
- **Fire-and-forget publishing** — audit failures are currently logged and swallowed. FR.03
  requires the user request to be rejected if the audit event cannot be published.
- **`_metadata` duplicated inside `content`** — the current implementation nests a copy of
  `_metadata` inside `content`. This is not part of the spec and should be removed.
