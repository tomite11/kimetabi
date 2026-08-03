import { z } from "zod";

export const slotSchema = z
  .object({
    category: z.enum(["LODGING", "TRANSPORT", "ACTIVITY", "MEAL", "OTHER"]),
    title: z.string().trim().min(1, "枠の名前を入力してください").max(100),
    dayFrom: z.coerce.number().int().min(1),
    dayTo: z.coerce.number().int().min(1),
    units: z.coerce.number().int().min(1),
    estPerPerson: z.union([z.coerce.number().int().min(0), z.literal("")]),
  })
  .refine((value) => value.dayTo >= value.dayFrom, {
    path: ["dayTo"],
    message: "終了日は開始日以降にしてください",
  });

export type SlotFormValues = z.infer<typeof slotSchema>;

export const candidateSchema = z
  .object({
    title: z.string().trim().max(200),
    url: z.union([
      z.string().trim().url("URLの形式を確認してください"),
      z.literal(""),
    ]),
    estAmount: z.union([z.coerce.number().int().min(0), z.literal("")]),
    estBasis: z.enum(["PER_PERSON", "TOTAL"]),
  })
  .refine((value) => value.title.length > 0 || value.url.length > 0, {
    path: ["title"],
    message: "名前またはURLを入力してください",
  });

export type CandidateFormValues = z.infer<typeof candidateSchema>;

export const candidateRequestSchema = z
  .object({
    title: z.string().trim().min(1).max(200).optional(),
    url: z
      .string()
      .url()
      .regex(/^https?:\/\//)
      .max(2048)
      .optional(),
    note: z.string().max(2000).optional(),
    tags: z.array(z.string().min(1).max(40)).max(20).optional(),
    estAmount: z.number().int().min(0).optional(),
    estBasis: z.enum(["PER_PERSON", "TOTAL"]).optional(),
  })
  .refine((value) => value.title != null || value.url != null);

export type CandidateRequestValues = z.infer<typeof candidateRequestSchema>;
