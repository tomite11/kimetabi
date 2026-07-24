---
name: kimetabi-domain-backend
description: Implement and review the タビキメワリ A-lane Spring Boot domain backend for trips, members, slots, candidates, votes, expenses, allocations, settlements, database migrations, REST contracts, and money invariants. Use for M0–M8 tasks whose IDs contain “-A”, or whenever changing backend business rules, PostgreSQL schemas, optimistic locking, idempotent domain writes, allocation rounding, or settlement calculations in this repository.
---

# Kimetabi Domain Backend

## Prepare

1. Read `AGENTS.md`, the relevant `doc/SPEC.md` sections, and the assigned task in `doc/IMPLEMENTATION_PLAN.md`.
2. Read [references/domain-invariants.md](references/domain-invariants.md).
3. Load `$java-springboot` before changing Spring Boot code.
4. Load `$owasp-security-check` when the task changes input validation, authorization, REST boundaries, or sensitive data.
5. Inspect current migrations, OpenAPI, tests, and the parallel lane contracts.

## Implement

1. Express money and state invariants in a pure domain layer first.
2. Add a forward-only Flyway migration with foreign keys, checks, indexes, and audit columns needed by the acceptance conditions.
3. Keep `/api/trips/{tripId}` as the authorization parent and verify nested resource ownership in the service layer.
4. Perform business update, optimistic version check, `trip.revision` increment, audit record, and Outbox insertion in one transaction when applicable.
5. Return the updated complete resource and version. Return Problem Details with the current value for `409 Conflict`.
6. Update OpenAPI before the frontend integrates; never create an alternate DTO contract.

Use Java four-space indentation, immutable value objects for calculations, `BigDecimal` with explicit scale/rounding, and IANA timezone-aware types.

## Test

- Unit-test state transitions, timezone boundaries, allocation, rounding, balances, and settlement.
- Use Testcontainers for constraints, transactions, optimistic locking, idempotency, and Outbox tests.
- Add authorization tests for role and cross-trip resource access when an endpoint changes.
- Prefer property tests for sum invariants and deterministic rounding.
- Test repeated requests and concurrent updates, not only the happy path.

Run the backend commands documented in the root README.

## Review

1. Load `$code-review-excellence` after implementation and tests.
2. Review the complete diff for correctness, transaction boundaries, data integrity, authorization, concurrency, migration safety, API compatibility, and test gaps.
3. Fix every critical and high-priority finding.
4. Rerun all tests affected by review fixes.

Do not claim completion while tests fail, required tests or code review were skipped, critical/high findings remain unresolved, or the assigned milestone join condition remains unverified.

## Hand off

Provide:

- assigned task IDs and completed acceptance conditions
- migrations and OpenAPI schemas added
- invariants and concurrency cases tested
- test commands and results
- code review findings and resolutions
- B/C lane contracts that changed
- unresolved specification decisions
