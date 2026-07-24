# Milestone routing

Use this reference for routing only. Read `doc/IMPLEMENTATION_PLAN.md` for the complete task descriptions and join conditions.

| Milestone | A lane | B lane | C lane | Start condition |
|---|---|---|---|---|
| M0 | Domain states, schema, API | Boundaries, events, threats | Screens, state, E2E cases | Specification available |
| M1 | Spring/DB/API base | Security/operations/CI | React/data/test/PWA base | M0 contracts fixed |
| M2 | Trip/member domain | Firebase/authz/invites | Trip/join/app shell | M1 quality gates pass |
| M3 | Slots/candidates/votes | Authz/idempotency/events | Plan/compare/vote UI | M2 snapshot and auth ready |
| M4 | Metadata state/jobs | SSRF/extraction/Tasks | Async candidate UI | M3 candidate contract fixed |
| M5 | Expense/allocation | Storage/retry/audit | Capture/Dexie/confirm UI | M3 member and resource model ready |
| M6 | Settlement/recalculation | Transfer auth/audit/events | Settlement UI | M5 confirmed expenses ready |
| M7 | Revision/snapshot tests | STOMP/outbox recovery | Client recovery/E2E | Starts after M2; finishes after events exist |
| M8 | Regression/data operations | IaC/security/monitoring | PWA/E2E/accessibility | M3–M7 join conditions pass |

## Parallelism constraints

- M4 can run alongside M5 and M6 after the M3 candidate contract is stable.
- M7 skeleton can start after M2, then absorb events from M3–M6.
- M8 environment work can start early, but release validation waits for M3–M7.
- Each lane owns three numbered tasks per milestone as defined in plan section 7.

## Stop conditions

Stop and update the specification before implementation when:

- the plan and SPEC disagree materially;
- a missing schema field prevents an acceptance condition;
- a new external service or state store is required;
- authorization or money behavior cannot be determined;
- the change expands beyond domestic travel and JPY for MVP.
