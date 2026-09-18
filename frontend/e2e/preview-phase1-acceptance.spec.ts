import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
} from "@playwright/test";
import WebSocket from "ws";

type Identity = { idToken: string };
type TraceEvidence = { check: string; status: number; traceId: string };

type TripSnapshot = {
  trip: {
    id: number;
    timezone: string;
    expectedMemberCount: number;
    phase: string;
    phaseOverride?: string | null;
    revision: number;
    version: number;
  };
  currentMemberId: number;
  members: Array<{ id: number; role: string; status: string }>;
  slots: Array<{
    id: number;
    version: number;
    adoptedCandidateId?: number | null;
  }>;
  planItems: Array<{ id: number; fromCandidateId?: number | null }>;
};

type Candidate = {
  id: number;
  estAmount?: number | null;
  metadataStatus: string;
  version: number;
};

type Expense = {
  id: number;
  status: string;
  amount?: number | null;
  version: number;
  shares: Array<{ memberId: number; finalAmount?: number | null }>;
};

type Settlement = {
  id: number;
  status: string;
  expenseTotal: number;
  hasUnappliedChanges: boolean;
  version: number;
  transfers: Array<{
    id: number;
    fromMemberId: number;
    toMemberId: number;
    amount: number;
    status: string;
    version: number;
  }>;
};

const previewUrl = process.env.E2E_PREVIEW_URL;
const apiUrl = process.env.E2E_PREVIEW_API_URL;
const firebaseApiKey = process.env.E2E_PREVIEW_FIREBASE_API_KEY;

test.describe.configure({ mode: "serial" });

