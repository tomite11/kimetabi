import createClient from "openapi-fetch";

import type { paths } from "./generated/schema";

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL || globalThis.location?.origin || "";

export const apiClient = createClient<paths>({
  baseUrl: apiBaseUrl,
  fetch: (...args) => globalThis.fetch(...args),
});
