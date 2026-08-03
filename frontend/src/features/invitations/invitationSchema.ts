import { z } from "zod";

export const displayNameSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "表示名を入力してください")
    .max(100, "表示名は100文字以内で入力してください"),
});

export type DisplayNameValues = z.infer<typeof displayNameSchema>;