test("同一Previewでフェーズ1の業務・認可・整合性を一周する", async ({
  request,
}, testInfo) => {
  test.setTimeout(360_000);
  test.skip(
    !previewUrl || !apiUrl || !firebaseApiKey,
    "Preview URL、API URL、Firebase API keyが必要です",
  );

  const evidence: TraceEvidence[] = [];
  const owner = await createAnonymousIdentity(request, firebaseApiKey!);
  const member = await createAnonymousIdentity(request, firebaseApiKey!);
  const outsider = await createAnonymousIdentity(request, firebaseApiKey!);

  const createTrip = await api<TripSnapshot>(request, evidence, owner, {
    check: "trip.create",
    method: "POST",
    path: "/api/trips",
    expectedStatus: 201,
    idempotencyKey: crypto.randomUUID(),
    data: {
      title: "Phase 5 受け入れ旅行",
      destination: "東京",
      startsOn: "2026-09-20",
      endsOn: "2026-09-22",
      timezone: "Asia/Tokyo",
      expectedMemberCount: 3,
      ownerName: "Phase5 Owner",
    },
  });
  const tripId = createTrip.trip.id;
  expect(createTrip.slots).toHaveLength(3);
  expect(createTrip.trip.timezone).toBe("Asia/Tokyo");
  expect(createTrip.trip.expectedMemberCount).toBe(3);

  const invitation = await api<{ url: string }>(request, evidence, owner, {
    check: "invitation.create",
    method: "POST",
    path: `/api/trips/${tripId}/invitations`,
    expectedStatus: 201,
  });
  const invitationToken = new URL(invitation.url, previewUrl).pathname
    .split("/")
    .at(-1);
  expect(invitationToken).toBeTruthy();
  const joined = await api<TripSnapshot>(request, evidence, member, {
    check: "invitation.accept",
    method: "POST",
    path: "/api/invitations/accept",
    expectedStatus: 201,
    data: { token: invitationToken, name: "Phase5 Member" },
  });
  expect(
    joined.members.filter((entry) => entry.status === "ACTIVE"),
  ).toHaveLength(2);

  await api(request, evidence, outsider, {
    check: "rest.non_member_denied",
    method: "GET",
    path: `/api/trips/${tripId}`,
    expectedStatus: 404,
  });
  const webSocketEvidence = await verifyWebSocketAuthorization(
    owner,
    outsider,
    tripId,
  );

  const slot = createTrip.slots[0];
  const urlKey = crypto.randomUUID();
  const startedAt = performance.now();
  const urlCandidate = await api<Candidate>(request, evidence, owner, {
    check: "candidate.url_async",
    method: "POST",
    path: `/api/trips/${tripId}/slots/${slot.id}/candidates`,
    expectedStatus: 201,
    idempotencyKey: urlKey,
    data: { url: "https://example.com/phase-5-preview" },
  });
  expect(performance.now() - startedAt).toBeLessThan(3_000);
  expect(urlCandidate.metadataStatus).toBe("PENDING");
  const replayedCandidate = await api<Candidate>(request, evidence, owner, {
    check: "candidate.idempotent_replay",
    method: "POST",
    path: `/api/trips/${tripId}/slots/${slot.id}/candidates`,
    expectedStatus: 201,
    idempotencyKey: urlKey,
    data: { url: "https://example.com/phase-5-preview" },
  });
  expect(replayedCandidate.id).toBe(urlCandidate.id);
  const metadataCandidate = await waitForMetadataTerminalState(
    request,
    evidence,
    owner,
    tripId,
    slot.id,
    urlCandidate.id,
  );
  expect(["COMPLETED", "FAILED_RETRYABLE", "FAILED_PERMANENT"]).toContain(
    metadataCandidate.metadataStatus,
  );

  const manualCandidate = await api<Candidate>(request, evidence, owner, {
    check: "candidate.manual",
    method: "POST",
    path: `/api/trips/${tripId}/slots/${slot.id}/candidates`,
    expectedStatus: 201,
    idempotencyKey: crypto.randomUUID(),
    data: { title: "Phase 5 候補", estAmount: 12_000, estBasis: "PER_PERSON" },
  });
  expect(manualCandidate.estAmount).toBe(12_000);
  await api(request, evidence, member, {
    check: "vote.member",
    method: "PUT",
    path: `/api/trips/${tripId}/candidates/${manualCandidate.id}/vote`,
    expectedStatus: 200,
    data: { choice: "YES" },
  });
  await api(request, evidence, member, {
    check: "adoption.member_denied",
    method: "PUT",
    path: `/api/trips/${tripId}/slots/${slot.id}/adoption`,
    expectedStatus: 403,
    data: { candidateId: manualCandidate.id, version: slot.version },
  });
  const adoption = await api<{
    slot: { version: number; adoptedCandidateId: number };
    planItem: { fromCandidateId: number };
  }>(request, evidence, owner, {
    check: "adoption.owner",
    method: "PUT",
    path: `/api/trips/${tripId}/slots/${slot.id}/adoption`,
    expectedStatus: 200,
    data: { candidateId: manualCandidate.id, version: slot.version },
  });
  expect(adoption.slot.adoptedCandidateId).toBe(manualCandidate.id);
  expect(adoption.planItem.fromCandidateId).toBe(manualCandidate.id);
  await api(request, evidence, owner, {
    check: "adoption.stale_version",
    method: "PUT",
    path: `/api/trips/${tripId}/slots/${slot.id}/adoption`,
    expectedStatus: 409,
    data: { candidateId: urlCandidate.id, version: slot.version },
  });

  const draftKey = crypto.randomUUID();
  const draft = await api<Expense>(request, evidence, owner, {
    check: "expense.photo_draft",
    method: "POST",
    path: `/api/trips/${tripId}/expenses`,
    expectedStatus: 201,
    idempotencyKey: draftKey,
    data: { hasReceipt: true },
  });
  const replayedDraft = await api<Expense>(request, evidence, owner, {
    check: "expense.offline_replay",
    method: "POST",
    path: `/api/trips/${tripId}/expenses`,
    expectedStatus: 201,
    idempotencyKey: draftKey,
    data: { hasReceipt: true },
  });
  expect(replayedDraft.id).toBe(draft.id);
  await api(request, evidence, owner, {
    check: "expense.incomplete_rejected",
    method: "PATCH",
    path: `/api/trips/${tripId}/expenses/${draft.id}`,
    expectedStatus: 422,
    data: { version: draft.version, status: "CONFIRMED" },
  });

  const confirmed = await api<Expense>(request, evidence, owner, {
    check: "expense.confirm_and_round",
    method: "PATCH",
    path: `/api/trips/${tripId}/expenses/${draft.id}`,
    expectedStatus: 200,
    data: {
      version: draft.version,
      payerId: createTrip.currentMemberId,
      amount: 101,
      paidAt: "2026-09-20T12:00:00+09:00",
      allocationType: "EQUAL",
      shares: [
        { memberId: createTrip.currentMemberId },
        { memberId: joined.currentMemberId },
      ],
      status: "CONFIRMED",
    },
  });
  expect(
    confirmed.shares.reduce((sum, share) => sum + (share.finalAmount ?? 0), 0),
  ).toBe(101);
  expect(confirmed.shares.map((share) => share.finalAmount).sort()).toEqual([
    50, 51,
  ]);
  await api(request, evidence, owner, {
    check: "expense.stale_version",
    method: "PATCH",
    path: `/api/trips/${tripId}/expenses/${draft.id}`,
    expectedStatus: 409,
    data: { version: draft.version, amount: 102 },
  });

  let snapshot = await api<TripSnapshot>(request, evidence, owner, {
    check: "snapshot.before_settlement",
    method: "GET",
    path: `/api/trips/${tripId}`,
    expectedStatus: 200,
  });
  const settlement = await api<Settlement>(request, evidence, owner, {
    check: "settlement.create",
    method: "POST",
    path: `/api/trips/${tripId}/settlements`,
    expectedStatus: 201,
    idempotencyKey: crypto.randomUUID(),
    data: { expectedTripRevision: snapshot.trip.revision },
  });
  expect(settlement.transfers.length).toBeLessThanOrEqual(1);
  expect(
    settlement.transfers.reduce((sum, transfer) => sum + transfer.amount, 0),
  ).toBe(50);
  const settled = await api<Settlement>(request, evidence, owner, {
    check: "settlement.confirm",
    method: "POST",
    path: `/api/trips/${tripId}/settlements/${settlement.id}/confirmation`,
    expectedStatus: 200,
    data: { version: settlement.version },
  });
  expect(settled.status).toBe("CONFIRMED");

  const laterDraft = await api<Expense>(request, evidence, owner, {
    check: "expense.after_settlement",
    method: "POST",
    path: `/api/trips/${tripId}/expenses`,
    expectedStatus: 201,
    idempotencyKey: crypto.randomUUID(),
    data: { amount: 40, source: "MANUAL" },
  });
  await api(request, evidence, owner, {
    check: "expense.after_settlement_confirm",
    method: "PATCH",
    path: `/api/trips/${tripId}/expenses/${laterDraft.id}`,
    expectedStatus: 200,
    data: {
      version: laterDraft.version,
      payerId: createTrip.currentMemberId,
      paidAt: "2026-09-21T12:00:00+09:00",
      allocationType: "EQUAL",
      shares: [
        { memberId: createTrip.currentMemberId },
        { memberId: joined.currentMemberId },
      ],
      status: "CONFIRMED",
    },
  });
  const unchangedSettlement = await api<Settlement>(request, evidence, owner, {
    check: "settlement.unapplied_changes",
    method: "GET",
    path: `/api/trips/${tripId}/settlements/${settlement.id}`,
    expectedStatus: 200,
  });
  expect(unchangedSettlement.expenseTotal).toBe(101);
  expect(unchangedSettlement.hasUnappliedChanges).toBe(true);

  snapshot = await api<TripSnapshot>(request, evidence, owner, {
    check: "snapshot.before_recalculation",
    method: "GET",
    path: `/api/trips/${tripId}`,
    expectedStatus: 200,
  });
  const recalculated = await api<Settlement>(request, evidence, owner, {
    check: "settlement.recalculate",
    method: "POST",
    path: `/api/trips/${tripId}/settlements`,
    expectedStatus: 201,
    idempotencyKey: crypto.randomUUID(),
    data: { expectedTripRevision: snapshot.trip.revision },
  });
  expect(recalculated.id).not.toBe(settlement.id);
  expect(recalculated.expenseTotal).toBe(141);

  const phaseChanged = await api<TripSnapshot["trip"]>(
    request,
    evidence,
    owner,
    {
      check: "trip.phase_override",
      method: "PATCH",
      path: `/api/trips/${tripId}`,
      expectedStatus: 200,
      data: { version: snapshot.trip.version, phaseOverride: "TRAVELING" },
    },
  );
  expect(phaseChanged.timezone).toBe("Asia/Tokyo");
  expect(phaseChanged.phase).toBe("TRAVELING");
  expect(phaseChanged.phaseOverride).toBe("TRAVELING");
  await api(request, evidence, owner, {
    check: "trip.stale_version",
    method: "PATCH",
    path: `/api/trips/${tripId}`,
    expectedStatus: 409,
    data: { version: snapshot.trip.version, phaseOverride: "SETTLING" },
  });

  const recoverySocket = await connectStomp(owner.idToken);
  recoverySocket.close();
  const recoveredSnapshot = await api<TripSnapshot>(request, evidence, owner, {
    check: "revision.recovery_snapshot",
    method: "GET",
    path: `/api/trips/${tripId}`,
    expectedStatus: 200,
  });
  expect(recoveredSnapshot.trip.revision).toBeGreaterThan(
    createTrip.trip.revision,
  );

  await testInfo.attach("phase5-preview-evidence.json", {
    body: Buffer.from(
      JSON.stringify({ tripId, webSocketEvidence, evidence }, null, 2),
    ),
    contentType: "application/json",
  });
  console.log(
    JSON.stringify({
      phase5PreviewTripId: tripId,
      webSocketEvidence,
      evidence,
    }),
  );
});

