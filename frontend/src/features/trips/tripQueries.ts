import { queryOptions } from "@tanstack/react-query";

import { apiClient } from "../../api/client";
import type { components } from "../../api/generated/schema";

export const tripKeys = {
  all: ["trips"] as const,
  list: () => [...tripKeys.all, "list"] as const,
  snapshot: (tripId: number) => [...tripKeys.all, tripId, "snapshot"] as const,
};

export const tripListQuery = queryOptions({
  queryKey: tripKeys.list(),
  queryFn: async () => {
    const { data, error, response } = await apiClient.GET("/api/trips");

    if (error || !data) {
      throw new Error(
        `旅行一覧を取得できませんでした（${response?.status || "network"}）`,
      );
    }

    return data;
  },
});

export const tripSnapshotQuery = (tripId: number) =>
  queryOptions({
    queryKey: tripKeys.snapshot(tripId),
    queryFn: async () => {
      const { data, error, response } = await apiClient.GET(
        "/api/trips/{tripId}",
        {
          params: { path: { tripId } },
        },
      );

      if (error || !data) {
        throw new Error(
          `旅行を取得できませんでした（${response?.status || "network"}）`,
        );
      }

      return data;
    },
  });

export type CreateTripCommand = {
  request: components["schemas"]["CreateTripRequest"];
  idempotencyKey: string;
};

export async function createTrip({
  request,
  idempotencyKey,
}: CreateTripCommand) {
  const { data, error, response } = await apiClient.POST("/api/trips", {
    params: {
      header: {
        "Idempotency-Key": idempotencyKey,
      },
    },
    body: request,
  });

  if (error || !data) {
    throw new Error(
      `旅行を作成できませんでした（${response?.status || "network"}）`,
    );
  }

  return data;
}
