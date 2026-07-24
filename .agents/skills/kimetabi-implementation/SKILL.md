---
name: kimetabi-implementation
description: Coordinate implementation of the タビキメワリ project across M0–M8 and the A/B/C parallel work lanes. Use when selecting the next implementation-plan task, checking milestone prerequisites, splitting work among three developers or agents, integrating lane outputs, reviewing milestone completion, or reporting progress against doc/IMPLEMENTATION_PLAN.md.
---

# Kimetabi Implementation

## Establish context

1. Read `/Users/tomi/work/kimetabi/AGENTS.md`.
2. Read the relevant sections of `doc/SPEC.md`; treat it as the product and architecture source of truth.
3. Read `doc/IMPLEMENTATION_PLAN.md` sections 5, 7, and 11.
4. Read [references/milestones.md](references/milestones.md).
5. Inspect the worktree and existing implementation before assigning or changing work.

Do not copy the plan into code comments or create a second task tracker. Update the specification when an implementation decision changes it.

## Select work

1. Identify the requested milestone and task IDs.
2. Confirm every prerequisite and prior milestone join condition.
3. Resolve task status from repository evidence, not assumptions.
4. Choose the lane skill:
   - A tasks: `$kimetabi-domain-backend`
   - B tasks: `$kimetabi-platform-security`
   - C tasks: `$kimetabi-frontend-pwa`
5. Load any additional repository-required skill before implementation.

When the user requests parallel agents, assign non-overlapping task IDs and list the files or contracts each agent owns. Otherwise work locally and preserve the same boundaries.

## Coordinate contracts

Before parallel implementation, fix only the shared seams needed by all lanes:

- OpenAPI paths and schemas
- Flyway migration number ownership
- resource IDs, versions, and Problem Details
- Outbox event names and payload fields
- MSW fixtures and acceptance examples

Do not let frontend code introduce handwritten API DTOs while waiting. Do not let multiple lanes edit the same migration. Prefer additive contract changes until all consumers migrate.

## Integrate

1. Review each lane against its task output and tests.
2. Run the milestone join tests from the plan.
3. Verify authorization, money invariants, idempotency, timezone boundaries, conflicts, offline retry, and revision recovery when relevant.
4. Update OpenAPI-generated types and documentation in the same change.
5. Do not mark a milestone complete until its join condition has repository evidence.

## Report

Report:

- completed task IDs
- files and contracts changed
- tests run and results
- remaining join conditions
- specification decisions or blockers
