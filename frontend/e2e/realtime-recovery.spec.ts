import { expect, test } from "@playwright/test";
import { WebSocket, WebSocketServer } from "ws";

type Subscription = { socket: WebSocket; id: string };

test("2ブラウザが重複・欠落・再接続後にREST snapshotへ収束する", async ({
  browser,
}) => {
  const subscriptions: Subscription[] = [];
  const socketServer = new WebSocketServer({ host: "127.0.0.1", port: 5174 });
  socketServer.on("connection", (socket) => {
    socket.on("message", (raw) => {
      const frames = raw.toString().split("\0").filter(Boolean);
      for (const frame of frames) {
        if (frame.startsWith("CONNECT")) {
          socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
        }
        if (frame.startsWith("SUBSCRIBE")) {
          const id = /\nid:([^\n]+)/.exec(`\n${frame}`)?.[1] ?? "sub-0";
          subscriptions.push({ socket, id });
        }
      }
    });
  });

  const first = await browser.newContext();
  const second = await browser.newContext();
  const firstPage = await first.newPage();
  const secondPage = await second.newPage();
  for (const page of [firstPage, secondPage]) {
    await page.addInitScript(() => {
      const originalFetch = window.fetch.bind(window);
      let revision = 1;
      Object.defineProperty(window, "__setTripRevision", {
        value: (nextRevision: number) => {
          revision = nextRevision;
        },
      });
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url =
          typeof args[0] === "string" ? args[0] : (args[0] as Request).url;
        if (!url.endsWith("/api/trips/42") || !response.ok) return response;
        const body = await response.clone().json();
        body.trip.revision = revision;
        return new Response(JSON.stringify(body), {
          status: response.status,
          statusText: response.statusText,
          headers: response.headers,
        });
      };
    });
  }
  const snapshotRequests = new Map([
    [firstPage, 0],
    [secondPage, 0],
  ]);
  for (const page of [firstPage, secondPage]) {
    page.on("request", (request) => {
      if (request.url().endsWith("/api/trips/42")) {
        snapshotRequests.set(page, (snapshotRequests.get(page) ?? 0) + 1);
      }
    });
    await page.goto("/t/42");
    await expect(page.getByRole("heading", { name: "東京の旅" })).toBeVisible();
  }

  await expect.poll(() => subscriptions.length).toBe(2);
  expect(snapshotRequests.get(firstPage)).toBeGreaterThanOrEqual(2);
  expect(snapshotRequests.get(secondPage)).toBeGreaterThanOrEqual(2);

  function publish(eventId: string, tripRevision: number) {
    const body = JSON.stringify({
      eventId,
      tripId: 42,
      tripRevision,
      type: "CANDIDATE_UPDATED",
      resourceType: "candidate",
      resourceId: 501,
      resourceVersion: tripRevision,
      occurredAt: "2026-08-29T10:00:00Z",
    });
    for (const { socket, id } of subscriptions) {
      if (socket.readyState !== WebSocket.OPEN) continue;
      socket.send(
        `MESSAGE\nsubscription:${id}\ndestination:/topic/trip/42\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(body)}\n\n${body}\0`,
      );
    }
  }

  const beforeEvent = new Map(snapshotRequests);
  await Promise.all(
    [firstPage, secondPage].map((page) =>
      page.evaluate(() =>
        (
          window as typeof window & {
            __setTripRevision: (revision: number) => void;
          }
        ).__setTripRevision(2),
      ),
    ),
  );
  publish("550e8400-e29b-41d4-a716-446655440010", 2);
  await expect
    .poll(() => snapshotRequests.get(firstPage))
    .toBeGreaterThan(beforeEvent.get(firstPage) ?? 0);
  await expect
    .poll(() => snapshotRequests.get(secondPage))
    .toBeGreaterThan(beforeEvent.get(secondPage) ?? 0);
  await expect(firstPage.locator("[data-trip-revision]")).toHaveAttribute(
    "data-trip-revision",
    "2",
  );
  await expect(secondPage.locator("[data-trip-revision]")).toHaveAttribute(
    "data-trip-revision",
    "2",
  );

  const afterFirstEvent = new Map(snapshotRequests);
  publish("550e8400-e29b-41d4-a716-446655440010", 2);
  await firstPage.waitForTimeout(200);
  expect(snapshotRequests.get(firstPage)).toBe(afterFirstEvent.get(firstPage));
  expect(snapshotRequests.get(secondPage)).toBe(
    afterFirstEvent.get(secondPage),
  );

  await Promise.all(
    [firstPage, secondPage].map((page) =>
      page.evaluate(() =>
        (
          window as typeof window & {
            __setTripRevision: (revision: number) => void;
          }
        ).__setTripRevision(4),
      ),
    ),
  );
  publish("550e8400-e29b-41d4-a716-446655440011", 4);
  await expect
    .poll(() => snapshotRequests.get(firstPage))
    .toBeGreaterThan(afterFirstEvent.get(firstPage) ?? 0);
  await expect
    .poll(() => snapshotRequests.get(secondPage))
    .toBeGreaterThan(afterFirstEvent.get(secondPage) ?? 0);
  await expect(firstPage.locator("[data-trip-revision]")).toHaveAttribute(
    "data-trip-revision",
    "4",
  );
  await expect(secondPage.locator("[data-trip-revision]")).toHaveAttribute(
    "data-trip-revision",
    "4",
  );

  subscriptions.length = 0;
  const beforeReconnect = new Map(snapshotRequests);
  for (const socket of socketServer.clients) socket.close(1012, "restart");
  await expect.poll(() => subscriptions.length, { timeout: 10_000 }).toBe(2);
  await expect
    .poll(() => snapshotRequests.get(firstPage))
    .toBeGreaterThan(beforeReconnect.get(firstPage) ?? 0);
  await expect
    .poll(() => snapshotRequests.get(secondPage))
    .toBeGreaterThan(beforeReconnect.get(secondPage) ?? 0);

  await first.close();
  await second.close();
  await new Promise<void>((resolve) => socketServer.close(() => resolve()));
});
