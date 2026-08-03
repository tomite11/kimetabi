import { useMutation } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { Link, useParams } from "react-router";

import { ApiError } from "../../api/ApiError";
import { acceptRecovery } from "./invitationApi";
import styles from "./InvitationPage.module.css";

function recoveryError(error: Error) {
  if (!(error instanceof ApiError)) return "通信状態を確認してください。";
  if (error.status === 404)
    return "この復旧リンクは無効、期限切れ、または使用済みです。";
  if (error.status === 409)
    return "この端末の参加情報は、すでに別のメンバーに使われています。";
  if (error.status === 429)
    return "試行回数が上限に達しました。15分ほど待ってからお試しください。";
  return "メンバー情報を復旧できませんでした。";
}

export function RecoveryPage() {
  const { recoveryToken = "" } = useParams();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const mutation = useMutation({
    mutationFn: () => acceptRecovery({ token: recoveryToken }),
  });

  useEffect(() => headingRef.current?.focus(), []);

  if (mutation.isSuccess) {
    return (
      <section className={styles.page} aria-labelledby="recovery-done-title">
        <p className={styles.eyebrow}>RECOVERY COMPLETE</p>
        <h1 id="recovery-done-title">参加情報を復旧しました</h1>
        <p className={styles.lead}>
          {mutation.data.name}さんの参加情報を、この端末へ引き継ぎました。
        </p>
        <Link className={styles.linkButton} to="/">
          旅行一覧を開く
        </Link>
      </section>
    );
  }

  return (
    <section className={styles.page} aria-labelledby="recovery-title">
      <p className={styles.eyebrow}>MEMBER RECOVERY</p>
      <h1 id="recovery-title" ref={headingRef} tabIndex={-1}>
        旅の参加情報を、この端末へ。
      </h1>
      <p className={styles.lead}>
        幹事から受け取った復旧リンクを使って、以前のメンバー情報を引き継ぎます。
      </p>
      {mutation.isError && (
        <p className={styles.error} role="alert">
          {recoveryError(mutation.error)}
        </p>
      )}
      <button
        className={styles.recoveryAction}
        type="button"
        disabled={mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending ? "復旧しています…" : "参加情報を復旧する"}
      </button>
    </section>
  );
}
