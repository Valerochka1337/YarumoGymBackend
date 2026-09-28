# Deterministic workout planner V2 wire contract

This is the frozen Android/backend contract. V2 JSON is UTF-8 application/json without a BOM,
duplicate keys, or unknown object keys. UUIDs are lowercase RFC-4122 strings.

## Negotiation and endpoints

Every V2 request sends `X-Planner-Protocol: 2`; responses send it and
`X-Gym-Capabilities: deterministic-workout-planner-v2`. V2 never falls back to V1. A 404, 405,
426, or missing capability is local UPDATE_REQUIRED. No header is V1; V1 retains its envelope,
lists AI only, and returns its existing 404 shape for RULE_BASED lookup/approval. An unfinished
V1 job terminalizes as existing FAILED with `ai_invalid_request` or `ai_context_stale`, keeping
its raw bytes.

| Operation | Method and path |
|---|---|
| Capability | `GET /v1/planning/v2/capabilities` |
| Create | `POST /v1/planning/v2/jobs` |
| Job status/cancel | `GET` / `DELETE /v1/planning/v2/jobs/{requestId}` |
| Refine | `POST /v1/planning/v2/proposals/{proposalId}/refinements` |
| Mapping list | `GET /v1/planning/v2/exercise-mappings` |
| Mapping detail/write/delete | `GET` / `PUT` / `DELETE /v1/planning/v2/exercise-mappings/{exerciseId}` |
| Proposal/explanation | Existing `/v1/training-proposals/{proposalId}` and `/planner-explanation`, with V2 header |

Capability response has exactly this closed schema:

```json
{"schemaVersion":2,"protocol":2,"capability":"deterministic-workout-planner-v2"}
```

## Create and job result

`POST /v1/planning/v2/jobs` stores/replays using owner + requestId + exact raw bytes; different
bytes for an existing key yield existing `ai_request_conflict`. A new alternative has a new UUID.

```json
{
  "requestId":"5a78bd9b-8c67-4dd8-9ff3-2e0b52593e02",
  "variant":0,
  "expectedRevision":117,
  "expectedCatalogRevision":42,
  "startsAtMillis":1790578800000,
  "timeZoneId":"Europe/Moscow",
  "gymIds":["63c84698-4f78-41da-90c4-e2e3435c74ce"],
  "excludedExerciseIds":[],
  "excludedEquipmentIds":[],
  "priorityMuscles":["UPPER_CHEST"],
  "includeNotes":false,
  "availableDurationMinutes":60
}
```

Every field is required except `variant`, whose default is 0. `includeNotes` must be false and
exists only for structured compatibility. V1 `currentState` and `preferences` do not exist in
V2. Goal and profile are captured server owner facts; null/OTHER goal becomes GENERAL_FITNESS and
the explanation reports `GOAL_DEFAULTED_TO_GENERAL_FITNESS`. Current gyms and excluded equipment
remain the equipment constraint. Existing validation remains: duration 10..240, known muscles,
unique arrays, canonical UUIDs.

The exact existing job envelope is frozen for V2 (there are no status/current/stage/poll fields):

```json
{"requestId":"5a78bd9b-8c67-4dd8-9ff3-2e0b52593e02","state":"QUEUED","errorCode":null,"result":null}
```

This is the 202 create response and GET response. State is QUEUED, RUNNING, READY, FAILED, STALE,
EXPIRED, SUPERSEDED, or IMPOSSIBLE. DELETE returns 204; subsequent GET returns SUPERSEDED.
`result` is null unless READY. A READY result has the complete existing CalendarDraftResponse,
including complete ProposalResponse:

```json
{
  "requestId":"5a78bd9b-8c67-4dd8-9ff3-2e0b52593e02",
  "context":{"revision":117,"catalogRevision":42,"capturedAtMillis":1790575200000},
  "proposal":{
    "proposalId":"e56e40bc-8c80-4bdf-92fd-35ab62e70236",
    "author":{"kind":"RULE_BASED","accountId":null},
    "recipientId":"cc0f83cf-a748-49b7-8392-74a97a458b06",
    "source":"RULE_BASED",
    "status":"PENDING",
    "currentVersion":1,
    "createdAt":1790575200000,
    "updatedAt":1790575200000,
    "expiresAt":1791180000000,
    "snapshot":{
      "version":1,
      "draft":{
        "name":"Тренировка",
        "gymIds":["63c84698-4f78-41da-90c4-e2e3435c74ce"],
        "exercises":[{"exerciseId":"d40c9207-afb0-4d56-9e1a-3d36e2f19598","restSeconds":120,"plannedSets":[{"weightKg":60.0,"reps":10,"durationSec":null,"speedKmh":null,"inclinePct":null}]}],
        "startsAtMillis":1790578800000,
        "timeZoneId":"Europe/Moscow"
      },
      "ownerRevision":117,
      "catalogRevision":42,
      "createdAt":1790575200000
    }
  }
}
```

