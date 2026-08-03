import { http, HttpResponse } from "msw";

import { emptyTripPage, tokyoTripSnapshot } from "./fixtures";

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
