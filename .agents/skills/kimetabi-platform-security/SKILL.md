---
name: kimetabi-platform-security
description: "Implement and audit the タビキメワリ B-lane platform and security boundaries: Firebase authentication, trip authorization, invite recovery, SSRF-safe URL metadata retrieval, Cloud Tasks, Transactional Outbox, STOMP WebSocket security, Cloud Storage uploads, Google Cloud infrastructure, logging, monitoring, and operational recovery. Use for M0–M8 tasks whose IDs contain “-B” and for any security, asynchronous processing, realtime synchronization, internal API, or GCP boundary change in this repository."
---

# Kimetabi Platform Security

## Prepare

1. Read `AGENTS.md`, relevant `doc/SPEC.md` sections, and the assigned plan task.
2. Read [references/security-boundaries.md](references/security-boundaries.md).
3. Load `$owasp-security-check` for every security-sensitive change.
4. Load `$java-springboot` for Spring Security, task handlers, STOMP, or backend infrastructure.
5. Load `$pwa-development` when changing Service Worker or retry contracts shared with the frontend.
6. Inspect threat boundaries, service identities, data classification, logs, retry behavior, and existing tests.

## Implement

- Separate authentication (“who”) from trip authorization (“may do what here”).
- Verify trip membership and resource ownership for REST and WebSocket independently.
- Treat user URLs, invite tokens, upload metadata, Firebase tokens, STOMP headers, and task payloads as untrusted.
- Make asynchronous handlers idempotent and safe for at-least-once delivery.
- Keep internal task and dispatch endpoints exclusive to the intended service-account OIDC identity.
- Log identifiers and outcome codes, not bearer tokens, invite tokens, share text, receipt contents, or raw sensitive URLs.
- Use least privilege and explicit configuration for Cloud Run, SQL, Tasks, Storage, Scheduler, and Secret Manager.

## Verify

1. Test deny cases before success cases: non-member, inactive member, wrong role, wrong trip, wrong identity.
2. Test replay, expiry, duplicate delivery, partial failure, and recovery.
3. For URL fetching, test each redirect hop and multiple IP encodings without public internet dependencies.
4. For WebSocket, test CONNECT and SUBSCRIBE authorization separately, then duplicate/gap recovery.
5. For infrastructure, verify CORS, CSP, backup/PITR, deletion protection, secret access, alerting, and rollback.
6. Run relevant Spring, security, and integration tests.

## Hand off

Document the trust boundary, identities and permissions, failure/retry semantics, observable signals, tests, and any residual risk. Do not describe an untested security control as complete.
