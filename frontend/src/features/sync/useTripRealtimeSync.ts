import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";

import { apiBaseUrl, refreshAccessToken } from "../../api/client";
import { parseTripEvent, TripEventApplier } from "./tripEvent";

function websocketUrl() {
  const configured = import.meta.env.VITE_WEBSOCKET_URL;
  if (configured) return configured;
  const url = new URL(apiBaseUrl || globalThis.location.origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/ws";
  url.search = "";
  url.hash = "";
  return url.toString();
}

export function useTripRealtimeSync(
  tripId: number,
  revision: number,
  enabled = true,
) {
  const queryClient = useQueryClient();
  const applierRef = useRef<TripEventApplier | null>(null);
  const revisionRef = useRef(revision);
  revisionRef.current = revision;

  useEffect(() => {
    if (
      !enabled ||
      (import.meta.env.MODE === "test" &&
        import.meta.env.VITE_ENABLE_REALTIME !== "true") ||
      (import.meta.env.VITE_ENABLE_MSW === "true" &&
        import.meta.env.VITE_ENABLE_REALTIME !== "true")
    ) {
      return;
    }

    const applier = new TripEventApplier(
      queryClient,
      tripId,
      revisionRef.current,
    );
    applierRef.current = applier;
    let active = true;
    let subscription: StompSubscription | undefined;
    let eventChain = Promise.resolve();

    const client = new Client({
      brokerURL: websocketUrl(),
      reconnectDelay: 2_000,
      connectionTimeout: 10_000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      beforeConnect: async () => {
        const token = await refreshAccessToken();
        if (!token)
          throw new Error("WebSocket authentication token is unavailable");
        client.connectHeaders = { Authorization: `Bearer ${token}` };
      },
      onConnect: () => {
        eventChain = eventChain
          .then(async () => {
            await applier.synchronizeSnapshot();
            if (!active || !client.connected) return;
            subscription?.unsubscribe();
            subscription = client.subscribe(
              `/topic/trip/${tripId}`,
              (message: IMessage) => {
                const event = parseTripEvent(message.body);
                if (!event) return;
                eventChain = eventChain.then(() => applier.apply(event)).then();
              },
            );
          })
          .catch(() => {
            if (active) client.forceDisconnect();
          });
      },
    });

    client.activate();
    return () => {
      active = false;
      subscription?.unsubscribe();
      applierRef.current = null;
      void client.deactivate();
    };
  }, [enabled, queryClient, tripId]);

  useEffect(() => {
    applierRef.current?.setSnapshotRevision(revision);
  }, [revision]);
}
