# Calendar AI backend — Stage 21 plan

Slug: `calendar-ai`. This is the backend-only one-off AI proposal creator. Its authoritative executable contract is [`vibe/contracts/calendar-ai-contract.json`](contracts/calendar-ai-contract.json), schema version 2. PLAN-01 remains the sole apply writer; its pinned fixture is `src/test/resources/training-proposals-contract.json` SHA-256 `65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998` and the real creation seam is `TrainingProposalAiCreator.createOrRevise` to `TrainingProposalService.createOrReviseInternalAi`.

## Scope and acceptance

The route is additive `POST /v1/ai/calendar-drafts`. It captures bounded owner data, obtains a strict provider-shaped draft without provider-supplied weights/speed/incline, validates and server-projects the draft, then atomically creates a pending AI PLAN-01 proposal. `GET /v1/ai/status` gains frozen `CALENDAR_DRAFT` when available. Android/readiness/apply, direct routine/calendar writes, recurring plans, Google I/O, health/recovery advice and PLAN-01 editable-preview equality are out of scope.

| ID | Criterion |
|---|---|
| B-AC-001 | Exact Ready revisions, future zone/slot, live selected-gym intersection and nonempty bounded candidates are required before provider admission. |
| B-AC-002 | `catalog → owner head → authenticated session` is used at reserve/capture/final; provider I/O has no DB lock; stale/deleted/session races create no proposal. |
| B-AC-003 | Context is owner-only, backend-built and bounded; it excludes raw health/InBody, credentials, prompts, provider data and future facts. |
| B-AC-004 | Goal groups order the **context only** before priority/coverage/UUID. They do not require a proposal prefix/seed or promise physiology. |
| B-AC-005 | Strength weight is latest eligible same-exercise `actualWeightKg` including explicit null, else legacy `weightKg`; other exercise/target/original data never contributes. |
| B-AC-006 | Missing optional profile/mass/history/notes does not block. Note opt-out excludes notes and hints. No health disclosure gates calendar or permits health data. |
| B-AC-007 | Strict raw/DTO/provider/context/type/availability/exclusion/duration validation rejects the whole attempt with zero proposal. |
| B-AC-008 | Durable attempt, cancellation, deadline, crash and final-commit races are idempotent and never retain raw/provider payload. |

## Target execution and durable boundary

```text
raw request → validate + SHA-256 → reserve owner/requestId ledger
 → outer relation guard + bounded capture tx → release all locks
 → provider strict schema outside tx → strict validation + server projection
 → outer relation guard + final locks + COMMITTING ticket → proposal + SUCCEEDED receipt atomically
 → replay normalized terminal receipt/outcome; a new requestId is required to retry
```

Migration `013-calendar-ai-attempts.sql` follows the training-proposals schema. It owns `(owner_id, request_id)`, exact raw digest, lease and sanitized terminal outcome/typed receipt identity only. It stores no raw request, prompt or provider input/output/error. States are `PROCESSING`, `COMMITTING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED`; 45 seconds runs from admission and the 60-second lease converts abandoned work to `INTERRUPTED`, never a same-ID restart. Same ID/different digest is `409 ai_request_conflict`; processing/committing is `409 ai_in_progress`; success replays without provider/new proposal; terminal failure replays sanitized outcome. Actor deletion cleans its attempt rows in the actual proposal-creator deletion order.

DeferredResult and worker share a cancellation/commit ticket. A timeout/disconnect before final ownership atomically creates `CANCELLED` and no proposal. Under the declared lock order, final transaction claims `COMMITTING` immediately before insert while deadline remains live. The winner writes proposal plus `SUCCEEDED` receipt in that transaction; later callback cannot undo a committed winner. Failed DB commit leaves no proposal/success and terminalizes failure.

## Context, ranking and provider contract

The strict request, full provider context, provider output, response and status schemas have `additionalProperties:false`, required fields, nullability and bounds in the contract. Raw HTTP media is JSON UTF-8, max 524288 bytes, rejecting empty/BOM/invalid UTF-8/trailing JSON/duplicate and unknown fields. The complete error/status matrix and vectors are also there.

Selected gyms are the live owner-gym/catalog availability intersection. Candidate reads use owner/kind/catalog indexes, `LIMIT 1001` then reject above 1000. History uses a partial functional numeric `finishedAt` index for nondeleted owner workouts, materializes at most 65 matching parents, rejects above 64 before set expansion, then rejects above 8192 facts. A fact is accepted only when completed and `finishedAt ≤ capturedAt`, `startedAt ≤ factTime ≤ finishedAt`, and inside `[startOfDay(capturedLocalDate−27), capturedAt]`; invalid temporal source data is excluded with a marker, never modified. Bounded notes share these sources, have exact timestamp fallbacks and whole-entry 20/16384 UTF-8 limits. Latest mass is indexed `measuredAt ≤ capturedAt LIMIT 1`; total source bytes and final context are bounded (1 MiB), with an incompatible source-body cap reported rather than loosened.