IMPOSSIBLE uses NO_FEASIBLE_PLAN, PLANNER_LIMIT_REACHED, or PLANNER_TIMEOUT as errorCode. FAILED,
STALE, EXPIRED, and SUPERSEDED retain the V1 code vocabulary.

## Typed refinement

`POST /v1/planning/v2/proposals/{proposalId}/refinements` accepts only caller-owned RULE_BASED
proposals. It creates a new queued job and never mutates the local/manual ApprovalDraft.

```json
{
  "requestId":"cfdf3ca6-73c9-4c4d-807a-e427b6592f65",
  "variant":1,
  "expectedRevision":117,
  "expectedCatalogRevision":42,
  "baseProposalVersion":1,
  "approvalDraft":{"name":"Тренировка","gymIds":["63c84698-4f78-41da-90c4-e2e3435c74ce"],"exercises":[],"startsAtMillis":1790578800000,"timeZoneId":"Europe/Moscow"},
  "changes":[
    {"kind":"REPLACE","slotId":"upper-push","selectionId":"upper-push-1","exerciseId":"d40c9207-afb0-4d56-9e1a-3d36e2f19598"},
    {"kind":"EXCLUDE","exerciseId":"f51ab0aa-0c48-4e85-b494-ed9356660cc8"}
  ]
}
```

variant defaults to 0; all other fields are required. approvalDraft is exact existing
ApprovalDraft. changes contains 1..100 entries. REPLACE requires an exact captured
(slotId, selectionId) and a hard-eligible exercise. EXCLUDE only takes exerciseId. Kinds are
REPLACE and EXCLUDE; arbitrary text is absent and rejected. The 202 response is the exact job
envelope above, and Android journals this new requestId then opens its new READY proposal.

## Proposal and explanation

V2 list/get/approval retains the complete existing ProposalResponse. RULE_BASED is additive to
the existing source enum; approval bytes and ApprovalDraft are unchanged.

A RULE_BASED explanation keeps all existing keys and adds all deterministic keys below:

```json
{
  "proposalId":"e56e40bc-8c80-4bdf-92fd-35ab62e70236",
  "version":1,
  "durationSpec":"planner-duration-v2",
  "desiredMinutes":60,
  "estimatedSeconds":3420,
  "minimumSeconds":2700,
  "focusMuscles":["UPPER_CHEST"],
  "repeatedExerciseIds":[],
  "lastFinishedAtMillis":null,
  "eligibleExerciseCount":14,
  "selectionReason":"RULE_BASED",
  "repeatReason":"NONE",
  "shortfallReason":"NONE",
  "algorithm":"deterministic-planner-v2",
  "algorithmVersion":2,
  "inputFingerprint":"sha256 hex",
  "effectiveGoal":"MUSCLE_GAIN",
  "terminalCode":null,
  "optimalityNotGuaranteed":false,
  "nodeCount":112,
  "evaluationCount":3,
  "ruleIds":["goal-muscle-gain","equipment-gym","accent-more"],
  "reasons":[],
  "slotSelections":[{"slotId":"upper-push","selectionId":"upper-push-1","exerciseId":"d40c9207-afb0-4d56-9e1a-3d36e2f19598"}]
}
```

Only lastFinishedAtMillis and terminalCode are nullable. focusMuscles, repeatedExerciseIds,
ruleIds, reasons, and slotSelections are non-null arrays and may be empty. Every other key is
non-null. Legacy explanations decode unchanged and do not acquire synthetic deterministic values.

## Mapping

Mapping list response is the closed envelope `{"items":[<mapping>]}`. Detail GET and successful
PUT return one mapping. DELETE returns 204.

```json
{
  "exerciseId":"d40c9207-afb0-4d56-9e1a-3d36e2f19598",
  "movementClass":"HORIZONTAL_PUSH",
  "roles":["PRIMARY","ACCESSORY"],
  "supportedGoals":["STRENGTH","MUSCLE_GAIN","GENERAL_FITNESS"],
  "exerciseType":"STRENGTH",
  "equipmentIds":["barbell","bench"],
  "revision":1
}
```

PUT omits revision. movementClass is HORIZONTAL_PUSH, HORIZONTAL_PULL, VERTICAL_PUSH,
VERTICAL_PULL, SQUAT, HIP_HINGE, LUNGE, CARRY, CORE, CARDIO, or MOBILITY. roles is a non-empty
unique sorted subset of PRIMARY, ACCESSORY, CONDITIONING. supportedGoals is a non-empty unique
sorted subset of STRENGTH, MUSCLE_GAIN, FAT_LOSS, GENERAL_FITNESS, ENDURANCE. exerciseType is
STRENGTH, TIMED, or CARDIO. equipmentIds is a unique sorted string array. An unmapped personal
exercise remains manually editable but cannot be automatically selected. Owner mappings are
owner-only; built-ins use admin-only `/admin/api/planner-exercise-mappings/{exerciseId}`.
