import createClient from "openapi-fetch";

import type { paths } from "./generated/schema";

export type AccessTokenProvider = (
  forceRefresh?: boolean,
) => Promise<string | undefined>;

export const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL || globalThis.location?.origin || "";

let currentAccessTokenProvider: AccessTokenProvider | undefined;

export function setAccessTokenProvider(provider?: AccessTokenProvider) {
  currentAccessTokenProvider = provider;
}

export function createApiClient(
  accessTokenProvider?: AccessTokenProvider,
  baseUrl = apiBaseUrl,
) {
  const client = createClient<paths>({
    baseUrl,
    fetch: (...args) => globalThis.fetch(...args),
  });

  if (accessTokenProvider) {
    client.use({
      async onRequest({ request }) {
        const accessToken = await accessTokenProvider();
        if (accessToken) {
          request.headers.set("Authorization", `Bearer ${accessToken}`);
        }
        return request;
      },
    });
  }

  return client;
}

export const apiClient = createApiClient(async () =>
  currentAccessTokenProvider ? currentAccessTokenProvider() : undefined,
);

export async function refreshAccessToken() {
  return currentAccessTokenProvider?.(true);
}
