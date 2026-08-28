import { QueryClient } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import { server } from "../../test/mocks/server";
import { tokyoTripSnapshot } from "../../test/mocks/fixtures";
import { TripEventApplier, parseTripEvent, type TripEvent } from "./tripEvent";

function event(
  eventId: string,
  tripRevision: number,
  resourceType: TripEvent["resourceType"] = "candidate",
): TripEvent {
  return {
    eventId,
    tripId: 42,
    tripRevision,
    type: "CANDIDATE_UPDATED",
    resourceType,
    resourceId: 501,
    resourceVersion: 2,
    occurredAt: "2026-08-29T10:00:00Z",
  };
}

describe("TripEventApplier", () => {
  it("OpenAPIのTripEventを検証して解釈する", () => {
    const value = event("550e8400-e29b-41d4-a716-446655440000", 2);
    expect(parseTripEvent(JSON.stringify(value))).toEqual(value);
    expect(parseTripEvent('{"eventId":1}')).toBeUndefined();
    expect(parseTripEvent("not-json")).toBeUndefined();
  });

  it("同じeventIdを一度だけ適用し、対象Queryとsnapshotを更新する", async () => {
    let requestCount = 0;
    server.use(
      http.get("*/api/trips/42", () => {
        requestCount += 1;
        return HttpResponse.json({
          ...tokyoTripSnapshot,
          trip: { ...tokyoTripSnapshot.trip, revision: 2 },
        } satisfies components["schemas"]["TripSnapshot"]);
      }),
    );
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const applier = new TripEventApplier(queryClient, 42, 1);
    const received = event("550e8400-e29b-41d4-a716-446655440000", 2);

    await expect(applier.apply(received)).resolves.toBe("applied");
    await expect(applier.apply(received)).resolves.toBe("ignored");

    expect(requestCount).toBe(1);
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ["trips", 42, "slots"],
      refetchType: "active",
    });
  });

  it("欠落をREST snapshotで回復し、逆順の古いeventを無視する", async () => {
    let requestCount = 0;
    server.use(
      http.get("*/api/trips/42", () => {
        requestCount += 1;
        return HttpResponse.json({
          ...tokyoTripSnapshot,
          trip: { ...tokyoTripSnapshot.trip, revision: 4 },
        } satisfies components["schemas"]["TripSnapshot"]);
      }),
    );
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const applier = new TripEventApplier(queryClient, 42, 1);

    await expect(
      applier.apply(event("550e8400-e29b-41d4-a716-446655440001", 4)),
    ).resolves.toBe("recovered");
    await expect(
      applier.apply(event("550e8400-e29b-41d4-a716-446655440002", 3)),
    ).resolves.toBe("ignored");
    expect(requestCount).toBe(1);
  });

  it("snapshot同期失敗時はeventIdを確定せず再配送で回復できる", async () => {
    let requestCount = 0;
    server.use(
      http.get("*/api/trips/42", () => {
        requestCount += 1;
        if (requestCount === 1) {
          return HttpResponse.json({}, { status: 503 });
        }
        return HttpResponse.json({
          ...tokyoTripSnapshot,
          trip: { ...tokyoTripSnapshot.trip, revision: 2 },
        } satisfies components["schemas"]["TripSnapshot"]);
      }),
    );
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const applier = new TripEventApplier(queryClient, 42, 1);
    const received = event("550e8400-e29b-41d4-a716-446655440003", 2);

    await expect(applier.apply(received)).rejects.toThrow();
    await expect(applier.apply(received)).resolves.toBe("applied");
    expect(requestCount).toBe(2);
  });
});
