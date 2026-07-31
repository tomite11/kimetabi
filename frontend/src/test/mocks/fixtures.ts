import type { components } from "../../api/generated/schema";

export const emptyTripPage = {
  items: [],
  nextCursor: null,
} satisfies components["schemas"]["TripPage"];
