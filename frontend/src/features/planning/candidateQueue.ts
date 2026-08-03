import type { components } from "../../api/generated/schema";
import {
  database,
  type PendingOperation,
  type PendingOperationState,
} from "../../offline/database";
import { createCandidate, PlanningApiError } from "./planningApi";
import {
  candidateRequestSchema,
  type CandidateRequestValues,
} from "./planningSchema";

export type CreateCandidateRequest =
  components["schemas"]["CreateCandidateRequest"];

export type QueuedCandidate = PendingOperation & {
  id: number;
  operationType: "CREATE_CANDIDATE";
  resourceId: number;
  payload: CandidateRequestValues;
};

const activeScopes = new Set<string>();
const requestedScopes = new Set<string>();

function scopeKey(firebaseUid: string, tripId: number) {
  return `${firebaseUid}:${tripId}`;
}

export async function enqueueCandidate(
  firebaseUid: string,
  tripId: number,
  slotId: number,
  payload: CreateCandidateRequest,
  idempotencyKey = crypto.randomUUID(),
) {
  return database.pendingOperations.add({
    firebaseUid,
    tripId,
    method: "POST",
    path: `/api/trips/${tripId}/slots/${slotId}/candidates`,
    payload,
    idempotencyKey,
    resourceId: slotId,
    operationType: "CREATE_CANDIDATE",
    createdAt: new Date().toISOString(),
    retryCount: 0,
    state: "PENDING",
  });
}

export async function listQueuedCandidates(
  firebaseUid: string,
  tripId: number,
  slotId: number,
): Promise<QueuedCandidate[]> {
  const operations = await database.pendingOperations
    .where("[firebaseUid+tripId]")
    .equals([firebaseUid, tripId])
    .sortBy("createdAt");
  return operations.flatMap((operation) => {
    const payload = candidateRequestSchema.safeParse(operation.payload);
    return operation.id != null &&
      operation.operationType === "CREATE_CANDIDATE" &&
      operation.resourceId === slotId &&
      payload.success
      ? [{ ...operation, payload: payload.data } as QueuedCandidate]
      : [];
  });
}

function failureState(error: unknown): PendingOperationState {
  if (!(error instanceof PlanningApiError)) return "PENDING";
  if (error.status === 409) return "CONFLICT";
  if (error.status >= 400 && error.status < 500 && error.status !== 401)
    return "NEEDS_CORRECTION";
  return "PENDING";
}

export async function flushCandidateQueue(
  firebaseUid: string,
  tripId: number,
  onSuccess: (candidate: components["schemas"]["Candidate"]) => void,
) {
  const scope = scopeKey(firebaseUid, tripId);
  if (activeScopes.has(scope)) {
    requestedScopes.add(scope);
    return;
  }
  activeScopes.add(scope);
  try {
    const operations = await database.pendingOperations
      .where("[firebaseUid+tripId]")
      .equals([firebaseUid, tripId])
      .sortBy("createdAt");
    for (const operation of operations) {
      if (
        operation.id == null ||
        operation.operationType !== "CREATE_CANDIDATE" ||
        operation.resourceId == null ||
        operation.state === "CONFLICT" ||
        operation.state === "NEEDS_CORRECTION"
      )
        continue;
      await database.pendingOperations.update(operation.id, {
        state: "RETRYING",
        retryCount: operation.retryCount + 1,
      });
      try {
        const payload = candidateRequestSchema.safeParse(operation.payload);
        if (!payload.success) {
          await database.pendingOperations.update(operation.id, {
            state: "NEEDS_CORRECTION",
            lastProblem: "保存した候補入力を確認してください",
          });
          continue;
        }
        let candidate;
        try {
          candidate = await createCandidate(
            tripId,
            operation.resourceId,
            operation.idempotencyKey,
            payload.data satisfies CreateCandidateRequest,
          );
        } catch (error) {
          if (!(error instanceof PlanningApiError) || error.status !== 401)
            throw error;
          candidate = await createCandidate(
            tripId,
            operation.resourceId,
            operation.idempotencyKey,
            payload.data satisfies CreateCandidateRequest,
          );
        }
        onSuccess(candidate);
        await database.pendingOperations.delete(operation.id);
      } catch (error) {
        const state = failureState(error);
        await database.pendingOperations.update(operation.id, {
          state,
          lastProblem:
            error instanceof PlanningApiError ? error.message : "通信待ち",
        });
        if (state === "PENDING") break;
      }
    }
  } finally {
    activeScopes.delete(scope);
    if (requestedScopes.delete(scope)) {
      await flushCandidateQueue(firebaseUid, tripId, onSuccess);
    }
  }
}
