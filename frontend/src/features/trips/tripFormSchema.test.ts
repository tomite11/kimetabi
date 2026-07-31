import { describe, expect, it } from "vitest";

import { createTripSchema } from "./tripFormSchema";

const validTrip = {
  title: "瀬戸内の旅",
  destination: "香川県",
  startsOn: "2026-09-12",
  endsOn: "2026-09-14",
  timezone: "Asia/Tokyo",
  expectedMemberCount: 4,
  ownerName: "みなと",
};

describe("createTripSchema", () => {
  it("OpenAPIの旅行作成値を検証する", () => {
    expect(createTripSchema.parse(validTrip)).toEqual(validTrip);
  });

  it("帰着日が出発日より前なら拒否する", () => {
    expect(
      createTripSchema.safeParse({ ...validTrip, endsOn: "2026-09-11" })
        .success,
    ).toBe(false);
  });
});
