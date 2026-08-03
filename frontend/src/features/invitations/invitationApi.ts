import { apiClient } from "../../api/client";
import { ApiError } from "../../api/ApiError";
import type { components } from "../../api/generated/schema";

export async function acceptInvitation(
  request: components["schemas"]["AcceptInvitationRequest"],
) {
  const { data, error, response } = await apiClient.POST(
    "/api/invitations/accept",
    { body: request },
  );

  if (error || !data) {
    throw new ApiError("招待を受け取れませんでした", response?.status);
  }

  return data;
}

export async function acceptRecovery(
  request: components["schemas"]["AcceptRecoveryRequest"],
) {
  const { data, error, response } = await apiClient.POST(
    "/api/recoveries/accept",
    { body: request },
  );

  if (error || !data) {
    throw new ApiError("メンバー情報を復旧できませんでした", response?.status);
  }

  return data;
}
