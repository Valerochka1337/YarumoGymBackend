# Admin AI diagnostics — implementation plan

## Goal and scope

Give authenticated administrators a read-only, process-local view of the last 200 AI attempts (maximum 24 hours) so `ai_unavailable` can be triaged from safe metadata, including calendar-job failures before provider entry. Add only `GET /admin/api/ai-diagnostics` and its admin page/view. Diagnostics are random IDs, bounded RAM only, disappear on restart, and are not audit data.

Out of scope: triggering AI, shell/Docker access, raw provider/server payloads, prompts/messages/context, account/user IDs, credentials, database persistence/migrations, Android, versioning, queue mutation, and cross-process aggregation. The existing admin session/filter and `no-store` policy remain authoritative.

Assumptions: implementation is on `feat/admin-ai-diagnostics`; a single JVM is useful for incident triage, while restart/other-instance gaps are explicitly displayed. `SELECT 1` and aggregate calendar-job queue counts are the only DB observation.

## Acceptance criteria

- **AC-001** Authenticated admin GET returns a bounded, sanitized snapshot; unauthenticated/non-admin access is rejected and response is `no-store`.
- **AC-002** Each calendar/provider/agentic attempt records correlation, typed stage, elapsed duration, bounded round/tool counters, model label, HTTP status, and fixed failure category; pre-provider job failures are included.
- **AC-003** Retention is synchronized, process-memory-only, random diagnostic IDs, max 200 entries and 24h; concurrent scopes neither cross-contaminate nor leak after `finally` cleanup.
- **AC-004** No forbidden raw data can enter API/UI/record: prompt, messages, context, response body, account/user ID, credential, arbitrary upstream text, or raw `error.message`.
- **AC-005** Non-200 provider failures classify using only allowlisted upstream `error.code`/`error.type`/`error.param` (including `invalid_json_schema`, `invalid_request_error`, `model_not_found`, `unsupported_parameter`, `response_format`, `tools`, `model`, `max_completion_tokens`) plus HTTP status; unknown values map to a fixed category, never an arbitrary string.
- **AC-006** Admin UI is read-only: Diagnostics navigation, loading/error/empty/content states, fixed-category filtering, refresh, and local sanitized-report copy work without external transfer and state the retention/restart limitation.

## Frozen contracts and flow

Current: calendar job claim → `provider.available` → `CalendarAiService.create/refine` agent rounds/tools → `AiConfiguration` provider wrapper → HTTP provider (non-200 becomes `ai_unavailable`); errors lose diagnostic cause. Target: an `AiDiagnostics` singleton owns an in-memory deque and a `ThreadLocal<AttemptHandle?>`. Instrumentation opens a scope around `runNext`, `create`, `refine`, and each provider turn; nested stages share one random UUID. Each scope closes in `finally`, records terminal category/duration, and restores/removes the prior ThreadLocal value. The service returns an immutable copied snapshot only; database health and queue aggregate are calculated at GET time and never identify a job/owner.

**DTO v1 (exact, additive only):**

```json
{
  "generatedAt":"2026-09-20T10:00:00Z",
  "retention":{"maxRuns":200,"maxAgeHours":24,"processLocal":true,"lostOnRestart":true},
  "database":{"status":"OK|ERROR"},
  "calendarQueue":{"status":"OK|ERROR","queued":0,"running":0,"failed":0,"ready":0},
  "runs":[{
    "id":"random UUID","startedAt":"ISO-8601 instant","durationMs":12,
    "outcome":"RUNNING|SUCCESS|FAILURE|CANCELLED","failureCategory":"NONE|AI_UNAVAILABLE|AI_TIMEOUT|AI_BUSY|AI_INTERRUPTED|AI_INVALID_RESPONSE|AI_CONTEXT_STALE|AI_CONTEXT_TOO_LARGE|UPSTREAM_REJECTED|UPSTREAM_UNAVAILABLE|DATABASE|TRANSPORT|VALIDATION|PROVIDER_UNCONFIGURED|INTERNAL",
    "model":"configured label or null","httpStatus":400,
    "upstreamCode":"invalid_json_schema|invalid_request_error|model_not_found|unsupported_parameter|null",
    "upstreamType":"invalid_request_error|null",
    "upstreamParam":"response_format|tools|model|max_completion_tokens|null",
    "rounds":0,"toolCalls":0,
    "stages":[{"stage":"CALENDAR_JOB|CALENDAR_CREATE|CALENDAR_REFINE|PLANNER_TURN|PLANNER_TOOL|PROVIDER_HTTP","outcome":"RUNNING|SUCCESS|FAILURE|CANCELLED","durationMs":12}]
  }]
}
```

All enums are closed; `httpStatus` is 100–599 or null; counters/durations are non-negative and capped; `stages` is capped at 64. Queue counts are nullable when its aggregate query fails (never fabricated zeros). No other fields are serialized. Exact allowlisted `error.code`, `error.type`, and `error.param` win; optional known-keyword classification must map only to the same fixed enums and must not retain source text. Observation is best-effort, catches its own failures, performs no DB writes, and must never change business outcome. The job outer catch records the actual caught class only as `DATABASE|TRANSPORT|VALIDATION|PROVIDER_UNCONFIGURED|INTERNAL`, before business masking, never a message.

