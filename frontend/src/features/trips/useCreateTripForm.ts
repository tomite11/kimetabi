import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";

import { createTripSchema, type CreateTripFormValues } from "./tripFormSchema";

export function useCreateTripForm() {
  return useForm<CreateTripFormValues>({
    resolver: zodResolver(createTripSchema),
    defaultValues: {
      title: "新しい旅行",
      destination: "",
      startsOn: "",
      endsOn: "",
      timezone: "Asia/Tokyo",
      expectedMemberCount: 2,
      ownerName: "わたし",
      voteVisibility: "ANONYMOUS",
    },
    mode: "onBlur",
  });
}
