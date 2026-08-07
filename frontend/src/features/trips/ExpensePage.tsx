import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useParams } from "react-router";
import { z } from "zod";

import type { components } from "../../api/generated/schema";
import { refreshAccessToken } from "../../api/client";
import { useAuth } from "../../auth/AuthProvider";
import {
  confirmExpense,
  expenseKeys,
  ExpenseApiError,
  expensesQuery,
  getPreviousSharePreset,
  type Expense,
  type ExpensePage as ExpensePageData,
  type UpdateExpenseRequest,
} from "../expenses/expenseApi";
import {
  discardQueuedExpense,
  enqueueExpenseConfirmation,
  retryQueuedExpense,
} from "../expenses/expenseQueue";
import { useExpenseQueue } from "../expenses/useExpenseQueue";
import { useTripSnapshot } from "./TripShell";
import styles from "./ExpensePage.module.css";
import shellStyles from "./TripShell.module.css";

type AllocationType = components["schemas"]["AllocationType"];
type ExpenseShareInput = components["schemas"]["ExpenseShareInput"];
type Preset = "all" | "previous" | "specified";

const confirmSchema = z.object({
  amount: z.coerce.number().int().min(1, "1円以上を入力してください。"),
  payerId: z.coerce.number().int().positive(),
  fixedAmounts: z.record(z.string(), z.coerce.number().int().min(0)),
});
type ConfirmForm = z.infer<typeof confirmSchema>;

const yen = new Intl.NumberFormat("ja-JP", {
  style: "currency",
  currency: "JPY",
  maximumFractionDigits: 0,
});

function queueStateLabel(state: string) {
  if (state === "RETRYING") return "送信中";
  if (state === "CONFLICT") return "競合を確認してください";
  if (state === "NEEDS_CORRECTION") return "入力の修正が必要です";
  return "アップロード待ち";
}

function sharesFor(
  preset: Preset,
  memberIds: number[],
  fixedAmounts: Record<string, number>,
): { allocationType: AllocationType; shares: ExpenseShareInput[] } {
  if (preset !== "specified") {
    return {
      allocationType: "EQUAL",
      shares: memberIds.map((memberId) => ({ memberId, weight: 1 })),
    };
  }
  return {
    allocationType: "FIXED_AND_WEIGHT",
    shares: memberIds.map((memberId) => {
      const fixedAmount = Number(fixedAmounts[String(memberId)] ?? 0);
      return fixedAmount > 0
        ? { memberId, fixedAmount }
        : { memberId, weight: 1 };
    }),
  };
}