Stages appear in opening order; array index is sequence. Add PLANNER_TOOL to the stage enum.
Register RUNNING on opening the outer scope; nested scopes share its ID and never add duplicate runs.
RUNNING/SUCCESS have NONE; FAILURE has a non-NONE category; CANCELLED uses AI_INTERRUPTED.
Preserve the most specific inner failure category when the same failure is masked by an outer scope.
Evict oldest runs, including active ones when necessary; evicted scopes never reinsert themselves.
Tests cover ordered stages, active-to-terminal transitions, queue failure vs true zero, scope isolation,
pre-wrapper categories and observer failures preserving the original business result.

## Tasks

| Task | Owner / exact files | Depends | Action and automated verification | Done / AC |
|---|---|---|---|---|
| T-001 | Backend writer: new `service/ai/AiDiagnostics.kt`, `controller/admin/AiDiagnosticsController.kt`, relevant admin model file; tests `service/ai/AiDiagnosticsTest.kt`, admin integration test | — | Implement frozen DTO, synchronized 200/24h store, ThreadLocal nested-scope restore/remove, snapshot/`SELECT 1`/aggregate queue, GET controller. Run `./gradlew test --tests '*AiDiagnostics*'`. | API serializes only v1 fields; bounds, expiry, cleanup and auth covered. AC-001,003,004 |
| T-002 | Backend writer: `CalendarDraftJobService.kt`, `CalendarAiService.kt`, `AiConfiguration.kt`, `HttpOpenAiChatCompletionsProvider.kt`; tests `CalendarDraftJobServiceTest.kt`, `CalendarPlannerAgentTest.kt`, `HttpOpenAiChatCompletionsProviderTest.kt` | T-001 | Propagate handles through job claim/availability/create/refine/agentic rounds/tools and provider HTTP with strict `finally`; parse bounded non-200 JSON solely for allowlisted `error.code`/`error.type`/`error.param`, discard body, classify fixed enums. Observer failure is swallowed; job outer catch records only local fixed exception class category. Run `./gradlew test --tests '*Calendar*' --tests '*HttpOpenAiChatCompletionsProviderTest'`. | One correlation covers nested work; pre-provider and unexpected local failure appear; raw message/body cannot be observed; 400 schema/param causes are distinguishable. AC-002,004,005 |
| T-003 | Static UI writer: `src/main/resources/static/admin/index.html`, `static/admin/admin.js`; JS DOM test harness/new `src/test/resources` or existing JS-test location | T-001 (DTO frozen) | Add `data-view="ai-diagnostics"`, read-only renderer/filter/refresh/copy using `textContent` and Clipboard API only; surface loading/error/empty/content and retention copy. Run the repository JS DOM test command (or `node --test` target). | No mutation API/action exists; DOM test proves safe text rendering/filter/copy and limitation notice. AC-006,004 |
| T-004 | Backend writer: touched backend tests plus admin auth integration test | T-001,T-002,T-003 | Strict plan/privacy review and regression run: `./gradlew test`; manually inspect API JSON and static bundle for prohibited field names/unsafe `innerHTML`. | AC traceability is green; no P0/P1 privacy/auth finding. AC-001…006 |

## Ownership and waves

| Lane | Exclusive ownership |
|---|---|
| Backend writer | DTO/controller/diagnostic store, all AI instrumentation, provider parsing, Kotlin and integration tests |
| Static UI writer | `index.html`, `admin.js`, JS DOM tests only |

Wave 1 freezes T-001’s DTO/store contract. Wave 2 runs T-002 and T-003 in parallel with no shared files. Wave 3 runs T-004 after a stable combined diff. No DI/Hilt, Room, Android navigation/state restoration, permissions, background worker, migration, or Android quality gate applies; the existing scheduled job is observed only, not changed semantically.

## Gates, risks, rollback

Relevant gates: strict security/privacy review; focused backend unit/integration tests; admin auth and `Cache-Control: no-store`; bounded-concurrency/cleanup tests; JS DOM read-only test; final backend test command. Android Gradle gates are intentionally excluded because no Android source/config changes are in scope.

Risks: ThreadLocal propagation fails if work later changes executors—instrument the actual virtual-thread/provider call path and test nested restoration. Process-local data is incomplete after restart or across replicas—state this, do not invent persistence. Provider errors may omit/alter code/type/param—retain only fixed fallback categories. Rollback is removal of the endpoint/view/instrumentation; no user or DB data has been written.

## Gate P self-check

Every AC maps to T-001–T-004 and a command/inspection; contracts precede parallel work; ownership has no overlap; only backend/security/static conditional gates are included. Strict reviewer must specifically reject any raw-data escape, auth bypass, unbounded retention, or scope cleanup defect.
