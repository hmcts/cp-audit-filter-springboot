# Audit Payload — Proposed `content` Block Structure

**Status:** Draft — for discussion with Riaz (Audit tech lead)
**Relates to:** `cp-audit-filter-springboot`

> The Audit Event Store schema is fixed. Only the `payload.content` block is under our control.
> This document proposes what that block should contain.

---

## Context — what we can and cannot change

The Audit Event Store payload envelope is fixed:

```json
{
  "timestamp": "...",
  "origin": "case-data-api",
  "content": {
    // ← this is the only part we own
  }
}
```

The `metadata` column (event envelope), `name` column, `payload.timestamp`, and `payload.origin`
must all conform to the existing Audit spec. See [`audit-design-concerns.md`](audit-design-concerns.md)
for the full field mapping.

---

## Current `content` block (problems)

Currently `content` is an unstructured merge of:
- The raw request/response body (parsed JSON fields merged to the top level)
- Path parameters (merged to the top level)
- Query parameters (merged to the top level)
- A duplicated `_metadata` object

This means all fields collide at the same level with no way to distinguish where a value came from,
and the `_metadata` duplication inside `content` is redundant.

```json
{
  "content": {
    "caseId": "CASE-001",
    "caseType": "CIVIL",
    "documentType": "CLAIM_FORM",
    "_metadata": {
      "name": "application/json",
      "context": { "user": "user-456" },
      "id": "...",
      "createdAt": "..."
    }
  }
}
```

---

## Proposed `content` block

Structured into clear sub-sections. The `_metadata` duplication is removed.

```json
{
  "content": {
    "pathParams": {
      "caseId": "CASE-001"
    },
    "queryParams": {
      "caseType": "CIVIL"
    },
    "body": {
      "documentType": "CLAIM_FORM",
      "filename": "claim.pdf"
    },
    "domain": {
      "caseId": "CASE-001",
      "caseUrn": "URN:HMCTS:2026:001234",
      "materialId": null,
      "clientId": null,
      "subscriptionId": null
    }
  }
}
```

---

## Field reference

### `content.pathParams`

| Field | Type | Notes |
|---|---|---|
| *(dynamic)* | `Map<String, String>` | Key/value pairs extracted from the URI via OpenAPI spec |

### `content.queryParams`

| Field | Type | Notes |
|---|---|---|
| *(dynamic)* | `Map<String, String>` | Key/value pairs from the query string |

### `content.body`

| Field | Type | Notes |
|---|---|---|
| *(dynamic)* | `Object / null` | Parsed JSON body. Omitted when `audit.http.include-payload-body=false` to avoid capturing PII |

### `content.domain`

Well-known HMCTS identifiers auto-promoted from params and headers when present.
All fields are `null` when not applicable.

| Field | Promoted from |
|---|---|
| `caseId` | Path param or query param named `caseId` |
| `caseUrn` | Path param or query param named `caseUrn` |
| `materialId` | Path param or query param named `materialId` |
| `clientId` | `x-client-id` header |
| `subscriptionId` | `x-subscription-id` header |

---

## Open questions

- [ ] Should `domain` fields also remain in `pathParams` / `queryParams`, or be promoted exclusively?
- [ ] Is the `domain` sub-section acceptable to the Audit team, or should all fields remain flat?
- [ ] Should `body` be omitted entirely when suppressed, or present as `null`?
- [ ] Per-endpoint config: should endpoints without explicit configuration be blocked from emitting
      audit events to avoid incomplete records? (See [`audit-design-concerns.md`](audit-design-concerns.md))
- [ ] Should `content` carry a `direction` field (`REQUEST` / `RESPONSE`) to distinguish the two
      events, given the top-level envelope has no such concept in the current spec?
