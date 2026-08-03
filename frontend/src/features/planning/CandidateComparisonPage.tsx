import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { useParams } from "react-router";

import type { components } from "../../api/generated/schema";
import { useAuth } from "../../auth/AuthProvider";
import { tripKeys } from "../trips/tripQueries";
import { useTripSnapshot } from "../trips/TripShell";
import {
  adoptCandidate,
  PlanningApiError,
  planningKeys,
  putVote,
  retryCandidateMetadata,
  updateCandidateManually,
  slotDetailQuery,
  type Candidate,
  type VoteChoice,
} from "./planningApi";
import {
  enqueueCandidate,
  flushCandidateQueue,
  listQueuedCandidates,
  type QueuedCandidate,
} from "./candidateQueue";
import { candidateSchema, type CandidateFormValues } from "./planningSchema";
import styles from "./Planning.module.css";

type VoteView = components["schemas"]["VoteView"];

const voteLabels: Record<VoteChoice, string> = {
  YES: "これがいい",
  ANY: "どちらでも",
  NO: "むり",
};

const metadataLabels: Record<components["schemas"]["MetadataStatus"], string> =
  {
    PENDING: "情報を取得待ち",
    PROCESSING: "情報を取得中",
    COMPLETED: "情報を取得済み",
    FAILED_RETRYABLE: "取得に失敗・再試行できます",
    FAILED_PERMANENT: "自動取得できませんでした",
  };

function candidatePerPerson(
  candidate: Candidate,
  units: number,
  members: number,
) {
  if (candidate.estAmount == null || candidate.estBasis == null) return 0;
  return candidate.estBasis === "PER_PERSON"
    ? candidate.estAmount * units
    : Math.ceil(candidate.estAmount / members);
}