async function verifyWebSocketAuthorization(
  member: Identity,
  outsider: Identity,
  tripId: number,
) {
  const memberSocket = await connectStomp(member.idToken);
  const outsiderSocket = await connectStomp(outsider.idToken);
  try {
    memberSocket.send(
      `SUBSCRIBE\nid:member-sub\ndestination:/topic/trip/${tripId}\nack:auto\n\n\0`,
    );
    outsiderSocket.send(
      `SUBSCRIBE\nid:outsider-sub\ndestination:/topic/trip/${tripId}\nack:auto\n\n\0`,
    );
    const outsiderFrame = await waitForFrame(outsiderSocket, (frame) =>
      frame.startsWith("ERROR"),
    );
    expect(outsiderFrame).toContain("ERROR");
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(memberSocket.readyState).toBe(WebSocket.OPEN);
    return { memberConnected: true, nonMemberSubscriptionDenied: true };
  } finally {
    memberSocket.close();
    outsiderSocket.close();
  }
}

async function connectStomp(idToken: string): Promise<WebSocket> {
  const socketUrl = apiUrl!.replace(/^https:/, "wss:") + "/ws";
  const socket = new WebSocket(socketUrl, { origin: previewUrl });
  await new Promise<void>((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  socket.send(
    `CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${idToken}\n\n\0`,
  );
  const frame = await waitForFrame(socket, (candidate) =>
    candidate.startsWith("CONNECTED"),
  );
  expect(frame).toContain("CONNECTED");
  return socket;
}

async function waitForFrame(
  socket: WebSocket,
  predicate: (frame: string) => boolean,
  timeoutMs = 5_000,
): Promise<string> {
  return await new Promise<string>((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error("STOMP frame timeout"));
    }, timeoutMs);
    const onMessage = (raw: WebSocket.RawData) => {
      for (const frame of raw.toString().split("\0").filter(Boolean)) {
        if (!predicate(frame)) continue;
        cleanup();
        resolve(frame);
        return;
      }
    };
    const onError = (error: Error) => {
      cleanup();
      reject(error);
    };
    const cleanup = () => {
      clearTimeout(timeout);
      socket.off("message", onMessage);
      socket.off("error", onError);
    };
    socket.on("message", onMessage);
    socket.on("error", onError);
  });
}

