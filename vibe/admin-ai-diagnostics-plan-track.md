# Admin AI diagnostics — tracker

| Task | Status | AC | Verification / done evidence |
|---|---|---|---|
| T-001 | pass | AC-001, AC-003, AC-004 | Focused gate passed: DTO allowlist, bounds, expiry, ThreadLocal cleanup, DB/queue aggregate, and admin auth coverage. |
| T-002 | pass | AC-002, AC-004, AC-005 | Focused gate passed: job/provider correlation, pre-provider/local fixed category, and code/type/param-only non-200 classification. |
| T-003 | pass | AC-004, AC-006 | `node --test src/test/js/*.test.cjs`: 21 passed; read-only rendering/filter/copy and safe DOM. |
| T-004 | pass | AC-001…AC-006 | Full 337-test run found one new category-precedence failure; corrected and targeted regression plus bootJar/Spotless passed. Independent privacy review and narrow fix review passed. |

## AC traceability

| AC | Tasks | Automated evidence |
|---|---|---|
| AC-001 | T-001, T-004 | diagnostics/admin-auth integration tests; full backend test |
| AC-002 | T-002 | calendar job/agent/provider tests |
| AC-003 | T-001 | diagnostics store concurrency, cap, TTL, nested cleanup tests |
| AC-004 | T-001, T-002, T-003 | serialization/redaction, provider error, JS safe-DOM tests |
| AC-005 | T-002 | non-200 allowlisted code/type/param and unknown fallback tests |
| AC-006 | T-003 | JS DOM read-only state/filter/refresh/copy tests |

## Deviations

Backend-only branch `feat/admin-ai-diagnostics` from `e1e89b9`. Android and its version are unchanged.
Durations use a bounded injected wall clock rather than monotonic time; backward jumps clamp to zero.

## Findings

- `CalendarDraftJobService.runNext` currently collapses failures after claim; diagnostics must begin before `provider.available`.
- Planner create/refine loops and HTTP planner-turn non-200 handling are the primary `ai_unavailable` correlation seams.
- `AdminFilter` already protects `/admin/api/**` and sets `no-store`; the new GET must stay inside it.

## Command results

`JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --tests '*AiDiagnostics*' --tests '*Calendar*' --tests '*HttpOpenAiChatCompletionsProviderTest'` — passed (2026-09-20).

## Residual risks

- Process-memory diagnostics cannot explain a previous process/replica; UI must say so.
- Security/privacy review completed without remaining P1/P2 findings.
- Publication explicitly approved by user; release in progress. Production error cannot be reconstructed retrospectively: after deployment,
  reproduce once in the app and inspect the new diagnostic run. No live provider request was issued.

Final commands: `./gradlew spotlessApply check bootJar` ran 337 tests (336 passed, one new assertion
exposed loss of INTERNAL beneath AI_INVALID_RESPONSE). Fixed precedence without changing planner
behavior. Then `./gradlew spotlessApply spotlessCheck test --tests '*AiDiagnostics*' --tests
'*CalendarAiCaptureIntegrationTest' --tests '*HttpOpenAiChatCompletionsProviderTest' --tests
'*CalendarPlannerAgentTest' bootJar` passed. `git diff --check` passed.
