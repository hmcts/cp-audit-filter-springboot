# Audit Payload — Proposed Structure

**Status:** Proposal — for discussion
**Relates to:** `cp-audit-filter-springboot`

> This document proposes a revised audit event structure to replace the current unstructured payload.
> It is intended to be circulated for team review before any implementation work begins.

---

## Goals

- Make every field's purpose immediately obvious from its location in the structure
- Capture the HTTP context that is currently missing (method, URL, status code)
- Provide a consistent home for HMCTS domain identifiers (caseId, caseUrn, etc.)
- Make it unambiguous whether an event represents a request or a response
- Lay the groundwork for service registration and data governance

---

## Top-level Structure

Every audit event has the same six top-level sections regardless of direction.

| Field | Type | Description |
|---|---|---|
| `eventId` | UUID | Unique identifier for this audit event |
| `eventType` | String | Fixed value `AUDIT_HTTP` — stable for consumer subscriptions |
| `direction` | String | `REQUEST` or `RESPONSE` |
| `occurredAt` | ISO-8601 | UTC timestamp to millisecond precision |
| `service` | Object | Which service produced this event |
| `http` | Object | HTTP envelope — method, URL, status, client IP |
| `identity` | Object | Who made the call |
| `domain` | Object | HMCTS domain identifiers extracted from the request |
| `payload` | Object | Path params, query params, and optionally the body |

---

## Request Event

```json
{
  "eventId": "a1b2c3d4-0001-0000-0000-000000000000",
  "eventType": "AUDIT_HTTP",
  "direction": "REQUEST",
  "occurredAt": "2026-04-30T10:30:00.123Z",

  "service": {
    "name": "case-data-api",
    "subscriptionId": "svc-hmcts-case-data-001"
  },

  "http": {
    "requestId": "f7e6d5c4-0002-0000-0000-000000000000",
    "method": "POST",
    "url": "/cases/CASE-001/documents",
    "statusCode": null,
    "clientIp": "10.0.1.42"
  },

  "identity": {
    "userId": "user-456",
    "correlationId": "corr-xyz-789"
  },

  "domain": {
    "caseId": "CASE-001",
    "caseUrn": "URN:HMCTS:2026:001234",
    "materialId": null,
    "clientId": null,
    "subscriptionId": null
  },

  "payload": {
    "pathParams": {
      "caseId": "CASE-001"
    },
    "queryParams": {
      "caseType": "CIVIL"
    },
    "body": {
      "documentType": "CLAIM_FORM",
      "filename": "claim.pdf"
    }
  }
}
```

---

## Response Event

Same `requestId` as the request event — this is how the two events are linked.

```json
{
  "eventId": "a1b2c3d4-0001-0000-0000-000000000001",
  "eventType": "AUDIT_HTTP",
  "direction": "RESPONSE",
  "occurredAt": "2026-04-30T10:30:00.456Z",

  "service": {
    "name": "case-data-api",
    "subscriptionId": "svc-hmcts-case-data-001"
  },

  "http": {
    "requestId": "f7e6d5c4-0002-0000-0000-000000000000",
    "method": "POST",
    "url": "/cases/CASE-001/documents",
    "statusCode": 201,
    "clientIp": "10.0.1.42"
  },

  "identity": {
    "userId": "user-456",
    "correlationId": "corr-xyz-789"
  },

  "domain": {
    "caseId": "CASE-001",
    "caseUrn": "URN:HMCTS:2026:001234",
    "materialId": null,
    "clientId": null,
    "subscriptionId": null
  },

  "payload": {
    "pathParams": {
      "caseId": "CASE-001"
    },
    "queryParams": {
      "caseType": "CIVIL"
    },
    "body": {
      "documentId": "DOC-9988",
      "status": "UPLOADED"
    }
  }
}
```

---

## Field Reference

### `service`

| Field | Type | Required | Source |
|---|---|---|---|
| `name` | String | Always | Servlet context path (already captured) |
| `subscriptionId` | String | Always | New — set via `audit.http.subscription-id` config property per service |

### `http`

| Field | Type | Required | Source |
|---|---|---|---|
| `requestId` | UUID | Always | New — generated once per request, stamped on both REQUEST and RESPONSE events to link them |
| `method` | String | Always | New — `request.getMethod()` |
| `url` | String | Always | New — `request.getRequestURI()` |
| `statusCode` | Integer | RESPONSE only | New — `response.getStatus()` after `filterChain.doFilter()` |
| `clientIp` | String | Always | New — `request.getRemoteAddr()` with `X-Forwarded-For` fallback behind a proxy |

### `identity`

| Field | Type | Required | Source |
|---|---|---|---|
| `userId` | String | Optional | `CJSCPPUID` header (already captured) |
| `correlationId` | String | Optional | `x-correlation-id` header (already captured) |

### `domain`

Well-known HMCTS identifiers are auto-promoted from path/query params if present.
All fields are `null` when not applicable to the request.

| Field | Type | Promoted from |
|---|---|---|
| `caseId` | String | Path param or query param named `caseId` |
| `caseUrn` | String | Path param or query param named `caseUrn` |
| `materialId` | String | Path param or query param named `materialId` |
| `clientId` | String | `x-client-id` header |
| `subscriptionId` | String | `x-subscription-id` header |

### `payload`

| Field | Type | Required | Notes |
|---|---|---|---|
| `pathParams` | Object | Always | Key/value pairs extracted via OpenAPI spec |
| `queryParams` | Object | Always | Key/value pairs from query string |
| `body` | Object / null | Optional | Suppressed when `audit.http.include-payload-body=false` to avoid capturing PII |

---

## What Changes from the Current Implementation

| Current field | Proposed equivalent | Change |
|---|---|---|
| `_metadata.id` | `eventId` | Promoted to top level |
| `_metadata.name` | `eventType` + `direction` | Split into two explicit fields |
| `_metadata.createdAt` | `occurredAt` | Renamed, promoted to top level |
| `_metadata.correlation.client` | `identity.correlationId` | Moved to `identity` section |
| `_metadata.context.user` | `identity.userId` | Moved to `identity` section |
| `origin` | `service.name` | Renamed, moved to `service` section |
| `component` | *(removed)* | Redundant — was just `origin + "-api"` |
| `timestamp` | `occurredAt` | Renamed, promoted to top level |
| `content` | `payload` + `domain` | Split — domain identifiers get their own section |
| `content._metadata` | *(removed)* | Was duplicated inside content — no longer needed |
| *(missing)* | `http.method` | New |
| *(missing)* | `http.url` | New |
| *(missing)* | `http.statusCode` | New |
| *(missing)* | `http.requestId` | New — links REQUEST and RESPONSE events |
| *(missing)* | `http.clientIp` | New |
| *(missing)* | `service.subscriptionId` | New — requires service registration |
| *(missing)* | `domain.*` | New — auto-promoted from path/query params |

---

## Open Questions

- [ ] Should `domain` fields also remain in `payload.pathParams` / `payload.queryParams`, or be promoted exclusively?
- [ ] What is the agreed header name for `clientId` and `subscriptionId`?
- [ ] Should `payload.body` be omitted entirely from the structure when suppressed, or present as `null`?
- [ ] Is `service.subscriptionId` mandatory from day one, or can it default to `service.name` until registration is in place?
- [ ] Should `clientIp` respect `X-Forwarded-For` only, or also `X-Real-IP` and similar proxy headers?
