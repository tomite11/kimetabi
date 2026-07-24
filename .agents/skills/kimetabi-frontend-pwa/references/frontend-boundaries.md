# Frontend boundaries

## State ownership

| State | Owner |
|---|---|
| REST resources and aggregates | TanStack Query |
| Form values and immediate validation | React Hook Form + Zod |
| Offline snapshots, blobs, pending operations | Dexie.js |
| Temporary selection, dialog, visual state | React state |
| API contract | Generated OpenAPI types |

Do not introduce Redux or Zustand without a specification decision.

## Routes

- `/` trip list
- `/t/{tripId}` phase-adaptive home
- `/t/{tripId}/plan` slots and confirmed plan
- `/t/{tripId}/plan/{slotId}` candidate comparison
- `/t/{tripId}/expenses` expenses and draft tray
- `/t/{tripId}/expenses/new` capture or amount entry
- `/t/{tripId}/settle` settlement
- `/join/{inviteToken}` invitation
- `/candidates/import` Share Target confirmation

## PWA safety

- Precache only the application shell and static assets.
- Never store authenticated API responses in Cache Storage.
- Keep local offline data scoped by Firebase UID and trip.
- Make upload and mutation progress visible.
- Keep a foreground retry path because Background Sync is not universal.
- Treat Service Worker updates as a data-compatibility event; do not silently discard old Dexie operations.

## Design

- Use the prototype color, typography, spacing, journey tape, phase marker, stable tabs, and single bottom action.
- Show per-person amount as primary and total as secondary.
- Use vertical candidate cards on mobile; enable comparison tables only for four or more candidates at tablet/desktop widths.
- Provide deliberate empty states for trip, candidate slot, and expenses.
