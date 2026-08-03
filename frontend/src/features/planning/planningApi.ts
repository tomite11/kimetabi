import { queryOptions } from "@tanstack/react-query";

import { apiClient } from "../../api/client";
import type { components } from "../../api/generated/schema";

export type TripSnapshot = components["schemas"]["TripSnapshot"];
export type Slot = components["schemas"]["Slot"];
export type SlotDetail = components["schemas"]["SlotDetail"];
export type Candidate = components["schemas"]["Candidate"];
export type CreateSlotRequest = components["schemas"]["CreateSlotRequest"];
export type CreateCandidateRequest =
  components["schemas"]["CreateCandidateRequest"];
export type VoteChoice = components["schemas"]["VoteChoice"];

export class PlanningApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: components["schemas"]["Problem"] | undefined,
  ) {
    super(problem?.message || `操作を完了できませんでした（${status}）`);
  }
}

function failed(status: number, error: unknown): never {
  throw new PlanningApiError(
    status,
    error as components["schemas"]["Problem"] | undefined,
  );
}

export const planningKeys = {
  slot: (tripId: number, slotId: number) =>
    ["trips", tripId, "slots", slotId] as const,
};

export const slotDetailQuery = (tripId: number, slotId: number) =>
  queryOptions({
    queryKey: planningKeys.slot(tripId, slotId),
    queryFn: async () => {
      const { data, error, response } = await apiClient.GET(
        "/api/trips/{tripId}/slots/{slotId}",
        { params: { path: { tripId, slotId } } },
      );
      if (!data) failed(response.status, error);
      return data as SlotDetail;
    },
  });

export async function createSlot(tripId: number, body: CreateSlotRequest) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/slots",
    { params: { path: { tripId } }, body },
  );
  if (!data) failed(response.status, error);
  return data as Slot;
}

export async function reorderSlots(
  tripId: number,
  snapshot: TripSnapshot,
  slots: Slot[],
) {
  const { data, error, response } = await apiClient.PUT(
    "/api/trips/{tripId}/slots/order",
    {
      params: { path: { tripId } },
      body: {
        tripVersion: snapshot.trip.version,
        items: slots.map((slot, sortOrder) => ({
          slotId: slot.id,
          version: slot.version,
          sortOrder,
        })),
      },
    },
  );
  if (!data) failed(response.status, error);
  return data;
}

export async function splitSlot(
  tripId: number,
  slot: Slot,
  splitAfterDay: number,
  secondTitle?: string,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/slots/{slotId}/split",
    {
      params: { path: { tripId, slotId: slot.id } },
      body: { version: slot.version, splitAfterDay, secondTitle },
    },
  );
  if (!data) failed(response.status, error);
  return data;
}

export async function createCandidate(
  tripId: number,
  slotId: number,
  idempotencyKey: string,
  body: CreateCandidateRequest,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/slots/{slotId}/candidates",
    {
      params: {
        path: { tripId, slotId },
        header: { "Idempotency-Key": idempotencyKey },
      },
      body,
    },
  );
  if (!data) failed(response.status, error);
  return data as Candidate;
}

export async function putVote(
  tripId: number,
  candidateId: number,
  choice: VoteChoice,
  reason: string | undefined,
  version: number | undefined,
) {
  const { data, error, response } = await apiClient.PUT(
    "/api/trips/{tripId}/candidates/{candidateId}/vote",
    {
      params: { path: { tripId, candidateId } },
      body: { choice, reason, version },
    },
  );
  if (!data) failed(response.status, error);
  return data;
}

export async function adoptCandidate(
  tripId: number,
  slot: Slot,
  candidateId: number,
) {
  const { data, error, response } = await apiClient.PUT(
    "/api/trips/{tripId}/slots/{slotId}/adoption",
    {
      params: { path: { tripId, slotId: slot.id } },
      body: { candidateId, version: slot.version },
    },
  );
  if (!data) failed(response.status, error);
  return data;
}
