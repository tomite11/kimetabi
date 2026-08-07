import Dexie, { type EntityTable } from "dexie";

export type PendingOperationState =
  "PENDING" | "RETRYING" | "CONFLICT" | "NEEDS_CORRECTION";

export interface PendingOperation {
  id?: number;
  firebaseUid: string;
  tripId: number;
  method: "POST" | "PATCH" | "DELETE";
  path: string;
  payload: unknown;
  idempotencyKey: string;
  targetVersion?: number;
  createdAt: string;
  retryCount: number;
  state: PendingOperationState;
  operationType?:
    "CREATE_CANDIDATE" | "CREATE_EXPENSE_DRAFT" | "CONFIRM_EXPENSE";
  resourceId?: number;
  receiptBlobId?: string;
  lastProblem?: string;
}

export interface ReceiptBlob {
  id: string;
  firebaseUid: string;
  tripId: number;
  blob: Blob;
  contentType: "image/jpeg" | "image/png" | "image/webp";
  createdAt: string;
}

export class KimetabiDatabase extends Dexie {
  pendingOperations!: EntityTable<PendingOperation, "id">;
  receiptBlobs!: EntityTable<ReceiptBlob, "id">;

  constructor() {
    super("kimetabi");
    this.version(1).stores({
      pendingOperations:
        "++id, [firebaseUid+tripId], createdAt, state, idempotencyKey",
    });
    this.version(2).stores({
      pendingOperations:
        "++id, [firebaseUid+tripId], createdAt, state, idempotencyKey, operationType",
    });
    this.version(3).stores({
      pendingOperations:
        "++id, [firebaseUid+tripId], createdAt, state, idempotencyKey, operationType, receiptBlobId",
      receiptBlobs: "id, [firebaseUid+tripId], createdAt",
    });
  }
}

export const database = new KimetabiDatabase();
