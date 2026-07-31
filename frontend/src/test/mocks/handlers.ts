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
];