Goal grouping is a deterministic ordering of complete candidate context only, then user priority, least mapped-muscle coverage and UUID. It is an explicit product heuristic, not output selection, physiological proof or a required prefix. The provider schema is named `calendar_draft` and contains only `exerciseId`, rests, reps and duration. Server fills speed/incline null and applies the frozen same-exercise actual-null/legacy weight rule. For timed/cardio, known work plus defined rests must fit `availableDurationMinutes`; strength has no claimed exact duration fit. Envelope limits are engineering bounds, not a clinical recommendation.

## Tasks and traceability

| Task | Owner | Depends on | Work and required evidence |
|---|---|---|---|
| T-001 | fixture/contract owner | accepted PLAN-01 fixture | Copy pinned fixture byte-for-byte; validate contract schemas and all V-001…V-011 before implementation. |
| T-002 | sole backend writer | T-001 | Append migration 013/master, ledger/repositories/cleanup, raw route DTO, `AiActionService` ticket/status action and `AiProvider`/`HttpOpenAiChatCompletionsProvider` calendar schema naming. Tests: `AiActionServiceTest`, provider test, `AiIntegrationTest`, `CalendarAiIntegrationTest`. |
| T-003 | sole backend writer | T-002 | Bounded `AiContextReader` SQL/indexes/projections, relation guard order, server projection and typed creator receipt path. Add `EXPLAIN` and source-limit tests. |
| T-004 | sole backend writer | T-002,T-003 | Deterministic barrier/evaluation vectors V-001…V-011, including lost response, concurrent same ID, crash lease, final lock/preinsert winners, deletion, raw bytes, DST, 65/8193/1001, notes/weight/duration. |
| T-005 | independent focused tester | T-004 | Run focused contract/integration/provider tests and verify every vector/result count without changing production. |
| T-006 | independent read-only Sol reviewer | T-004 | Narrow strict review of ledger/lock/cancellation/SQL/schema/privacy traceability. |
| T-007 | original backend writer | T-005,T-006 findings | Consolidated repair and affected focused evidence; tracker records deviations. |
| T-008 | root only | accepted T-007 | One backend `check bootJar`; Android/apply AC-009/010 remain separately blocked/delegated. |

| AC | Tasks | Mandatory vectors |
|---|---|---|
| B-AC-001 | T-002–T-004 | V-001, V-006, V-008 |
| B-AC-002 | T-002–T-004 | V-005, V-011 |
| B-AC-003 | T-003,T-004 | V-006, V-007 |
| B-AC-004 | T-003,T-004 | V-008 |
| B-AC-005 | T-003,T-004 | V-009 |
| B-AC-006 | T-003,T-004 | V-007 |
| B-AC-007 | T-002–T-004 | V-001, V-006, V-009, V-010 |
| B-AC-008 | T-002–T-004 | V-002–V-005, V-011 |

Relevant focused command (after implementation only): `./gradlew --no-daemon test --tests '*CalendarAiIntegrationTest' --tests '*AiActionServiceTest' --tests '*AiIntegrationTest' --tests '*HttpOpenAiChatCompletionsProviderTest' --tests '*TrainingProposalIntegrationTest' spotlessCheck`. No Gradle runs in this planning repair.


## Root repair of surviving Gate P findings

This section supersedes conflicting shorthand above. Gate P remains pending until the repaired
fixture is independently accepted. One contract writer owns only
`vibe/contracts/calendar-ai-contract.json`; root owns this plan and tracker. No runtime change is
permitted by planning acceptance alone to the separately blocked PLAN-01 equality patch.

### Exact runtime ownership and standalone schema

The eventual sole backend writer owns new calendar action/controller DTOs, attempt entity/repository,
`src/main/resources/db/changelog/013-calendar-ai-attempts.sql`, master.yaml, bounded context queries,
`AiActionService`, `AiProvider`, `HttpOpenAiChatCompletionsProvider`, and the narrowly added typed
calendar creator entry point in `TrainingProposalAiCreator`/`TrainingProposalService`. It also owns
`src/main/resources/ai/calendar-output-schema.json` and relevant tests. The standalone schema has
ProviderOutput at root and only its transitive ProviderExercise, ProviderSet and Uuid definitions;
tests compare its JSON tree with extraction from the accepted fixture and assert the provider sends
schema name `calendar_draft`. Existing exercise/InBody schema routing remains covered.

