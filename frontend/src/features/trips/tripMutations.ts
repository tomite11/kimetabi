import { apiClient } from "../../api/client";
import { ApiError } from "../../api/ApiError";
import type { components } from "../../api/generated/schema";

function failure(message: string, status?: number) {
  return new ApiError(message, status);
}

export async function updateTrip(
  tripId: number,
  request: components["schemas"]["UpdateTripRequest"],
) {
  const { data, error, response } = await apiClient.PATCH(
    "/api/trips/{tripId}",
    { params: { path: { tripId } }, body: request },
  );
  if (error || !data)
    throw failure("フェーズを変更できませんでした", response?.status);
  return data;
}

export async function createInvitation(tripId: number) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/invitations",
    { params: { path: { tripId } } },
  );
  if (error || !data)
    throw failure("招待リンクを作れませんでした", response?.status);
  return data;
}

export async function createRecoveryLink(tripId: number, memberId: number) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/members/{memberId}/recovery-links",
    { params: { path: { tripId, memberId } } },
  );
  if (error || !data)
    throw failure("復旧リンクを作れませんでした", response?.status);
  return data;
}

export async function removeMember(
  tripId: number,
  memberId: number,
  version: number,
) {
  const { data, error, response } = await apiClient.DELETE(
    "/api/trips/{tripId}/members/{memberId}",
    {
      params: { path: { tripId, memberId } },
      body: { version },
    },
  );
  if (error || !data)
    throw failure("メンバーを削除できませんでした", response?.status);
  return data;
}

export async function transferOwner(
  tripId: number,
  memberId: number,
  version: number,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/owner-transfer",
    {
      params: { path: { tripId } },
      body: { memberId, version },
    },
  );
  if (error || !data)
    throw failure("OWNERを移譲できませんでした", response?.status);
  return data;
}
