---
name: kimetabi-frontend-pwa
description: Implement and review the タビキメワリ C-lane React TypeScript Vite PWA, including React Router Data Mode routes, TanStack Query server state, React Hook Form and Zod forms, generated OpenAPI clients, Dexie offline queues, Workbox Service Worker behavior, STOMP synchronization, responsive accessible UI, MSW tests, and Playwright E2E. Use for M0–M8 tasks whose IDs contain “-C” or any frontend, PWA, offline, browser, accessibility, or visual implementation change in this repository.
---

# Kimetabi Frontend PWA

## Prepare

1. Read `AGENTS.md`, relevant `doc/SPEC.md` sections, the assigned plan task, and the matching screen in `doc/screen-design.html`.
2. Read [references/frontend-boundaries.md](references/frontend-boundaries.md).
3. Load `$vercel-react-best-practices` for React work.
4. Load `$pwa-development` for manifest, Service Worker, cache, Dexie retry, or offline work.
5. Load `$webapp-testing` before browser or Playwright verification.
6. Load `$ui-design-review` when reviewing or materially changing visual UI.
7. Inspect generated OpenAPI types, MSW fixtures, route structure, and current design tokens.

## Implement

- Keep REST state in TanStack Query, forms in React Hook Form, persistent offline state in Dexie, and local interaction in React state.
- Use generated OpenAPI types through `openapi-fetch`; do not handwrite duplicate DTOs.
- Use React Router Data Mode for route lifecycle and error boundaries, without duplicating Query cache in loaders.
- Apply server results immediately after mutations; use WebSocket only to invalidate or refresh committed data.
- Preserve the prototype’s visual language while implementing responsive behavior, semantic HTML, keyboard focus, reduced motion, and empty states.
- Keep one phase-dependent primary action and stable Home/Plan/Expenses tabs.

## Offline and synchronization

1. Generate an idempotency key when creating an offline operation and reuse it on every retry.
2. Store target version, payload/blob, creation time, and retry state in Dexie.
3. Delete an operation only after confirmed server success.
4. Refresh tokens on 401; route 409 to conflict UI; retain permanent 4xx for user correction.
5. Retry on startup, online recovery, and focus; add Background Sync only as an enhancement.
6. On STOMP reconnect, fetch the REST snapshot before subscribing. Deduplicate event IDs and recover revision gaps through REST.

## Verify

- Test components with RTL and REST boundaries with MSW.
- Test success, loading, empty, offline, 401, authorization denial, 409, and permanent validation failure.
- Use Playwright for anonymous join, candidate flow, draft expense, settlement, multiple browsers, offline retry, and reconnect.
- Check mobile width, keyboard order, visible focus, reduced motion, and supported camera/file fallbacks.
- Verify Service Worker, manifest, Share Target, and offline start over HTTPS preview when applicable.

## Hand off

Report task IDs, routes/components changed, generated contract version, offline states handled, accessibility checks, browser/E2E results, and backend contract gaps.