export function CandidateComparisonPage() {
  const { tripId: tripValue, slotId: slotValue } = useParams();
  const tripId = Number(tripValue);
  const slotId = Number(slotValue);
  const snapshot = useTripSnapshot();
  const { uid } = useAuth();
  const queryClient = useQueryClient();
  const detailQuery = useQuery(slotDetailQuery(tripId, slotId));
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [noCandidateId, setNoCandidateId] = useState<number | null>(null);
  const [reason, setReason] = useState("");
  const [conflict, setConflict] = useState<string | null>(null);
  const [queuedCandidates, setQueuedCandidates] = useState<QueuedCandidate[]>(
    [],
  );
  const [editingCandidateId, setEditingCandidateId] = useState<number | null>(
    null,
  );
  const [manualTitle, setManualTitle] = useState("");
  const [manualAmount, setManualAmount] = useState("");
  const headingRef = useRef<HTMLHeadingElement>(null);
  const urlInputRef = useRef<HTMLInputElement | null>(null);
  const form = useForm<CandidateFormValues>({
    resolver: zodResolver(candidateSchema),
    defaultValues: {
      title: "",
      url: "",
      estAmount: "",
      estBasis: "PER_PERSON",
    },
  });
  const currentMember = snapshot.members.find(
    (member) => member.id === snapshot.currentMemberId,
  );
  const canAdopt =
    currentMember?.role === "OWNER" || currentMember?.role === "ORGANIZER";

  const applyCreatedCandidate = useCallback(
    (candidate: Candidate) => {
      queryClient.setQueryData(
        planningKeys.slot(tripId, slotId),
        (current: components["schemas"]["SlotDetail"] | undefined) =>
          current
            ? {
                ...current,
                candidates: current.candidates.some(
                  (currentCandidate) => currentCandidate.id === candidate.id,
                )
                  ? current.candidates.map((currentCandidate) =>
                      currentCandidate.id === candidate.id
                        ? candidate
                        : currentCandidate,
                    )
                  : [...current.candidates, candidate],
              }
            : current,
      );
      setSelectedId(candidate.id);
    },
    [queryClient, slotId, tripId],
  );

  const refreshQueue = useCallback(async () => {
    setQueuedCandidates(await listQueuedCandidates(uid, tripId, slotId));
  }, [slotId, tripId, uid]);

  const flushQueue = useCallback(async () => {
    if (!navigator.onLine) {
      await refreshQueue();
      return;
    }
    await flushCandidateQueue(uid, tripId, applyCreatedCandidate);
    await refreshQueue();
  }, [applyCreatedCandidate, refreshQueue, tripId, uid]);

  useEffect(() => {
    if (detailQuery.isSuccess) headingRef.current?.focus();
  }, [detailQuery.isSuccess]);

  useEffect(() => {
    void refreshQueue().then(flushQueue);
    const retry = () => void flushQueue();
    const retryOnFocus = () => {
      if (document.visibilityState === "visible") retry();
    };
    window.addEventListener("online", retry);
    window.addEventListener("focus", retry);
    document.addEventListener("visibilitychange", retryOnFocus);
    return () => {
      window.removeEventListener("online", retry);
      window.removeEventListener("focus", retry);
      document.removeEventListener("visibilitychange", retryOnFocus);
    };
  }, [flushQueue, refreshQueue]);

  const createMutation = useMutation({
    mutationFn: async (values: CandidateFormValues) => {
      await enqueueCandidate(uid, tripId, slotId, {
        title: values.title || undefined,
        url: values.url || undefined,
        estAmount: values.estAmount === "" ? undefined : values.estAmount,
        estBasis: values.estAmount === "" ? undefined : values.estBasis,
        tags: [],
      });
      await refreshQueue();
      await flushQueue();
    },
    onSuccess: () => {
      form.reset();
      urlInputRef.current?.focus();
    },
  });
  const retryMetadataMutation = useMutation({
    mutationFn: (candidate: Candidate) =>
      retryCandidateMetadata(tripId, candidate),
    onSuccess: applyCreatedCandidate,
  });
  const manualUpdateMutation = useMutation({
    mutationFn: (candidate: Candidate) =>
      updateCandidateManually(
        tripId,
        candidate,
        manualTitle.trim(),
        manualAmount === "" ? null : Number(manualAmount),
      ),
    onSuccess: (candidate) => {
      applyCreatedCandidate(candidate);
      setEditingCandidateId(null);
    },
  });
  const voteMutation = useMutation({
    mutationFn: ({
      candidate,
      choice,
    }: {
      candidate: Candidate;
      choice: VoteChoice;
    }) => {
      const view = detailQuery.data?.votesByCandidate[String(candidate.id)];
      return putVote(
        tripId,
        candidate.id,
        choice,
        choice === "NO" ? reason.trim() : undefined,
        view?.myVote?.version,
      );
    },
    onSuccess: (view, variables) => {
      queryClient.setQueryData(
        planningKeys.slot(tripId, slotId),
        (current: components["schemas"]["SlotDetail"] | undefined) =>
          current
            ? {
                ...current,
                votesByCandidate: {
                  ...current.votesByCandidate,
                  [variables.candidate.id]: view,
                },
              }
            : current,
      );
      setNoCandidateId(null);
      setReason("");
      setConflict(null);
    },
    onError: (error) => {
      if (error instanceof PlanningApiError && error.status === 409)
        setConflict("ほかの端末で投票が更新されました。");
    },
  });
  const adoptionMutation = useMutation({
    mutationFn: (candidateId: number) =>
      adoptCandidate(tripId, detailQuery.data!.slot, candidateId),
    onSuccess: (result) => {
      queryClient.setQueryData(
        planningKeys.slot(tripId, slotId),
        (current: components["schemas"]["SlotDetail"] | undefined) =>
          current ? { ...current, slot: result.slot } : current,
      );
      queryClient.setQueryData(
        tripKeys.snapshot(tripId),
        (current: components["schemas"]["TripSnapshot"] | undefined) =>
          current
            ? {
                ...current,
                slots: current.slots.map((slot) =>
                  slot.id === result.slot.id ? result.slot : slot,
                ),
                planItems: [
                  ...current.planItems.filter(
                    (planItem) => planItem.slotId !== result.planItem.slotId,
                  ),
                  result.planItem,
                ],
              }
            : current,
      );
      setConflict(null);
    },
    onError: (error) => {
      if (error instanceof PlanningApiError && error.status === 409)
        setConflict("ほかのメンバーが先に候補を決定しました。");
    },
  });

  if (detailQuery.isPending)
    return <p role="status">候補を読み込んでいます…</p>;
  if (detailQuery.isError)
    return (
      <div className={styles.emptyState}>
        <strong>候補を読み込めませんでした</strong>
        <button
          className={styles.secondaryButton}
          type="button"
          onClick={() => void detailQuery.refetch()}
        >
          再読み込み
        </button>
      </div>
    );

  const detail = detailQuery.data;
  const selected = detail.candidates.find(
    (candidate) =>
      candidate.id ===
      (selectedId ??
        detail.slot.adoptedCandidateId ??
        detail.candidates[0]?.id),
  );
  const otherPerPerson = snapshot.slots
    .filter((slot) => slot.id !== slotId)
    .reduce((sum, slot) => sum + (slot.estPerPerson ?? 0), 0);
  const perPerson =
    otherPerPerson +
    (selected
      ? candidatePerPerson(
          selected,
          detail.slot.units,
          snapshot.trip.expectedMemberCount,
        )
      : (detail.slot.estPerPerson ?? 0));
  const total = perPerson * snapshot.trip.expectedMemberCount;
  const cap = snapshot.trip.budgetCap;
  const over = cap != null && total > cap;

  const submitVote = (candidate: Candidate, choice: VoteChoice) => {
    if (choice === "NO" && noCandidateId !== candidate.id) {
      setNoCandidateId(candidate.id);
      return;
    }
    if (choice === "NO" && !reason.trim()) return;
    voteMutation.mutate({ candidate, choice });
  };

  return (
    <section aria-labelledby="compare-title">
      <div className={styles.slotHead}>
        <div>
          <p className={styles.sectionLabel}>
            DAY {detail.slot.dayFrom} · {detail.slot.category}
          </p>
          <h2
            className={styles.pageTitle}
            id="compare-title"
            ref={headingRef}
            tabIndex={-1}
          >
            {detail.slot.title}
          </h2>
        </div>
        <small>{detail.candidates.length}件の候補</small>
      </div>
      <p className={styles.lead}>
        候補を選ぶと仮選択になり、旅行全体の予算が動きます。
      </p>

      {detail.candidates.length === 0 ? (
        <div className={styles.emptyState}>
          <strong>候補はまだありません</strong>
          <p>URLを貼るか、名前だけでも追加できます。</p>
          <button
            className={styles.secondaryButton}
            type="button"
            onClick={() => urlInputRef.current?.focus()}
          >
            URLから候補を追加
          </button>
        </div>
      ) : null}
      <div className={styles.candidateList}>
        {queuedCandidates.map((operation) => {
          const payload = operation.payload;
          return (
            <article className={styles.candidateCard} key={operation.id}>
              <div className={styles.pendingVisual} aria-hidden="true">
                待
              </div>
              <div className={styles.candidateBody}>
                <strong>
                  {payload.title || "情報を取得して名前を表示します"}
                </strong>
                <p
                  className={styles.metadataStatus}
                  data-state={operation.state}
                >
                  {operation.state === "CONFLICT"
                    ? "ほかの操作と競合しました"
                    : operation.state === "NEEDS_CORRECTION"
                      ? "入力内容の修正が必要です"
                      : navigator.onLine
                        ? "候補を送信しています…"
                        : "オフラインで保存しました。接続後に送信します"}
                </p>
                {payload.url ? (
                  <small>{new URL(payload.url).hostname}</small>
                ) : null}
              </div>
            </article>
          );
        })}
        {detail.candidates.map((candidate) => {
          const view: VoteView | undefined =
            detail.votesByCandidate[String(candidate.id)];
          const amount = candidatePerPerson(
            candidate,
            detail.slot.units,
            snapshot.trip.expectedMemberCount,
          );
          const base = selected
            ? candidatePerPerson(
                selected,
                detail.slot.units,
                snapshot.trip.expectedMemberCount,
              )
            : amount;
          return (
            <article
              className={styles.candidateCard}
              data-selected={selected?.id === candidate.id}
              key={candidate.id}
            >
              <button
                className={styles.candidateSelect}
                type="button"
                aria-pressed={selected?.id === candidate.id}
                onClick={() => setSelectedId(candidate.id)}
              >
                <span className="sr-only">
                  {candidate.title || "名称未設定"}を仮選択
                </span>
              </button>
              <div className={styles.candidateBody}>
                <strong>{candidate.title || "名称未設定"}</strong>
                <p
                  className={styles.metadataStatus}
                  data-status={candidate.metadataStatus}
                  role={
                    candidate.metadataStatus === "PROCESSING"
                      ? "status"
                      : undefined
                  }
                >
                  {metadataLabels[candidate.metadataStatus]}
                </p>
                {candidate.imageUrl ? (
                  <img
                    className={styles.candidateImage}
                    src={candidate.imageUrl}
                    alt=""
                    loading="lazy"
                  />
                ) : null}
                <div className={styles.candidatePrice}>
                  ¥{amount.toLocaleString("ja-JP")}{" "}
                  <small>
                    / 人{" "}
                    {amount !== base
                      ? `· ${amount > base ? "+" : "−"}¥${Math.abs(amount - base).toLocaleString("ja-JP")}`
                      : "· 基準"}
                  </small>
                </div>
                {candidate.note ? <p>{candidate.note}</p> : null}
                {candidate.tags.length ? (
                  <div className={styles.tags}>
                    {candidate.tags.map((tag) => (
                      <span key={tag}>{tag}</span>
                    ))}
                  </div>
                ) : null}
                {candidate.metadataStatus === "FAILED_RETRYABLE" ? (
                  <button
                    className={styles.retryButton}
                    disabled={retryMetadataMutation.isPending}
                    type="button"
                    onClick={() => retryMetadataMutation.mutate(candidate)}
                  >
                    メタデータを再取得
                  </button>
                ) : null}
                {candidate.metadataStatus === "FAILED_PERMANENT" ? (
                  <>
                    <small>自動取得なしでも候補はそのまま利用できます。</small>
                    <button
                      className={styles.retryButton}
                      type="button"
                      onClick={() => {
                        setEditingCandidateId(candidate.id);
                        setManualTitle(candidate.title ?? "");
                        setManualAmount(candidate.estAmount?.toString() ?? "");
                      }}
                    >
                      名前と金額を手入力
                    </button>
                  </>
                ) : null}
                {editingCandidateId === candidate.id ? (
                  <form
                    className={styles.manualForm}
                    onSubmit={(event) => {
                      event.preventDefault();
                      if (manualTitle.trim())
                        manualUpdateMutation.mutate(candidate);
                    }}
                  >
                    <label>
                      候補名
                      <input
                        autoFocus
                        required
                        value={manualTitle}
                        onChange={(event) => setManualTitle(event.target.value)}
                      />
                    </label>
                    <label>
                      金額
                      <input
                        inputMode="numeric"
                        min="0"
                        type="number"
                        value={manualAmount}
                        onChange={(event) =>
                          setManualAmount(event.target.value)
                        }
                      />
                    </label>
                    <div className={styles.manualActions}>
                      <button
                        className={styles.secondaryButton}
                        type="button"
                        onClick={() => setEditingCandidateId(null)}
                      >
                        キャンセル
                      </button>
                      <button
                        className={styles.retryButton}
                        disabled={
                          !manualTitle.trim() || manualUpdateMutation.isPending
                        }
                        type="submit"
                      >
                        手入力を保存
                      </button>
                    </div>
                  </form>
                ) : null}
                <div
                  className={styles.voteRow}
                  aria-label={`${candidate.title || "候補"}への投票`}
                >
                  {(["YES", "ANY", "NO"] as const).map((choice) => (
                    <button
                      aria-pressed={view?.myVote?.choice === choice}
                      disabled={voteMutation.isPending}
                      key={choice}
                      type="button"
                      onClick={() => submitVote(candidate, choice)}
                    >
                      {voteLabels[choice]}
                    </button>
                  ))}
                </div>
                {noCandidateId === candidate.id ? (
                  <div className={styles.reasonField}>
                    <label>
                      「むり」の理由
                      <textarea
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                      />
                    </label>
                    <button
                      disabled={!reason.trim()}
                      type="button"
                      onClick={() => submitVote(candidate, "NO")}
                    >
                      理由を添えて投票
                    </button>
                  </div>
                ) : null}
                <small>
                  いい {view?.yesCount ?? 0} · どちらでも {view?.anyCount ?? 0}{" "}
                  · むり {view?.noCount ?? 0} · 未投票{" "}
                  {view?.unvotedMemberIds.length ?? snapshot.members.length}人
                </small>
              </div>
            </article>
          );
        })}
      </div>

      <form
        className={styles.candidateForm}
        onSubmit={form.handleSubmit((values) => createMutation.mutate(values))}
      >
        <strong>候補を追加</strong>
        <label>
          名前
          <input {...form.register("title")} />
        </label>
        <label>
          URL
          <input
            inputMode="url"
            placeholder="https://example.com/hotel"
            {...form.register("url")}
            ref={(element) => {
              form.register("url").ref(element);
              urlInputRef.current = element;
            }}
          />
        </label>
        {form.formState.errors.title ||
        form.formState.errors.url ||
        form.formState.errors.estAmount ? (
          <p role="alert">
            {form.formState.errors.title?.message ||
              form.formState.errors.url?.message ||
              form.formState.errors.estAmount?.message}
          </p>
        ) : null}
        <div className={styles.formColumns}>
          <label>
            金額
            <input inputMode="numeric" {...form.register("estAmount")} />
          </label>
          <label>
            金額の単位
            <select {...form.register("estBasis")}>
              <option value="PER_PERSON">1人・1単位あたり</option>
              <option value="TOTAL">合計</option>
            </select>
          </label>
        </div>
        <button disabled={createMutation.isPending} type="submit">
          この枠に追加
        </button>
        {createMutation.isError ? (
          <p className={styles.error} role="alert">
            候補を追加できませんでした。入力内容と通信状態を確認してください。
          </p>
        ) : null}
        {retryMetadataMutation.isError ? (
          <p className={styles.error} role="alert">
            メタデータを再取得できませんでした。もう一度お試しください。
          </p>
        ) : null}
        {manualUpdateMutation.isError ? (
          <p className={styles.error} role="alert">
            手入力を保存できませんでした。現在値を確認してください。
          </p>
        ) : null}
      </form>

      {(voteMutation.isError && conflict == null) ||
      (adoptionMutation.isError && conflict == null) ? (
        <p className={styles.error} role="alert">
          操作を完了できませんでした。通信状態を確認して、もう一度お試しください。
        </p>
      ) : null}

      {conflict ? (
        <div className={styles.conflict} role="alert">
          <strong>{conflict}</strong>
          <p>現在の内容を読み込んでから、もう一度選べます。</p>
          <button
            className={styles.secondaryButton}
            type="button"
            onClick={() => {
              setConflict(null);
              void detailQuery.refetch();
            }}
          >
            現在値を再読み込み
          </button>
        </div>
      ) : null}
      <aside className={styles.budgetBar} aria-label="旅行予算">
        <div className={styles.budgetRow}>
          <strong>1人 ¥{perPerson.toLocaleString("ja-JP")}</strong>
          <span>合計 ¥{total.toLocaleString("ja-JP")}</span>
        </div>
        <div className={styles.budgetTrack} data-over={over}>
          <span
            style={{
              width: cap ? `${Math.min(100, (total / cap) * 100)}%` : "35%",
            }}
          />
        </div>
        <div className={styles.budgetRow}>
          <small>
            {cap == null
              ? "予算上限は未設定"
              : over
                ? `¥${(total - cap).toLocaleString("ja-JP")} オーバー`
                : `あと ¥${(cap - total).toLocaleString("ja-JP")}`}
          </small>
          {canAdopt && selected ? (
            <button
              className={styles.adoptButton}
              disabled={adoptionMutation.isPending}
              type="button"
              onClick={() => adoptionMutation.mutate(selected.id)}
            >
              {detail.slot.adoptedCandidateId
                ? "この候補に変更"
                : "この候補を決定"}
            </button>
          ) : null}
        </div>
      </aside>
    </section>
  );
}
