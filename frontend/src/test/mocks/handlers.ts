import { http, HttpResponse } from "msw";

import {
  amountExpenseDraft,
  emptyTripPage,
  tokyoTripSnapshot,
  transportSlotDetail,
} from "./fixtures";

export const handlers = [
  http.get("*/api/trips", () => HttpResponse.json(emptyTripPage)),
  http.post("*/api/trips", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;

    return HttpResponse.json(
      {
        ...tokyoTripSnapshot,
        trip: {
          ...tokyoTripSnapshot.trip,
          title: body.title,
          destination: body.destination,
          startsOn: body.startsOn,
          endsOn: body.endsOn,
          expectedMemberCount: body.expectedMemberCount,
        },
      },
      { status: 201, headers: { Location: "/api/trips/42" } },
    );
  }),
  http.get("*/api/trips/42", () => HttpResponse.json(tokyoTripSnapshot)),
  http.post("*/api/trips/42/expenses", async ({ request }) => {
    const body = (await request.json()) as { amount?: number };
    return HttpResponse.json(
      { ...amountExpenseDraft, amount: body.amount ?? null },
      { status: 201 },
    );
  }),
  http.get("*/api/trips/42/expenses", () =>
    HttpResponse.json({ items: [], nextCursor: null }),
  ),
  http.get("*/api/trips/42/expenses/share-preset", () =>
    HttpResponse.json({
      sourceExpenseId: 700,
      allocationType: "EQUAL",
      shares: [
        { memberId: 7, weight: 1 },
        { memberId: 8, weight: 1 },
      ],
    }),
  ),
  http.patch("*/api/trips/42/expenses/:expenseId", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json({
      ...amountExpenseDraft,
      ...body,
      status: "CONFIRMED",
      currency: "JPY",
      baseAmount: body.amount,
      version: 1,
      shares: ((body.shares as Array<Record<string, unknown>>) ?? []).map(
        (share) => ({ ...share, finalAmount: 640 }),
      ),
    });
  }),
  http.get("*/api/trips/42/slots/101", () =>
    HttpResponse.json(transportSlotDetail),
  ),
  http.post("*/api/trips/42/slots/101/candidates", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json(
      { ...transportSlotDetail.candidates[0], id: 503, ...body },
      { status: 201 },
    );
  }),
  http.put(
    "*/api/trips/42/candidates/:candidateId/vote",
    async ({ request }) => {
      const body = (await request.json()) as {
        choice: "YES" | "ANY" | "NO";
        reason?: string;
      };
      return HttpResponse.json({
        visibility: "ANONYMOUS",
        yesCount: body.choice === "YES" ? 1 : 0,
        anyCount: body.choice === "ANY" ? 1 : 0,
        noCount: body.choice === "NO" ? 1 : 0,
        unvotedMemberIds: [8, 9],
        myVote: {
          memberId: 7,
          choice: body.choice,
          reason: body.reason,
          version: 0,
        },
      });
    },
  ),
  http.put("*/api/trips/42/slots/101/adoption", async ({ request }) => {
    const body = (await request.json()) as { candidateId: number };
    return HttpResponse.json({
      slot: {
        ...transportSlotDetail.slot,
        status: "DECIDED",
        adoptedCandidateId: body.candidateId,
        version: 2,
      },
      planItem: {
        id: 301,
        slotId: 101,
        fromCandidateId: body.candidateId,
        title: "確定予定",
        startsAt: null,
        timezone: "Asia/Tokyo",
        placeRef: null,
        version: 0,
      },
    });
  }),
  http.post("*/api/invitations/accept", () =>
    HttpResponse.json(tokyoTripSnapshot, { status: 201 }),
  ),
  http.post("*/api/recoveries/accept", () =>
    HttpResponse.json(tokyoTripSnapshot.members[1]),
  ),
  http.patch("*/api/trips/42", async ({ request }) => {
    const body = (await request.json()) as { phaseOverride?: string | null };
    return HttpResponse.json({
      ...tokyoTripSnapshot.trip,
      phase: body.phaseOverride || tokyoTripSnapshot.trip.phase,
      phaseOverride: body.phaseOverride,
      version: tokyoTripSnapshot.trip.version + 1,
    });
  }),
  http.post("*/api/trips/42/invitations", () =>
    HttpResponse.json(
      {
        id: 91,
        url: "https://tabikime.app/join/test-invitation-token-1234",
        expiresAt: "2026-08-07T10:00:00Z",
      },
      { status: 201 },
    ),
  ),
];