async function waitForMetadataTerminalState(
  request: APIRequestContext,
  evidence: TraceEvidence[],
  identity: Identity,
  tripId: number,
  slotId: number,
  candidateId: number,
): Promise<Candidate> {
  const deadline = Date.now() + 330_000;
  while (Date.now() < deadline) {
    const response = await request.get(
      `${apiUrl}/api/trips/${tripId}/slots/${slotId}`,
      { headers: { Authorization: `Bearer ${identity.idToken}` } },
    );
    expect(response.status()).toBe(200);
    const detail = (await response.json()) as { candidates: Candidate[] };
    const candidate = detail.candidates.find(
      (entry) => entry.id === candidateId,
    );
    expect(candidate).toBeTruthy();
    if (
      ["COMPLETED", "FAILED_RETRYABLE", "FAILED_PERMANENT"].includes(
        candidate!.metadataStatus,
      )
    ) {
      await recordEvidence(response, evidence, "candidate.metadata_terminal");
      return candidate!;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error("Candidate metadata did not reach a terminal state");
}

async function createAnonymousIdentity(
  request: APIRequestContext,
  apiKey: string,
): Promise<Identity> {
  const endpoint =
    `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=` +
    encodeURIComponent(apiKey);
  let lastError: unknown;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const response = await request.post(endpoint, {
        data: { returnSecureToken: true },
        timeout: 30_000,
      });
      expect(response.status()).toBe(200);
      const body = (await response.json()) as { idToken: string };
      expect(body.idToken).toBeTruthy();
      return { idToken: body.idToken };
    } catch (error) {
      lastError = error;
      if (attempt < 3)
        await new Promise((resolve) => setTimeout(resolve, attempt * 1_000));
    }
  }
  throw lastError;
}

