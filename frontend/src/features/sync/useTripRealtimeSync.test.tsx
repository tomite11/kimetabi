import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, waitFor } from "@testing-library/react";
import type { PropsWithChildren } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { setAccessTokenProvider } from "../../api/client";
import { tripKeys } from "../trips/tripQueries";
import { tokyoTripSnapshot } from "../../test/mocks/fixtures";
import { useTripRealtimeSync } from "./useTripRealtimeSync";

const { clients, MockClient } = vi.hoisted(() => {
  const instances: Array<{
    connected: boolean;
    connectHeaders: Record<string, string>;
    subscribedDestination?: string;
    activate: () => Promise<void>;
  }> = [];
  class HoistedMockClient {
    connected = false;
    connectHeaders: Record<string, string> = {};
    subscribedDestination: string | undefined;
    private readonly configuration: Record<
      string,
      (...args: never[]) => unknown
    >;

    constructor(configuration: Record<string, (...args: never[]) => unknown>) {
      this.configuration = configuration;
      instances.push(this);
    }

    async activate() {
      await this.configuration.beforeConnect?.();
      this.connected = true;
      this.configuration.onConnect?.();
    }

    subscribe(destination: string) {
      this.subscribedDestination = destination;
      return { unsubscribe: vi.fn() };
    }

    async deactivate() {
      this.connected = false;
    }
  }
  return { clients: instances, MockClient: HoistedMockClient };
});

vi.mock("@stomp/stompjs", () => ({ Client: MockClient }));

function RealtimeHarness() {
  useTripRealtimeSync(42, 1);
  return null;
}

describe("useTripRealtimeSync", () => {
  afterEach(() => {
    clients.length = 0;
    setAccessTokenProvider();
    vi.unstubAllEnvs();
  });

  it("tokenを更新し、REST snapshot同期の完了後に旅行topicを購読する", async () => {
    vi.stubEnv("VITE_ENABLE_REALTIME", "true");
    const tokenProvider = vi.fn(async () => "fresh-token");
    setAccessTokenProvider(tokenProvider);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    queryClient.setQueryData(tripKeys.snapshot(42), tokyoTripSnapshot);
    const fetchQuery = vi
      .spyOn(queryClient, "fetchQuery")
      .mockResolvedValue(tokyoTripSnapshot);
    function Wrapper({ children }: PropsWithChildren) {
      return (
        <QueryClientProvider client={queryClient}>
          {children}
        </QueryClientProvider>
      );
    }

    const view = render(<RealtimeHarness />, { wrapper: Wrapper });
    await waitFor(() => expect(clients).toHaveLength(1));
    await waitFor(() =>
      expect(clients[0].subscribedDestination).toBe("/topic/trip/42"),
    );

    expect(tokenProvider).toHaveBeenCalledWith(true);
    expect(clients[0].connectHeaders).toEqual({
      Authorization: "Bearer fresh-token",
    });
    expect(fetchQuery).toHaveBeenCalledOnce();

    await act(async () => clients[0].activate());
    await waitFor(() => expect(tokenProvider).toHaveBeenCalledTimes(2));

    await act(async () => view.unmount());
    expect(clients[0].connected).toBe(false);
  });
});
