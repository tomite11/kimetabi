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
}

export class KimetabiDatabase extends Dexie {
  pendingOperations!: EntityTable<PendingOperation, "id">;

  constructor() {
    super("kimetabi");
    this.version(1).stores({
      pendingOperations:
        "++id, [firebaseUid+tripId], createdAt, state, idempotencyKey",
    });
  }
}

export const database = new KimetabiDatabase();