async function api<T = unknown>(
  request: APIRequestContext,
  evidence: TraceEvidence[],
  identity: Identity,
  options: {
    check: string;
    method: "GET" | "POST" | "PUT" | "PATCH";
    path: string;
    expectedStatus: number;
    idempotencyKey?: string;
    data?: unknown;
  },
): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${identity.idToken}`,
  };
  if (options.idempotencyKey)
    headers["Idempotency-Key"] = options.idempotencyKey;
  const response = await request.fetch(`${apiUrl}${options.path}`, {
    method: options.method,
    headers,
    data: options.data,
  });
  await recordEvidence(response, evidence, options.check);
  expect(
    response.status(),
    `${options.check}: ${await safeProblem(response)}`,
  ).toBe(options.expectedStatus);
  if (response.status() === 204) return undefined as T;
  return (await response.json()) as T;
}

async function recordEvidence(
  response: APIResponse,
  evidence: TraceEvidence[],
  check: string,
) {
  let traceId = response.headers()["x-trace-id"] ?? "";
  if (!traceId && response.status() >= 400) {
    const problem = (await response.json()) as { traceId?: string };
    traceId = problem.traceId ?? "";
  }
  expect(traceId, `${check} trace ID`).not.toBe("");
  evidence.push({ check, status: response.status(), traceId });
}

async function safeProblem(response: APIResponse) {
  try {
    const body = (await response.json()) as { code?: string; message?: string };
    return `${body.code ?? "UNKNOWN"}: ${body.message ?? ""}`;
  } catch {
    return `HTTP ${response.status()}`;
  }
}
