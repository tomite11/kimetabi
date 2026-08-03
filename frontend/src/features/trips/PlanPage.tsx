import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { useState } from "react";
import { Link, useParams } from "react-router";

import type { components } from "../../api/generated/schema";
import {
  createSlot,
  reorderSlots,
  splitSlot,
  type TripSnapshot,
} from "../planning/planningApi";
import { slotSchema, type SlotFormValues } from "../planning/planningSchema";
import { useTripSnapshot } from "./TripShell";
import { tripKeys } from "./tripQueries";
import styles from "../planning/Planning.module.css";

type Slot = components["schemas"]["Slot"];

function SplitSlotControls({
  slot,
  pending,
  onSplit,
}: {
  slot: Slot;
  pending: boolean;
  onSplit: (slot: Slot, splitAfterDay: number) => void;
}) {
  const [splitAfterDay, setSplitAfterDay] = useState(slot.dayFrom);
  return (
    <span className={styles.splitControls}>
      <label>
        分割位置
        <select
          value={splitAfterDay}
          onChange={(event) => setSplitAfterDay(Number(event.target.value))}
        >
          {Array.from(
            { length: slot.dayTo - slot.dayFrom },
            (_, offset) => slot.dayFrom + offset,
          ).map((day) => (
            <option key={day} value={day}>
              DAY {day} の後
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        disabled={pending}
        onClick={() => onSplit(slot, splitAfterDay)}
      >
        この枠を分割
      </button>
    </span>
  );
}

export function PlanPage() {
  const snapshot = useTripSnapshot();
  const { tripId: value } = useParams();
  const tripId = Number(value);
  const queryClient = useQueryClient();
  const currentMember = snapshot.members.find(
    (member) => member.id === snapshot.currentMemberId,
  );
  const canManage =
    currentMember?.role === "OWNER" || currentMember?.role === "ORGANIZER";
  const planItemsBySlot = new Map(
    snapshot.planItems.map((planItem) => [planItem.slotId, planItem]),
  );
  const form = useForm<SlotFormValues>({
    resolver: zodResolver(slotSchema),
    defaultValues: {
      category: "ACTIVITY",
      title: "",
      dayFrom: 1,
      dayTo: 1,
      units: 1,
      estPerPerson: "",
    },
  });

  const updateSlots = (slots: Slot[], incrementTripVersion = false) =>
    queryClient.setQueryData<TripSnapshot>(
      tripKeys.snapshot(tripId),
      (current) =>
        current
          ? {
              ...current,
              trip: incrementTripVersion
                ? { ...current.trip, version: current.trip.version + 1 }
                : current.trip,
              slots: [...slots].sort((a, b) => a.sortOrder - b.sortOrder),
            }
          : current,
    );

  const createMutation = useMutation({
    mutationFn: (values: SlotFormValues) =>
      createSlot(tripId, {
        ...values,
        estPerPerson:
          values.estPerPerson === "" ? undefined : values.estPerPerson,
        sortOrder: snapshot.slots.length,
      }),
    onSuccess: (slot) => {
      updateSlots([...snapshot.slots, slot]);
      form.reset();
    },
  });
  const reorderMutation = useMutation({
    mutationFn: (slots: Slot[]) => reorderSlots(tripId, snapshot, slots),
    onSuccess: (slots) => updateSlots(slots, true),
  });
  const splitMutation = useMutation({
    mutationFn: ({
      slot,
      splitAfterDay,
    }: {
      slot: Slot;
      splitAfterDay: number;
    }) => splitSlot(tripId, slot, splitAfterDay, `${slot.title}（後半）`),
    onSuccess: () =>
      void queryClient.invalidateQueries({
        queryKey: tripKeys.snapshot(tripId),
      }),
  });

  const move = (index: number, offset: -1 | 1) => {
    const nextIndex = index + offset;
    if (nextIndex < 0 || nextIndex >= snapshot.slots.length) return;
    const next = [...snapshot.slots];
    [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
    reorderMutation.mutate(next);
  };

  return (
    <section aria-labelledby="plan-title">
      <div className={styles.titleRow}>
        <div>
          <p className={styles.sectionLabel}>JOURNEY TAPE</p>
          <h2 className={styles.pageTitle} id="plan-title">
            旅程
          </h2>
        </div>
        {canManage ? (
          <details className={styles.addPanel}>
            <summary>枠を追加</summary>
            <form
              onSubmit={form.handleSubmit((values) =>
                createMutation.mutate(values),
              )}
            >
              <label>
                種類
                <select {...form.register("category")}>
                  <option value="ACTIVITY">アクティビティ</option>
                  <option value="MEAL">食事</option>
                  <option value="LODGING">宿</option>
                  <option value="TRANSPORT">移動</option>
                  <option value="OTHER">その他</option>
                </select>
              </label>
              <label>
                枠の名前
                <input {...form.register("title")} />
              </label>
              {Object.keys(form.formState.errors).length > 0 ? (
                <p role="alert">
                  {form.formState.errors.title?.message ||
                    form.formState.errors.dayFrom?.message ||
                    form.formState.errors.dayTo?.message ||
                    form.formState.errors.units?.message ||
                    form.formState.errors.estPerPerson?.message ||
                    "入力内容を確認してください"}
                </p>
              ) : null}
              <div className={styles.formColumns}>
                <label>
                  開始日
                  <input type="number" {...form.register("dayFrom")} />
                </label>
                <label>
                  終了日
                  <input type="number" {...form.register("dayTo")} />
                </label>
              </div>
              <label>
                単位数
                <input type="number" {...form.register("units")} />
              </label>
              <label>
                1人あたり概算
                <input inputMode="numeric" {...form.register("estPerPerson")} />
              </label>
              <button disabled={createMutation.isPending} type="submit">
                追加する
              </button>
            </form>
          </details>
        ) : null}
      </div>

      {snapshot.slots.length ? (
        <ol className={styles.planList}>
          {snapshot.slots.map((slot, index) => {
            const planItem = planItemsBySlot.get(slot.id);
            return (
              <li key={slot.id}>
                <Link className={styles.slotLink} to={`${slot.id}`}>
                  <span>
                    DAY {slot.dayFrom}
                    {slot.dayTo > slot.dayFrom ? `–${slot.dayTo}` : ""}
                  </span>
                  <strong>{slot.title}</strong>
                  {planItem ? (
                    <span className={styles.confirmedPlan}>
                      確定予定: {planItem.title}
                    </span>
                  ) : null}
                  <small>
                    {slot.status === "DECIDED" ? "予定確定" : "候補を比べる"} ·{" "}
                    {slot.estPerPerson == null
                      ? "概算未設定"
                      : `${slot.estPerPerson.toLocaleString("ja-JP")}円 / 人`}
                  </small>
                </Link>
                {canManage ? (
                  <div
                    className={styles.slotActions}
                    aria-label={`${slot.title}の操作`}
                  >
                    <button
                      type="button"
                      disabled={index === 0 || reorderMutation.isPending}
                      onClick={() => move(index, -1)}
                    >
                      上へ
                    </button>
                    <button
                      type="button"
                      disabled={
                        index === snapshot.slots.length - 1 ||
                        reorderMutation.isPending
                      }
                      onClick={() => move(index, 1)}
                    >
                      下へ
                    </button>
                    {slot.category === "LODGING" &&
                    slot.dayTo > slot.dayFrom ? (
                      <SplitSlotControls
                        slot={slot}
                        pending={splitMutation.isPending}
                        onSplit={(target, splitAfterDay) =>
                          splitMutation.mutate({ slot: target, splitAfterDay })
                        }
                      />
                    ) : null}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ol>
      ) : (
        <div className={styles.emptyState}>
          <strong>旅程の枠がまだありません</strong>
          <p>
            {canManage
              ? "「枠を追加」から、最初に決めたいことを置きましょう。"
              : "幹事が枠を追加すると、ここに旅程が表示されます。"}
          </p>
        </div>
      )}
      {createMutation.isError ||
      reorderMutation.isError ||
      splitMutation.isError ? (
        <p className={styles.error} role="alert">
          操作を完了できませんでした。最新の旅程を再読み込みしてください。
        </p>
      ) : null}
    </section>
  );
}
