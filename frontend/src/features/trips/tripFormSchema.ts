import { z } from "zod";

import type { components } from "../../api/generated/schema";

export const createTripSchema = z
  .object({
    title: z.string().trim().min(1, "旅行名を入力してください"),
    destination: z.string().trim().min(1, "行き先を入力してください"),
    startsOn: z.string().date("出発日を入力してください"),
    endsOn: z.string().date("帰着日を入力してください"),
    timezone: z.string().min(1),
    expectedMemberCount: z.number().int().min(1).max(100),
    ownerName: z.string().trim().min(1, "名前を入力してください"),
    budgetCap: z.number().int().positive().optional(),
    voteVisibility: z.enum(["NAMED", "ANONYMOUS"]).optional(),
  })
  .refine((value) => value.startsOn <= value.endsOn, {
    message: "帰着日は出発日以降にしてください",
    path: ["endsOn"],
  }) satisfies z.ZodType<components["schemas"]["CreateTripRequest"]>;

export type CreateTripFormValues = z.infer<typeof createTripSchema>;
