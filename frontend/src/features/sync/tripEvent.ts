import type { QueryClient } from "@tanstack/react-query";

import type { components } from "../../api/generated/schema";
import { tripKeys, tripSnapshotQuery } from "../trips/tripQueries";

export type TripEvent = components["schemas"]["TripEvent"];
export type TripSnapshot = components["schemas"]["TripSnapshot"];

const maxRememberedEventIds = 500;

export function parseTripEvent(body: string): TripEvent | undefined {
  try {
    const value: unknown = JSON.parse(body);
    if (!value || typeof value !== "object") return undefined;
    const event = value as Partial<TripEvent>;
    if (
      typeof event.eventId !== "string" ||
      typeof event.tripId !== "number" ||
      typeof event.tripRevision !== "number" ||
      typeof event.type !== "string" ||
      typeof event.resourceType !== "string" ||
      typeof event.resourceId !== "number" ||
      typeof event.occurredAt !== "string"
    ) {
      return undefined;
    }
    return event as TripEvent;
  } catch {
    return undefined;
  }
}

export class TripEventApplier {
  private readonly eventIds = new Set<string>();
  private latestRevision: number;

  constructor(
    private readonly queryClient: QueryClient,
    private readonly tripId: number,
    initialRevision: number,
  ) {
    this.latestRevision = initialRevision;
  }

  setSnapshotRevision(revision: number) {
    this.latestRevision = Math.max(this.latestRevision, revision);
  }

  async synchronizeSnapshot() {
    const snapshot = await this.queryClient.fetchQuery({
      ...tripSnapshotQuery(this.tripId),
      staleTime: 0,
    });
    this.latestRevision = snapshot.trip.revision;
    return snapshot;
  }

  async apply(event: TripEvent) {
    if (event.tripId !== this.tripId || this.eventIds.has(event.eventId)) {
      return "ignored" as const;
    }
    if (event.tripRevision <= this.latestRevision) {
      this.remember(event.eventId);
      return "ignored" as const;
    }

    const hasGap = event.tripRevision !== this.latestRevision + 1;
    await this.invalidateAffectedQueries(event);
    await this.synchronizeSnapshot();
    this.remember(event.eventId);
    return hasGap ? ("recovered" as const) : ("applied" as const);
  }

  private remember(eventId: string) {
    this.eventIds.add(eventId);
    if (this.eventIds.size <= maxRememberedEventIds) return;
    const oldest = this.eventIds.values().next().value;
    if (oldest) this.eventIds.delete(oldest);
  }

  private async invalidateAffectedQueries(event: TripEvent) {
    if (event.resourceType === "candidate" || event.resourceType === "slot") {
      await this.queryClient.invalidateQueries({
        queryKey: ["trips", this.tripId, "slots"],
        refetchType: "active",
      });
    }
    if (event.resourceType === "expense") {
      await this.queryClient.invalidateQueries({
        queryKey: ["trips", this.tripId, "expenses"],
        refetchType: "active",
      });
    }
    if (
      event.resourceType === "settlement" ||
      event.resourceType === "settlementTransfer"
    ) {
      await this.queryClient.invalidateQueries({
        queryKey: ["trips", this.tripId, "settlements"],
        refetchType: "active",
      });
    }
    await this.queryClient.invalidateQueries({
      queryKey: tripKeys.snapshot(this.tripId),
      refetchType: "none",
    });
  }
}
