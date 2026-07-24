# Security boundaries

## User authentication and authorization

- Accept Firebase ID tokens for public REST and STOMP CONNECT only.
- Convert verified UID to `AppPrincipal`.
- Resolve ACTIVE membership and trip role from PostgreSQL on authorization.
- Do not place trip roles in Firebase custom claims.
- Hide trip existence with 404 where required; use 403 for known members lacking permission.

## Invitations and recovery

- Generate at least 128 bits of entropy and store only token hashes.
- Enforce expiry, single use, revocation, and IP/token rate limits.
- Keep historical references during UID recovery and logical deletion.

## URL metadata SSRF

- Allow only HTTP/HTTPS and ports 80/443.
- Reject loopback, private, link-local, multicast, unspecified, and metadata endpoints for IPv4 and IPv6.
- Resolve and validate every address; manually follow and validate each redirect.
- Bound connection time, total time, body size, redirect count, and per-host concurrency.
- Treat DNS/5xx as retryable only per specification; keep manual fallback.

## Async and realtime

- Store data, revision, and Outbox atomically.
- Deliver at least once; deduplicate with event/task ID.
- Require service-account OIDC for `/internal/**`.
- Authorize both STOMP CONNECT and SUBSCRIBE.
- Recover event gaps and reconnects through REST snapshot before subscription.

## Storage and operations

- Store object keys, not public receipt URLs.
- Validate MIME type and size; clean orphaned uploads.
- Keep secrets out of source, logs, errors, and frontend bundles.
- Configure Cloud SQL backup, PITR, deletion protection, and tested restore.
- Keep Cloud Run at one instance while using the in-process STOMP broker.
