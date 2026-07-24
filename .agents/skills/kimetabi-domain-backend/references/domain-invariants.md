# Domain invariants

## Trip and membership

- Exactly one active OWNER must exist per trip.
- OWNER cannot leave before transfer.
- Deleted or departed members remain referenced by historical votes, expenses, and transfers.
- Phase uses the trip IANA timezone; `phase_override` wins without mutating business data.

## Candidate planning

- Candidate-per-person estimates use `expected_member_count`, not joined members.
- Adoption candidate, slot, and trip must match.
- Adoption and `plan_item` creation/update are atomic.
- A linked confirmed expense blocks adoption removal.
- Stale versions return `409 Conflict`; do not use last-write-wins.

## Expense

- DRAFT may contain only a receipt; CONFIRMED requires payer, positive amount, JPY, paid time, positive base amount, allocation type, and at least one share.
- Every share member must belong to the trip.
- EQUAL uses weight 1; WEIGHT uses positive weights; FIXED_AND_WEIGHT rejects fixed totals above the expense.
- Persist the final integer burden per member for reproducibility.
- Allocate remaining yen by largest remainder; break ties by ascending member ID.
- Final burdens must sum exactly to `base_amount`.

## Settlement

- Member balances sum to zero.
- Transfer total equals total debt.
- Greedy transfer count is at most non-zero-member count minus one.
- Confirmed settlements are immutable.
- Later expense changes create an unapplied-change state and a new draft.
- Paid transfers remain historical facts and participate in recalculation.

## Transactions

For a business change, keep data mutation, resource version, trip revision, audit record, and Outbox event in the same database transaction whenever those records apply.
