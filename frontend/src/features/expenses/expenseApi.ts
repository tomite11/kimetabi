import { queryOptions } from "@tanstack/react-query";

import { apiClient } from "../../api/client";
import type { components } from "../../api/generated/schema";

type CreateExpenseDraftRequest =
  components["schemas"]["CreateExpenseDraftRequest"];
export type Expense = components["schemas"]["Expense"];
export type ExpensePage = components["schemas"]["ExpensePage"];
export type ExpenseSharePreset = components["schemas"]["ExpenseSharePreset"];
export type UpdateExpenseRequest =
  components["schemas"]["UpdateExpenseRequest"];

export class ExpenseApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: components["schemas"]["Problem"] | undefined,
  ) {
    super(problem?.message || `支出を更新できませんでした（${status}）`);
  }
}

function failed(status: number, error: unknown): never {
  throw new ExpenseApiError(
    status,
    error as components["schemas"]["Problem"] | undefined,
  );
}

export const expenseKeys = {
  all: (tripId: number) => ["trips", tripId, "expenses"] as const,
};

export const expensesQuery = (tripId: number) =>
  queryOptions({
    queryKey: expenseKeys.all(tripId),
    queryFn: async () => {
      const { data, error, response } = await apiClient.GET(
        "/api/trips/{tripId}/expenses",
        { params: { path: { tripId }, query: { limit: 100 } } },
      );
      if (!data) failed(response.status, error);
      return data as ExpensePage;
    },
  });

export async function createExpenseDraft(
  tripId: number,
  idempotencyKey: string,
  request: CreateExpenseDraftRequest,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/expenses",
    {
      params: {
        path: { tripId },
        header: { "Idempotency-Key": idempotencyKey },
      },
      body: request,
    },
  );
  if (!data) failed(response.status, error);
  return data as Expense;
}

export async function getExpense(tripId: number, expenseId: number) {
  const { data, error, response } = await apiClient.GET(
    "/api/trips/{tripId}/expenses/{expenseId}",
    { params: { path: { tripId, expenseId } } },
  );
  if (!data) failed(response.status, error);
  return data as Expense;
}

export async function prepareReceiptUpload(
  tripId: number,
  expense: Expense,
  blob: Blob,
) {
  const contentType = blob.type as "image/jpeg" | "image/png" | "image/webp";
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/expenses/{expenseId}/receipt-upload",
    {
      params: { path: { tripId, expenseId: expense.id } },
      body: { contentType, byteSize: blob.size, version: expense.version },
    },
  );
  if (!data) failed(response.status, error);
  return data;
}

export async function uploadReceiptBlob(
  uploadUrl: string,
  requiredHeaders: Record<string, string>,
  blob: Blob,
) {
  const response = await fetch(uploadUrl, {
    method: "PUT",
    headers: requiredHeaders,
    body: blob,
  });
  if (!response.ok) throw new ExpenseApiError(response.status, undefined);
}

export async function completeReceiptUpload(
  tripId: number,
  expenseId: number,
  receiptId: string,
  version: number,
) {
  const { data, error, response } = await apiClient.POST(
    "/api/trips/{tripId}/expenses/{expenseId}/receipts/{receiptId}/completion",
    {
      params: { path: { tripId, expenseId, receiptId } },
      body: { version },
    },
  );
  if (!data) failed(response.status, error);
  return data as Expense;
}

export async function getPreviousSharePreset(tripId: number) {
  const { data, error, response } = await apiClient.GET(
    "/api/trips/{tripId}/expenses/share-preset",
    { params: { path: { tripId } } },
  );
  if (response.status === 204) return undefined;
  if (!data) failed(response.status, error);
  return data as ExpenseSharePreset;
}

export async function confirmExpense(
  tripId: number,
  expenseId: number,
  body: UpdateExpenseRequest,
) {
  const { data, error, response } = await apiClient.PATCH(
    "/api/trips/{tripId}/expenses/{expenseId}",
    { params: { path: { tripId, expenseId } }, body },
  );
  if (!data) failed(response.status, error);
  return data as Expense;
}
