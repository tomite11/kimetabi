import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { useNavigate } from "react-router";

import { createTrip, tripKeys } from "./tripQueries";
import { useCreateTripForm } from "./useCreateTripForm";
import styles from "./TripListPage.module.css";

function currentTimezone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Tokyo";
}

export function CreateTripForm({ onCancel }: { onCancel: () => void }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const form = useCreateTripForm();
  const submissionRef = useRef<{
    requestHash: string;
    idempotencyKey: string;
  } | null>(null);
  const mutation = useMutation({
    mutationFn: createTrip,
    onSuccess: (snapshot) => {
      queryClient.setQueryData(tripKeys.snapshot(snapshot.trip.id), snapshot);
      void queryClient.invalidateQueries({ queryKey: tripKeys.list() });
      void navigate(`/t/${snapshot.trip.id}`);
    },
  });

  const submit = form.handleSubmit((values) => {
    const request = {
      ...values,
      title: `${values.destination}の旅`,
      ownerName: "わたし",
      timezone: currentTimezone(),
      voteVisibility: "ANONYMOUS",
    } as const;
    const requestHash = JSON.stringify(request);

    if (submissionRef.current?.requestHash !== requestHash) {
      submissionRef.current = {
        requestHash,
        idempotencyKey: crypto.randomUUID(),
      };
    }

    mutation.mutate({
      request,
      idempotencyKey: submissionRef.current.idempotencyKey,
    });
  });

  return (
    <form
      className={styles.createForm}
      onSubmit={(event) => void submit(event)}
    >
      <div className={styles.formHeading}>
        <p className={styles.eyebrow}>新しい旅行</p>
        <h2>旅の輪郭を決める</h2>
        <p>日程・目的地・人数だけで始められます。</p>
      </div>

      <div className={styles.field}>
        <label htmlFor="destination">目的地</label>
        <input
          id="destination"
          autoComplete="address-level1"
          aria-describedby={
            form.formState.errors.destination ? "destination-error" : undefined
          }
          aria-invalid={Boolean(form.formState.errors.destination)}
          {...form.register("destination")}
        />
        {form.formState.errors.destination ? (
          <p className={styles.fieldError} id="destination-error">
            {form.formState.errors.destination.message}
          </p>
        ) : null}
      </div>

      <div className={styles.dateFields}>
        <div className={styles.field}>
          <label htmlFor="startsOn">出発日</label>
          <input
            id="startsOn"
            type="date"
            aria-describedby={
              form.formState.errors.startsOn ? "starts-on-error" : undefined
            }
            aria-invalid={Boolean(form.formState.errors.startsOn)}
            {...form.register("startsOn")}
          />
          {form.formState.errors.startsOn ? (
            <p className={styles.fieldError} id="starts-on-error">
              {form.formState.errors.startsOn.message}
            </p>
          ) : null}
        </div>
        <div className={styles.field}>
          <label htmlFor="endsOn">帰着日</label>
          <input
            id="endsOn"
            type="date"
            aria-describedby={
              form.formState.errors.endsOn ? "ends-on-error" : undefined
            }
            aria-invalid={Boolean(form.formState.errors.endsOn)}
            {...form.register("endsOn")}
          />
          {form.formState.errors.endsOn ? (
            <p className={styles.fieldError} id="ends-on-error">
              {form.formState.errors.endsOn.message}
            </p>
          ) : null}
        </div>
      </div>

      <div className={styles.field}>
        <label htmlFor="expectedMemberCount">人数</label>
        <div className={styles.memberCount}>
          <input
            id="expectedMemberCount"
            type="number"
            inputMode="numeric"
            min="1"
            max="100"
            aria-describedby={
              form.formState.errors.expectedMemberCount
                ? "member-count-error"
                : "member-count-hint"
            }
            aria-invalid={Boolean(form.formState.errors.expectedMemberCount)}
            {...form.register("expectedMemberCount", { valueAsNumber: true })}
          />
          <span aria-hidden="true">人</span>
        </div>
        <p className={styles.fieldHint} id="member-count-hint">
          あとで変更できます
        </p>
        {form.formState.errors.expectedMemberCount ? (
          <p className={styles.fieldError} id="member-count-error">
            1〜100人で入力してください
          </p>
        ) : null}
      </div>

      {mutation.isError ? (
        <p className={styles.submitError} role="alert">
          {mutation.error.message}
        </p>
      ) : null}

      <div className={styles.formActions}>
        <button
          className={styles.secondaryAction}
          type="button"
          onClick={onCancel}
          disabled={mutation.isPending}
        >
          戻る
        </button>
        <button
          className={styles.primaryAction}
          type="submit"
          disabled={mutation.isPending}
        >
          {mutation.isPending ? "作成しています…" : "この内容で旅行を作る"}
        </button>
      </div>
    </form>
  );
}