T-001: the backend writer copies the accepted calendar fixture byte-for-byte from
`vibe/contracts/calendar-ai-contract.json` to `src/test/resources/calendar-ai-contract.json`;
record its final SHA and pinned PLAN-01 SHA. The planner does not edit test resources.

### Bounded SQL from capture through final creation

Every materialized source payload is at most 1,048,576 serialized UTF-8 bytes; the sum of source
payload bytes is at most 8,388,608. This is an AI admission limit, not a new sync restriction:
SyncService currently permits a 10 MiB request and a 16 MiB owner archive, so valid existing data
can exceed these AI limits and produce `ai_context_too_large` while remaining unchanged.

First materialize only bounded row keys, numeric indexed times and SQL payload byte lengths;
reject oversized counts/rows/aggregate lengths before fetching payload text or expanding arrays.
The numeric finishedAt expression/partial index must tolerate all existing valid timestamp values
and match the query predicate; date tests include numeric decimal representations accepted by sync.
History parent limit is 65 (reject >64), facts 8193 (reject >8192), candidate identity limit 1001
(reject >1000). Selected gyms are at most1000, relevant equipment catalogue keys at most2000,
profile and latest mass at most one each, candidate hints at most1000. Count sentinel rows do not
need payload hydration. The combined byte budget covers every fetched dependency, not just history.
SQL EXPLAIN on a large account must demonstrate no old-workout JSON expansion and bounded
materialization; LIMIT after an unbounded JSON expansion is insufficient. Partial owner/kind/time
indexes also serve latest mass and an active-workout EXISTS probe. Exact array expansion is limited
to the admitted finite payloads; provider context is separately limited to 1,048,576 UTF-8 bytes.

The current internal creator calls two unbounded scans:
`TrainingProposalService.validateLiveDraft()` and `aiDraftWithHistoryWeights()` both call
`findByUserIdOrderByKindAscIdAsc`. T-003 must introduce a typed internal calendar entry point which
uses a bounded live graph of selected exercise, gym and equipment dependencies and an indexed
active-workout EXISTS query. It applies the same draft, type, equipment, availability, archival,
reference, owner, session and revision checks; it does not call either unbounded helper. Reuse/extract
pure validation over that bounded graph rather than weakening checks. The captured server-only
weight projection is used only after final revision equality under locks. Ordinary approval and
existing creator behavior keep their current contract; the edited-preview equality is untouched.
Tests must instrument/verify no whole-owner repository reads on the new path, including final commit.

### Frozen calculations and minimized context

Priority is the sum of canonical muscle contributions (100/50/0) for requested muscles. Coverage
of a candidate is the sum, over its nonzero mapped muscles, of completed-set contribution totals
in the accepted 28-day facts, in integer hundredths of a set. No duration, repetitions or weight is
invented for this score. Context order is goal group descending, priority descending, coverage
ascending, canonical UUID ascending; it imposes no selected-output prefix.

The provider receives only ProviderIntent (zone, planned local date/time, duration, priority muscles,
current state and preferences), candidates, bounded profile/mass, opt-in notes, captured local date
and quality markers. Request IDs, revisions, gym/exclusion IDs and history weight-resolution tuples
remain server-only. Candidate UUIDs remain because the model selects them. Source fact tuples and
actual-member presence/null are server-only data used by projection and ranking.

Latest weight order is factTime descending, workout UUID ascending, section UUID ascending,
setIndex ascending. Workout note time is finishedAt, set note time is factTime, hint time is
updatedAt; all obey the same captured window and future cutoff. With includeNotes=false, no notes
or hints are emitted. Known duration is all TIMED/CARDIO set durations plus
sum(restSeconds * (setCount - 1)) across all exercises, treating null rest as zero. It must fit
availableDurationMinutes * 60; strength execution duration remains unknown.

### Immutable replay and verification

The durable success receipt stores the exact normalized original CalendarDraftResponse JSON tree,
including captured revision/time and initial PENDING proposal/version projection. Retrying returns
that tree even if the separate proposal later becomes approved/rejected; it never reconstructs the
receipt from mutable current proposal state. It is validated domain data, not raw provider output.
Cancellation maps to 504 ai_timeout; expired abandoned reservation to 409 ai_interrupted; failure
stores its sanitized status/code from the frozen matrix. The cancellation ticket and final commit
are serialized through transaction completion, with exact barrier tests for both winners.

Independent focused T and read-only Sol V start together after stable T-004. Neither changes
production. Root consolidates findings, then runs check/bootJar once after acceptance. Concrete
fixture vectors must contain actual JSON/raw bytes/hashes/times, barriers and expected row/response
counts rather than prose labels; schema acceptance is not inferred from JSON parsing alone.
