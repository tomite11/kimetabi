import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "../test/mocks/server";
import { createApiClient } from "./client";

describe("createApiClient", () => {
  it("生成済み契約でBearer tokenを付けて認証済みsessionへ疎通する", async () => {
    const accessTokenProvider = vi.fn().mockResolvedValue("valid-token");

    server.use(
      http.get("http://api.example.test/api/session", ({ request }) => {
        if (request.headers.get("Authorization") !== "Bearer valid-token") {
          return HttpResponse.json(
            { title: "Unauthorized", status: 401, code: "UNAUTHENTICATED" },
            { status: 401 },
          );
        }
        return HttpResponse.json({ firebaseUid: "fixture-user" });
      }),
    );

    const client = createApiClient(
      accessTokenProvider,
      "http://api.example.test",
    );
    const { data, error, response } = await client.GET("/api/session");

    expect(response.status).toBe(200);
    expect(error).toBeUndefined();
    expect(data).toEqual({ firebaseUid: "fixture-user" });
    expect(accessTokenProvider).toHaveBeenCalledOnce();
  });

  it("tokenがない場合はAuthorization headerを送らない", async () => {
    server.use(
      http.get("http://api.example.test/api/session", ({ request }) => {
        expect(request.headers.has("Authorization")).toBe(false);
        return HttpResponse.json(
          { title: "Unauthorized", status: 401, code: "UNAUTHENTICATED" },
          { status: 401 },
        );
      }),
    );

    const client = createApiClient(
      async () => undefined,
      "http://api.example.test",
    );
    const { error, response } = await client.GET("/api/session");

    expect(response.status).toBe(401);
    expect(error?.code).toBe("UNAUTHENTICATED");
  });
});