export function ExpensePage() {
  const tripId = Number(useParams().tripId);
  const snapshot = useTripSnapshot();
  const { uid } = useAuth();
  const queryClient = useQueryClient();
  const expenseQuery = useQuery(expensesQuery(tripId));
  const queue = useExpenseQueue(tripId);
  const [selectedId, setSelectedId] = useState<number>();
  const [preset, setPreset] = useState<Preset>("all");
  const [presetMessage, setPresetMessage] = useState<string>();
  const [selectedMemberIds, setSelectedMemberIds] = useState<number[]>(() =>
    snapshot.members
      .filter((member) => member.status === "ACTIVE")
      .map((member) => member.id),
  );
  const [selectionError, setSelectionError] = useState<string>();
  const confirmTitleRef = useRef<HTMLHeadingElement>(null);
  const activeMembers = useMemo(
    () => snapshot.members.filter((member) => member.status === "ACTIVE"),
    [snapshot.members],
  );
  const drafts = useMemo(
    () =>
      expenseQuery.data?.items.filter((item) => item.status === "DRAFT") ?? [],
    [expenseQuery.data?.items],
  );
  const confirmed = useMemo(
    () =>
      expenseQuery.data?.items.filter((item) => item.status === "CONFIRMED") ??
      [],
    [expenseQuery.data?.items],
  );
  const selected = drafts.find((draft) => draft.id === selectedId);
  const {
    register,
    reset,
    watch,
    setValue,
    formState: { errors },
    handleSubmit,
  } = useForm<ConfirmForm>({
    resolver: zodResolver(confirmSchema),
    defaultValues: {
      payerId: snapshot.currentMemberId,
      fixedAmounts: {},
    },
  });
  const amount = watch("amount") || 0;
  const perPerson = selectedMemberIds.length
    ? Math.floor(amount / selectedMemberIds.length)
    : 0;

  useEffect(() => {
    if (!selected) return;
    setPreset("all");
    setPresetMessage(undefined);
    setSelectionError(undefined);
    setSelectedMemberIds(activeMembers.map((member) => member.id));
    reset({
      amount: selected.amount ?? undefined,
      payerId: selected.payerId ?? snapshot.currentMemberId,
      fixedAmounts: {},
    });
    requestAnimationFrame(() => confirmTitleRef.current?.focus());
  }, [activeMembers, reset, selected, snapshot.currentMemberId]);

  const confirmation = useMutation({
    mutationFn: ({
      expense,
      body,
    }: {
      expense: Expense;
      body: UpdateExpenseRequest;
    }) =>
      confirmExpense(tripId, expense.id, body).catch(async (error) => {
        if (!(error instanceof ExpenseApiError) || error.status !== 401)
          throw error;
        await refreshAccessToken();
        return confirmExpense(tripId, expense.id, body);
      }),
    onSuccess: (confirmedExpense) => {
      queryClient.setQueryData<ExpensePageData>(
        expenseKeys.all(tripId),
        (current) =>
          current
            ? {
                ...current,
                items: current.items.map((item) =>
                  item.id === confirmedExpense.id ? confirmedExpense : item,
                ),
              }
            : current,
      );
      const next = drafts.find((draft) => draft.id !== confirmedExpense.id);
      setSelectedId(next?.id);
    },
    onError: async (error, variables) => {
      if (error instanceof ExpenseApiError && error.status < 500) return;
      const state =
        error instanceof ExpenseApiError && error.status === 409
          ? "CONFLICT"
          : error instanceof ExpenseApiError &&
              error.status >= 400 &&
              error.status < 500
            ? "NEEDS_CORRECTION"
            : "PENDING";
      await enqueueExpenseConfirmation(
        uid,
        tripId,
        variables.expense.id,
        variables.body,
        state,
        error instanceof Error ? error.message : "オンライン復帰待ち",
      );
      await queue.refetch();
    },
  });

  async function applyPreset(nextPreset: Preset) {
    setPreset(nextPreset);
    setPresetMessage(undefined);
    if (nextPreset === "all") {
      setSelectedMemberIds(activeMembers.map((member) => member.id));
      return;
    }
    if (nextPreset === "specified") return;
    try {
      const previous = await getPreviousSharePreset(tripId);
      if (!previous) {
        setPreset("all");
        setPresetMessage("前回の確定支出がないため、全員を選びました。");
        return;
      }
      const available = new Set(activeMembers.map((member) => member.id));
      const ids = previous.shares
        .map((share) => share.memberId)
        .filter((id) => available.has(id));
      setSelectedMemberIds(ids);
      setValue(
        "fixedAmounts",
        Object.fromEntries(
          previous.shares.flatMap((share) =>
            share.fixedAmount == null
              ? []
              : [[String(share.memberId), share.fixedAmount]],
          ),
        ),
      );
      setPreset(previous.allocationType === "EQUAL" ? "previous" : "specified");
      setPresetMessage("前回と同じ負担メンバーを反映しました。");
    } catch {
      setPreset("all");
      setPresetMessage("前回の按分を取得できませんでした。全員を選びました。");
    }
  }

  function submit(values: ConfirmForm) {
    if (!selected) return;
    if (!selectedMemberIds.length) {
      setSelectionError("負担する人を選んでください。");
      return;
    }
    setSelectionError(undefined);
    const allocation = sharesFor(
      preset,
      selectedMemberIds,
      values.fixedAmounts,
    );
    confirmation.mutate({
      expense: selected,
      body: {
        version: selected.version,
        amount: values.amount,
        payerId: values.payerId,
        paidAt: selected.paidAt ?? new Date().toISOString(),
        status: "CONFIRMED",
        ...allocation,
      },
    });
  }

  if (expenseQuery.isPending)
    return <p role="status">支出を読み込んでいます…</p>;
  if (expenseQuery.isError)
    return (
      <section aria-labelledby="expense-error-title">
        <h2 id="expense-error-title">支出を読み込めませんでした</h2>
        <button type="button" onClick={() => void expenseQuery.refetch()}>
          再読み込み
        </button>
      </section>
    );

  return (
    <section className={styles.page} aria-labelledby="expenses-title">
      <header className={styles.heading}>
        <div>
          <p className={shellStyles.sectionLabel}>TRIP EXPENSES</p>
          <h2 className={shellStyles.pageTitle} id="expenses-title">
            支出
          </h2>
        </div>
        <Link className={styles.captureLink} to={`/t/${tripId}/expenses/new`}>
          支出を記録
        </Link>
      </header>

      {queue.data?.length ? (
        <section className={styles.queue} aria-labelledby="queue-title">
          <h3 id="queue-title">送信待ち</h3>
          <ul>
            {queue.data.map((operation) => (
              <li key={operation.id}>
                <div>
                  <strong>
                    {"amount" in operation.payload &&
                    typeof operation.payload.amount === "number"
                      ? yen.format(operation.payload.amount)
                      : "レシート写真"}
                  </strong>
                  <small>{queueStateLabel(operation.state)}</small>
                  {operation.lastProblem ? (
                    <small>{operation.lastProblem}</small>
                  ) : null}
                </div>
                {operation.state === "CONFLICT" ||
                operation.state === "NEEDS_CORRECTION" ? (
                  <button
                    type="button"
                    onClick={() => {
                      if (operation.operationType === "CONFIRM_EXPENSE") {
                        setSelectedId(operation.resourceId);
                        void discardQueuedExpense(operation.id).then(
                          async () => {
                            await expenseQuery.refetch();
                            await queue.refetch();
                          },
                        );
                        return;
                      }
                      void retryQueuedExpense(operation.id).then(() =>
                        queue.flush(),
                      );
                    }}
                  >
                    {operation.operationType === "CONFIRM_EXPENSE"
                      ? "内容を確認"
                      : "再試行"}
                  </button>
                ) : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {!drafts.length && !confirmed.length && !queue.data?.length ? (
        <div className={shellStyles.emptyState}>
          <strong>支出はまだありません</strong>
          <p>
            レシート写真または金額だけで記録できます。オフラインでも端末に残ります。
          </p>
          <Link className={styles.emptyAction} to={`/t/${tripId}/expenses/new`}>
            最初の支出を記録
          </Link>
        </div>
      ) : null}

      {drafts.length ? (
        <section className={styles.tray} aria-labelledby="draft-title">
          <div className={styles.sectionHeading}>
            <div>
              <h3 id="draft-title">未確定の支出</h3>
              <p>移動中に記録した支出を、まとめて確認できます。</p>
            </div>
            <span>{drafts.length}件</span>
          </div>
          <ul className={styles.draftList}>
            {drafts.map((draft) => (
              <li key={draft.id}>
                <button type="button" onClick={() => setSelectedId(draft.id)}>
                  <span aria-hidden="true">
                    {draft.receipts.length ? "▣" : "¥"}
                  </span>
                  <span>
                    <strong>
                      {draft.amount ? yen.format(draft.amount) : "金額を確認"}
                    </strong>
                    <small>
                      {draft.receipts.some(
                        (receipt) => receipt.status === "PENDING",
                      )
                        ? "アップロード待ち"
                        : "支払者と按分を確認"}
                    </small>
                  </span>
                  <span aria-hidden="true">›</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {selected ? (
        <form
          className={styles.confirmPanel}
          onSubmit={handleSubmit(submit)}
          noValidate
        >
          <h3 ref={confirmTitleRef} tabIndex={-1}>
            この支出を確定
          </h3>
          <p>{drafts.length}件中 1件を確認しています</p>
          <label htmlFor="confirm-amount">支出の総額</label>
          <div className={styles.amountField}>
            <span aria-hidden="true">¥</span>
            <input
              id="confirm-amount"
              type="number"
              inputMode="numeric"
              {...register("amount")}
              aria-describedby={
                errors.amount ? "confirm-amount-error" : undefined
              }
            />
          </div>
          {errors.amount ? (
            <p className={styles.error} id="confirm-amount-error" role="alert">
              {errors.amount.message}
            </p>
          ) : null}

          <label htmlFor="payer">立て替えた人</label>
          <select id="payer" {...register("payerId")}>
            {activeMembers.map((member) => (
              <option key={member.id} value={member.id}>
                {member.name}
              </option>
            ))}
          </select>

          <fieldset className={styles.presets}>
            <legend>按分プリセット</legend>
            <div>
              {(["all", "previous", "specified"] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={preset === value}
                  onClick={() => void applyPreset(value)}
                >
                  {value === "all"
                    ? "全員"
                    : value === "previous"
                      ? "前回と同じ"
                      : "金額を指定"}
                </button>
              ))}
            </div>
          </fieldset>
          {presetMessage ? (
            <p className={styles.notice} role="status">
              {presetMessage}
            </p>
          ) : null}

          <fieldset className={styles.members}>
            <legend>負担する人</legend>
            {activeMembers.map((member) => (
              <div className={styles.memberRow} key={member.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={selectedMemberIds.includes(member.id)}
                    onChange={(event) => {
                      setSelectedMemberIds((current) =>
                        event.target.checked
                          ? [...current, member.id]
                          : current.filter((id) => id !== member.id),
                      );
                      setSelectionError(undefined);
                    }}
                  />
                  {member.name}
                </label>
                {preset === "specified" &&
                selectedMemberIds.includes(member.id) ? (
                  <label>
                    <span className={styles.srOnly}>{member.name}の固定額</span>
                    <input
                      type="number"
                      inputMode="numeric"
                      min="0"
                      placeholder="残りは均等"
                      {...register(`fixedAmounts.${member.id}`)}
                    />
                  </label>
                ) : null}
              </div>
            ))}
          </fieldset>
          {selectionError ? (
            <p className={styles.error} role="alert">
              {selectionError}
            </p>
          ) : null}

          <output className={styles.perPerson} aria-live="polite">
            <span>1人あたり</span>
            <strong>
              {selectedMemberIds.length ? `${yen.format(perPerson)}〜` : "—"}
            </strong>
            <small>端数は確定時に自動調整されます</small>
          </output>

          {confirmation.error instanceof ExpenseApiError ? (
            <div className={styles.errorPanel} role="alert">
              <strong>
                {confirmation.error.status === 409
                  ? "ほかの端末で支出が更新されました"
                  : confirmation.error.message}
              </strong>
              {confirmation.error.status === 409 ? (
                <button
                  type="button"
                  onClick={() => void expenseQuery.refetch()}
                >
                  最新内容を読み込む
                </button>
              ) : null}
            </div>
          ) : null}
          <button
            className={styles.confirmAction}
            type="submit"
            disabled={confirmation.isPending}
          >
            {confirmation.isPending ? "確定しています…" : "確定して次へ"}
          </button>
        </form>
      ) : null}

      {confirmed.length ? (
        <section className={styles.confirmed} aria-labelledby="confirmed-title">
          <h3 id="confirmed-title">確定済み</h3>
          <ul>
            {confirmed.map((expense) => (
              <li key={expense.id}>
                <strong>
                  {yen.format(expense.baseAmount ?? expense.amount ?? 0)}
                </strong>
                <span>
                  1人あたり {yen.format(expense.shares[0]?.finalAmount ?? 0)}〜
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </section>
  );
}

export function FutureActionPage({
  kind,
}: {
  kind: "candidate" | "expense" | "settlement";
}) {
  const copy = {
    candidate: ["候補を追加", "候補追加はM3で接続します。"],
    expense: ["支出を記録", "支出記録はM5で接続します。"],
    settlement: ["精算する", "精算フローはM6で接続します。"],
  } as const;
  return (
    <section aria-labelledby="future-action-title">
      <p className={shellStyles.sectionLabel}>COMING NEXT</p>
      <h2 className={shellStyles.pageTitle} id="future-action-title">
        {copy[kind][0]}
      </h2>
      <div className={shellStyles.emptyState}>
        <p>{copy[kind][1]}</p>
      </div>
    </section>
  );
}
