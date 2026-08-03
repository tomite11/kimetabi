import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { useNavigate, useParams } from "react-router";

import { ApiError } from "../../api/ApiError";
import { useAuth } from "../../auth/AuthProvider";
import { tripKeys } from "../trips/tripQueries";
import { acceptInvitation } from "./invitationApi";
import { displayNameSchema, type DisplayNameValues } from "./invitationSchema";
import styles from "./InvitationPage.module.css";

function invitationError(error: Error) {
  if (!(error instanceof ApiError)) return "通信状態を確認してください。";
  if (error.status === 404)
    return "この招待は無効、期限切れ、または使用済みです。";
  if (error.status === 429)
    return "試行回数が上限に達しました。15分ほど待ってからお試しください。";
  if (error.status === 422) return "名前を確認して、もう一度お試しください。";
  return "招待を受け取れませんでした。時間をおいてお試しください。";
}

export function JoinPage() {
  const { inviteToken = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isAnonymous } = useAuth();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const form = useForm<DisplayNameValues>({
    resolver: zodResolver(displayNameSchema),
    defaultValues: { name: "" },
  });
  const mutation = useMutation({
    mutationFn: acceptInvitation,
    onSuccess: (snapshot) => {
      queryClient.setQueryData(tripKeys.snapshot(snapshot.trip.id), snapshot);
      void queryClient.invalidateQueries({ queryKey: tripKeys.list() });
      void navigate(`/t/${snapshot.trip.id}`, {
        replace: true,
        state: { joinedAsGuest: isAnonymous },
      });
    },
  });

  useEffect(() => headingRef.current?.focus(), []);

  const onSubmit = form.handleSubmit((values) => {
    mutation.mutate({ token: inviteToken, name: values.name.trim() });
  });

  return (
    <section className={styles.page} aria-labelledby="join-title">
      <p className={styles.eyebrow}>TRAVEL INVITATION</p>
      <h1 id="join-title" ref={headingRef} tabIndex={-1}>
        名前だけで、旅に合流。
      </h1>
      <p className={styles.lead}>
        ログインは不要です。旅行のメンバーに表示する名前を入力してください。
      </p>
      <form className={styles.form} onSubmit={onSubmit} noValidate>
        <label htmlFor="display-name">表示名</label>
        <input
          id="display-name"
          autoComplete="nickname"
          aria-invalid={Boolean(form.formState.errors.name)}
          aria-describedby={
            form.formState.errors.name ? "display-name-error" : undefined
          }
          {...form.register("name")}
        />
        {form.formState.errors.name && (
          <p className={styles.error} id="display-name-error">
            {form.formState.errors.name.message}
          </p>
        )}
        {mutation.isError && (
          <p className={styles.error} role="alert">
            {invitationError(mutation.error)}
          </p>
        )}
        <button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "参加しています…" : "この名前で参加する"}
        </button>
      </form>
      <aside className={styles.deviceNotice}>
        <strong>この端末に参加情報を保存します</strong>
        <p>
          登録するまでこの端末でのみ有効です。参加後に、機種変更に備える登録方法をご案内します。
        </p>
      </aside>
    </section>
  );
}
