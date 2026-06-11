# Audit Payload — Proposed `content` Block & Annotation Design

**Status:** Draft — for discussion
**Relates to:** `cp-audit-filter-springboot`
**Spec reference:** [Audit Design — David Edwards (Confluence)](https://tools.hmcts.net/confluence/spaces/CPPGM/pages/1899790847/Audit)

> The Audit message format is defined by David's spec page. This document describes how the
> HTTP audit filter will produce a conforming message, and how consuming services configure
> it via controller annotations.

---

## The target message format

```json
{
  "component": "QUERY_API",
  "origin": "case-data-api",
  "timestamp": "2025-10-03T09:29:20.203Z",
  "content": {
    "_metadata": {
      "id": "3f535641-ed96-4c34-bfca-36b0c19e072e",
      "name": "case-data-api.case-document-download",
      "createdAt": "2025-10-03T09:29:20.203Z",
      "context": {
        "user": "31ec3a16-8721-498c-8da5-f099390ee254"
      }
    },
    "httpMethod": "GET",
    "caseId": "54845af8-114f-4b24-8e74-b1be65f6740d",
    "courtDocumentId": "55a0ed4b-6719-490b-b5b9-f480a6e0ecde"
  }
}
```

**No body. No headers. No raw params.** Only the specific domain fields the spec defines,
plus `httpMethod` in place of a free-text `action` label.

---

## What the filter derives automatically

These fields require no annotation — the filter populates them from the HTTP request context:

| Field | Derived from |
|---|---|
| `origin` | Servlet context path |
| `timestamp` | UTC timestamp at time of action |
| `content.httpMethod` | `request.getMethod()` — `GET`, `POST`, `PUT`, `DELETE` etc. |
| `component` | Derived from HTTP method: `GET` → `QUERY_API`, all others → `COMMAND_API` |
| `content._metadata.id` | Generated UUID per event |
| `content._metadata.createdAt` | UTC timestamp at time of event |
| `content._metadata.context.user` | `CJSCPPUID` request header |

### Standard domain field auto-extraction

The filter automatically extracts the following well-known field names from path params,
query params, and request body (checked in that order) without any annotation:

| Spec field | Auto-extracted when a param/body field is named |
|---|---|
| `caseId` | `caseId` |
| `hearingId` | `hearingId` |
| `materialId` | `materialId` |
| `courtDocumentId` | `courtDocumentId` |
| `caseNumber` | `caseNumber` |

Path params are resolved via Spring's `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE` —
no OpenAPI spec required.

---

## What the annotation provides

`@AuditConfig` on a controller method is **required** — the filter blocks by default for
any endpoint without it.

```java
public @interface AuditConfig {
    String eventName();                  // mandatory — "<service>.<entity>-<verb>"
    AuditComponent component()           // optional — overrides the HTTP method derivation
        default AuditComponent.DERIVED;
    AuditField[] fieldMappings()         // optional — for non-standard parameter names
        default {};
}

public @interface AuditField {
    String source();        // parameter name as it appears in the request
    SourceType from();      // where to look for it
    String target();        // spec field name to map it to
}

public enum SourceType {
    PATH_PARAM,
    QUERY_PARAM,
    BODY
}

public enum AuditComponent {
    DERIVED,        // default — inferred from HTTP method
    COMMAND_API,
    QUERY_API,
    EVENT_LISTENER,
    SCHEDULER
}
```

---

## Annotation examples

### Standard field names — minimal annotation

Path params are named `caseId` and `courtDocumentId` — the filter extracts them automatically.
Only `eventName` is needed:

```java
@AuditConfig(eventName = "case-data-api.case-document-download")
@GetMapping("/cases/{caseId}/documents/{courtDocumentId}")
public ResponseEntity<Document> getDocument(
        @PathVariable String caseId,
        @PathVariable String courtDocumentId) {
    ...
}
```

---

### Non-standard path param names — field mapping

The endpoint uses `caseReference` instead of `caseId` — declare the mapping:

```java
@AuditConfig(
    eventName = "case-data-api.case-document-download",
    fieldMappings = {
        @AuditField(source = "caseReference", from = SourceType.PATH_PARAM, target = "caseId")
    }
)
@GetMapping("/cases/{caseReference}/documents/{courtDocumentId}")
public ResponseEntity<Document> getDocument(
        @PathVariable String caseReference,
        @PathVariable String courtDocumentId) {
    ...
}
```

---

### Field from query param

```java
@AuditConfig(
    eventName = "case-data-api.case-search",
    fieldMappings = {
        @AuditField(source = "case_number", from = SourceType.QUERY_PARAM, target = "caseNumber")
    }
)
@GetMapping("/cases")
public ResponseEntity<List<Case>> searchCases(@RequestParam String case_number) {
    ...
}
```

---

### Field from request body

```java
@AuditConfig(
    eventName = "case-data-api.case-document-upload",
    fieldMappings = {
        @AuditField(source = "documentRef", from = SourceType.BODY, target = "courtDocumentId")
    }
)
@PostMapping("/cases/{caseId}/documents")
public ResponseEntity<Void> uploadDocument(
        @PathVariable String caseId,
        @RequestBody DocumentRequest body) {
    ...
}
```

---

### Override component type

For a `POST` that is semantically a search (e.g. complex query via request body):

```java
@AuditConfig(
    eventName = "case-data-api.case-search",
    component = AuditComponent.QUERY_API
)
@PostMapping("/cases/search")
public ResponseEntity<List<Case>> searchCases(@RequestBody SearchRequest request) {
    ...
}
```

---

## `content` field reference

### Always populated by the filter

| Field | Type | Source |
|---|---|---|
| `content._metadata.id` | UUID | Generated per event |
| `content._metadata.name` | String | `eventName` from `@AuditConfig` |
| `content._metadata.createdAt` | ISO 8601 | UTC timestamp |
| `content._metadata.context.user` | UUID String | `CJSCPPUID` header |
| `content.httpMethod` | String | `request.getMethod()` |

### Optional domain fields (auto-extracted or mapped via annotation)

| Field | Type | Notes |
|---|---|---|
| `caseId` | UUID String | Whenever the action relates to a case |
| `hearingId` | UUID String | Whenever the action relates to a hearing |
| `materialId` | UUID String | Whenever the action relates to a material item |
| `courtDocumentId` | UUID String | Whenever the action relates to a court document |
| `caseNumber` | String | Human-readable case number alongside `caseId` |
| `caseStatus` | String | Case status at time of action, where relevant |
| `dateOfHearing` | Date String | Where a specific hearing date is relevant |
| `type` | String | Type/category of entity being acted upon |
| `format` | String | Document/material format, where relevant |
| `language` | String | Document/material language, where relevant |

> Fields not in this list may not be indexed or queryable in the Audit reporting system.
> If a new standard field is needed, raise it with the data engineering team.

---

## Open questions

- [ ] **Service-to-service calls** — `_metadata.context.user` is mandatory but the user UUID
      may not be propagated across service boundaries. Should such calls be suppressed, or is
      there a fallback? See [`audit-design-concerns.md`](audit-design-concerns.md).
- [ ] **`_metadata.createdAt`** — spec says this falls back to `timestamp` if omitted. Should
      the filter always populate it, or rely on the fallback?
- [ ] **Excluded endpoints** — health, actuator, and multipart are excluded at filter level.
      Should exclusions be declarable via `@AuditConfig(excluded = true)` for FR.06 transparency?
- [ ] **`httpMethod` vs `action`** — `httpMethod` is a deviation from the spec's `action` field.
      Confirm with David that this is acceptable.
