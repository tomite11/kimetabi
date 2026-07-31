import { afterEach, describe, expect, it } from "vitest";

import { KimetabiDatabase } from "./database";

const databases: KimetabiDatabase[] = [];

afterEach(async () => {
  await Promise.all(
    databases.splice(0).map(async (database) => {
      database.close();
      await database.delete();
    }),
  );
});

describe("KimetabiDatabase", () => {
  it("未送信操作をユーザーと旅行に紐づけて保持する", async () => {
    const database = new KimetabiDatabase();
    databases.push(database);

    await database.pendingOperations.add({
      firebaseUid: "firebase-user-1",
      tripId: 42,
      method: "POST",
      path: "/api/trips/42/slots/7/candidates",
      payload: { url: "https://example.com/hotel" },
      idempotencyKey: "operation-1",
      targetVersion: 3,
      createdAt: "2026-07-31T06:00:00.000Z",
      retryCount: 0,
      state: "PENDING",
    });

    const operations = await database.pendingOperations
      .where("[firebaseUid+tripId]")
      .equals(["firebase-user-1", 42])
      .toArray();

    expect(operations).toHaveLength(1);
    expect(operations[0]).toMatchObject({
      idempotencyKey: "operation-1",
      targetVersion: 3,
      state: "PENDING",
    });
  });
});
