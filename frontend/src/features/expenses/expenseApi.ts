import type { components } from "../../api/generated/schema";
import { ApiError } from "../../api/ApiError";
import { apiClient } from "../../api/client";

type CreateExpenseDraftRequest =
  components["schemas"]["CreateExpenseDraftRequest"];

export async function createAmountExpenseDraft(tripId: number, amount: number) {
  const request = {
    amount,
    paidAt: new Date().toISOString(),
    source: "MANUAL",
  } satisfies CreateExpenseDraftRequest;
  const { data, response } = await apiClient.POST(
    "/api/trips/{tripId}/expenses",
    {
      params: {
        path: { tripId },
        header: { "Idempotency-Key": crypto.randomUUID() },
      },
      body: request,
    },
  );
  if (!data) throw new ApiError("支出を記録できませんでした", response.status);
  return data;
}
