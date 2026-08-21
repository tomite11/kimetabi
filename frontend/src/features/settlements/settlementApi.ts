import { queryOptions } from "@tanstack/react-query";

import { apiClient } from "../../api/client";
import type { components } from "../../api/generated/schema";

export type Settlement = components["schemas"]["Settlement"];
export type SettlementPage = components["schemas"]["SettlementPage"];
export type TransferStatus = components["schemas"]["TransferStatus"];

export class SettlementApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: components["schemas"]["Problem"] | undefined,
  ) {
    super(problem?.message || `精算を更新できませんでした（${status}）`);
  }
}

function failed(status: number, error: unknown): never {
  throw new SettlementApiError(
    status,
    error as components["schemas"]["Problem"] | undefined,
  );
}

export const settlementKeys = {
  all: (tripId: number) => ["trips", tripId, "settlements"] as const,
};

export const settlementsQuery = (tripId: number) =>
  queryOptions({
    queryKey: settlementKeys.all(tripId),
    queryFn: async () => {
      const { data, error, response } = await apiClient.GET(
        "/api/trips/{tripId}/settlements",
        { params: { path: { tripId }, query: { limit: 100 } } },
      );
      if (!data) failed(response.status, error);
      return data as SettlementPage;
    },
  });

export async function createSettlement(
  tripId: number,
  expectedTripRevision: number,
  idempotencyKey: string,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/settlements",
    {
      params: {
        path: { tripId },
        header: { "Idempotency-Key": idempotencyKey },
      },
      body: { expectedTripRevision },
    },
  );
  if (!data) failed(response.status, error);
  return data as Settlement;
}

export async function confirmSettlement(
  tripId: number,
  settlementId: number,
  version: number,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/settlements/{settlementId}/confirmation",
    {
      params: { path: { tripId, settlementId } },
      body: { version },
    },
  );
  if (!data) failed(response.status, error);
  return data as Settlement;
}

export async function updateTransfer(
  tripId: number,
  settlementId: number,
  transferId: number,
  status: "PAID" | "CONFIRMED",
  version: number,
) {
  const { data, error, response } = await apiClient.PATCH(
    "/api/trips/{tripId}/settlements/{settlementId}/transfers/{transferId}",
    {
      params: { path: { tripId, settlementId, transferId } },
      body: { status, version },
    },
  );
  if (!data) failed(response.status, error);
  return data as Settlement;
}
