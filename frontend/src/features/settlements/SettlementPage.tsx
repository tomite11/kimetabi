import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useRef } from "react";
import { useParams } from "react-router";

import { refreshAccessToken } from "../../api/client";
import { useTripSnapshot } from "../trips/TripShell";
import shellStyles from "../trips/TripShell.module.css";
import {
  confirmSettlement,
  createSettlement,
  SettlementApiError,
  settlementKeys,
  settlementsQuery,
  type Settlement,
  type SettlementPage as SettlementPageData,
  updateTransfer,
} from "./settlementApi";
import styles from "./SettlementPage.module.css";

const yen = new Intl.NumberFormat("ja-JP", {
  style: "currency",
  currency: "JPY",
  maximumFractionDigits: 0,
});

const date = new Intl.DateTimeFormat("ja-JP", {
  year: "numeric",
  month: "short",
  day: "numeric",
});

export function SettlementPage() {
  const { tripId: value } = useParams();
  const tripId = Number(value);
  const snapshot = useTripSnapshot();
  const queryClient = useQueryClient();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const query = useQuery(settlementsQuery(tripId));
  const memberNames = useMemo(
    () => new Map(snapshot.members.map((member) => [member.id, member.name])),
    [snapshot.members],
  );
  const currentMember = snapshot.members.find(
    (member) => member.id === snapshot.currentMemberId,
  );
  const isAdministrator =
    currentMember?.role === "OWNER" || currentMember?.role === "ORGANIZER";

  const replaceSettlement = (updated: Settlement) => {
    queryClient.setQueryData<SettlementPageData>(
      settlementKeys.all(tripId),
      (page) => {
        if (!page) return { items: [updated], nextCursor: null };
        const exists = page.items.some((item) => item.id === updated.id);
        return {
          ...page,
          items: exists
            ? page.items.map((item) =>
                item.id === updated.id ? updated : item,
              )
            : [updated, ...page.items],
        };
      },
    );
    requestAnimationFrame(() => headingRef.current?.focus());
  };

  const retryAuthentication = async <T,>(operation: () => Promise<T>) => {
    try {
      return await operation();
    } catch (error) {
      if (!(error instanceof SettlementApiError) || error.status !== 401)
        throw error;
      await refreshAccessToken();
      return operation();
    }
  };

  const createMutation = useMutation({
    mutationFn: () => {
      const idempotencyKey = crypto.randomUUID();
      return retryAuthentication(() =>
        createSettlement(tripId, snapshot.trip.revision, idempotencyKey),
      );
    },
    onSuccess: replaceSettlement,
  });
  const confirmMutation = useMutation({
    mutationFn: (settlement: Settlement) =>
      retryAuthentication(() =>
        confirmSettlement(tripId, settlement.id, settlement.version),
      ),
    onSuccess: replaceSettlement,
  });
  const transferMutation = useMutation({
    mutationFn: ({
      settlement,
      transferId,
      status,
      version,
    }: {
      settlement: Settlement;
      transferId: number;
      status: "PAID" | "CONFIRMED";
      version: number;
    }) =>
      retryAuthentication(() =>
        updateTransfer(tripId, settlement.id, transferId, status, version),
      ),
    onSuccess: replaceSettlement,
  });
  const mutationError =
    createMutation.error || confirmMutation.error || transferMutation.error;
  const conflict =
    mutationError instanceof SettlementApiError && mutationError.status === 409;

  if (query.isPending) return <p role="status">精算を読み込んでいます…</p>;
  if (query.isError)
    return (
      <section
        className={styles.errorState}
        aria-labelledby="settlement-error-title"
      >
        <h2 id="settlement-error-title">精算を読み込めませんでした</h2>
        <button type="button" onClick={() => void query.refetch()}>
          再読み込み
        </button>
      </section>
    );

  const current = query.data.items.find((item) => item.status !== "SUPERSEDED");
  const history = query.data.items.filter((item) => item.id !== current?.id);
  const pendingTransfers =
    current?.transfers.filter((transfer) => transfer.status !== "CONFIRMED") ??
    [];

  return (
    <section className={styles.page} aria-labelledby="settlement-title">
      <header>
        <p className={shellStyles.sectionLabel}>SETTLEMENT</p>
        <h2
          className={shellStyles.pageTitle}
          id="settlement-title"
          ref={headingRef}
          tabIndex={-1}
        >
          {current
            ? `${current.transfers.length}回の送金で、今回の旅を締めます。`
            : "旅の会計を、ひとつにまとめます。"}
        </h2>
        <p className={shellStyles.lead}>
          支払う人と受け取る人、それぞれに必要な操作だけを表示する、
          送金回数を抑えた精算です。
        </p>
      </header>

      {mutationError ? (
        <div className={styles.errorPanel} role="alert">
          <strong>
            {conflict
              ? "ほかの端末で精算が更新されました。"
              : mutationError.message}
          </strong>
          {conflict ? (
            <button type="button" onClick={() => void query.refetch()}>
              最新内容を読み込む
            </button>
          ) : null}
        </div>
      ) : null}

      {!current ? (
        <div className={styles.emptyState}>
          <span aria-hidden="true">¥</span>
          <h3>精算案はまだありません</h3>
          <p>確定済みの支出をもとに、送金回数を抑えた精算案を作成します。</p>
          {isAdministrator ? (
            <button
              type="button"
              disabled={createMutation.isPending}
              onClick={() => createMutation.mutate()}
            >
              {createMutation.isPending ? "計算しています…" : "精算案を作る"}
            </button>
          ) : (
            <small>精算案はOWNERまたはORGANIZERが作成できます。</small>
          )}
        </div>
      ) : (
        <>
          <div className={styles.summary} aria-label="精算対象の支出集計">
            <div>
              <p>旅行の支出合計</p>
              <strong>{yen.format(current.expenseTotal)}</strong>
            </div>
            <span>{current.expenseVersions.length}件</span>
          </div>

          {current.hasUnappliedChanges ? (
            <aside
              className={styles.unapplied}
              aria-labelledby="unapplied-title"
            >
              <div>
                <strong id="unapplied-title">未反映の変更があります</strong>
                <p>
                  確定後に追加・更新された支出を、新しい精算案へ反映できます。
                </p>
              </div>
              {isAdministrator ? (
                <button
                  type="button"
                  disabled={createMutation.isPending}
                  onClick={() => createMutation.mutate()}
                >
                  再計算する
                </button>
              ) : null}
            </aside>
          ) : null}

          {current.status === "DRAFT" ? (
            <section
              className={styles.draftNotice}
              aria-labelledby="draft-title"
            >
              <div>
                <span>確認中</span>
                <h3 id="draft-title">この精算案を確定しますか？</h3>
                <p>確定後は送金先と金額を変更せず、進捗だけを記録します。</p>
              </div>
              {isAdministrator ? (
                <button
                  type="button"
                  disabled={
                    confirmMutation.isPending || current.hasUnappliedChanges
                  }
                  onClick={() => confirmMutation.mutate(current)}
                >
                  {confirmMutation.isPending ? "確定しています…" : "精算を確定"}
                </button>
              ) : (
                <small>OWNERまたはORGANIZERの確定を待っています。</small>
              )}
            </section>
          ) : null}

          {current.status === "COMPLETED" ? (
            <div className={styles.completed} role="status">
              <span aria-hidden="true">✓</span>
              <div>
                <strong>精算が完了しました</strong>
                <p>全員の受取確認が済んでいます。</p>
              </div>
            </div>
          ) : null}

          <section aria-labelledby="transfer-title">
            <div className={styles.sectionHeading}>
              <div>
                <p>PAYMENTS</p>
                <h3 id="transfer-title">送金の進み具合</h3>
              </div>
              <span>{pendingTransfers.length}件 未完了</span>
            </div>
            {current.transfers.length ? (
              <ol className={styles.transferList}>
                {current.transfers.map((transfer) => {
                  const canPay =
                    transfer.status === "PENDING" &&
                    (transfer.fromMemberId === snapshot.currentMemberId ||
                      isAdministrator);
                  const canConfirm =
                    transfer.status === "PAID" &&
                    (transfer.toMemberId === snapshot.currentMemberId ||
                      isAdministrator);
                  const proxy =
                    (canPay &&
                      transfer.fromMemberId !== snapshot.currentMemberId) ||
                    (canConfirm &&
                      transfer.toMemberId !== snapshot.currentMemberId);
                  return (
                    <li key={transfer.id}>
                      <div className={styles.transferMain}>
                        <div className={styles.transferPath}>
                          <span>
                            {memberNames.get(transfer.fromMemberId) ??
                              "メンバー"}
                          </span>
                          <span aria-hidden="true">→</span>
                          <span>
                            {memberNames.get(transfer.toMemberId) ?? "メンバー"}
                          </span>
                        </div>
                        <strong>{yen.format(transfer.amount)}</strong>
                      </div>
                      <div className={styles.transferState}>
                        <span data-status={transfer.status}>
                          {transfer.status === "PENDING"
                            ? "支払い待ち"
                            : transfer.status === "PAID"
                              ? "受取確認待ち"
                              : "受取確認済み"}
                        </span>
                        {canPay || canConfirm ? (
                          <button
                            type="button"
                            disabled={transferMutation.isPending}
                            onClick={() =>
                              transferMutation.mutate({
                                settlement: current,
                                transferId: transfer.id,
                                status: canPay ? "PAID" : "CONFIRMED",
                                version: transfer.version,
                              })
                            }
                          >
                            {canPay ? "支払い済みにする" : "受取を確認する"}
                            {proxy ? "（代理）" : ""}
                          </button>
                        ) : null}
                      </div>
                    </li>
                  );
                })}
              </ol>
            ) : (
              <div className={styles.zeroState}>
                <strong>送金は必要ありません</strong>
                <p>全員の立替額と負担額がすでに釣り合っています。</p>
              </div>
            )}
          </section>
        </>
      )}

      {history.length ? (
        <section className={styles.history} aria-labelledby="history-title">
          <h3 id="history-title">以前の精算</h3>
          <ul>
            {history.map((item) => (
              <li key={item.id}>
                <div>
                  <strong>{date.format(new Date(item.calculatedAt))}</strong>
                  <small>
                    {item.expenseVersions.length}件 ·{" "}
                    {yen.format(item.expenseTotal)}
                  </small>
                </div>
                <span>
                  {item.status === "SUPERSEDED"
                    ? "再計算済み"
                    : item.status === "COMPLETED"
                      ? "完了"
                      : item.status}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </section>
  );
}
