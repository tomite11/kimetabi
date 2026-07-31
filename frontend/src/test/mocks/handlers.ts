import { http, HttpResponse } from "msw";

import { emptyTripPage } from "./fixtures";

export const handlers = [
  http.get("*/api/trips", () => HttpResponse.json(emptyTripPage)),
];
