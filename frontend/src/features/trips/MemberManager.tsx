import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type RefObject } from "react";

import { ApiError } from "../../api/ApiError";
import type { components } from "../../api/generated/schema";
import {
  createInvitation,
  createRecoveryLink,
  removeMember,
  transferOwner,
} from "./tripMutations";
import { tripKeys } from "./tripQueries";
import styles from "./TripShell.module.css";

type TripSnapshot = components["schemas"]["TripSnapshot"];

function mutationMessage(error: Error) {
  if (error instanceof ApiError && error.status === 403)
    return "この操作はOWNERだけが行えます。";
  if (error instanceof ApiError && error.status === 409)
    return "旅行の状態が更新されています。閉じて再読み込みしてください。";
  return error.message;
}

async function copyLink(url: string) {
  await navigator.clipboard.writeText(url);
}

export function MemberManager({
  snapshot,
  triggerRef,
}: {
  snapshot: TripSnapshot;
  triggerRef: RefObject<HTMLButtonElement | null>;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const queryClient = useQueryClient();
  const [sharedLink, setSharedLink] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const tripId = snapshot.trip.id;
  const updateSnapshot = (next: TripSnapshot) =>
    queryClient.setQueryData(tripKeys.snapshot(tripId), next);
  const invite = useMutation({
    mutationFn: () => createInvitation(tripId),
    onSuccess: (link) => {
      setSharedLink(link.url);
      setNotice("招待リンクを作りました。7日以内に共有してください。");
    },
  });
  const recovery = useMutation({
    mutationFn: (memberId: number) => createRecoveryLink(tripId, memberId),
    onSuccess: (link) => {
      setSharedLink(link.url);
      setNotice("復旧リンクを作りました。24時間以内に本人へ共有してください。");
    },
  });
  const removal = useMutation({
    mutationFn: (memberId: number) =>
      removeMember(tripId, memberId, snapshot.trip.version),
    onSuccess: updateSnapshot,
  });
  const transfer = useMutation({
    mutationFn: (memberId: number) =>
      transferOwner(tripId, memberId, snapshot.trip.version),
    onSuccess: updateSnapshot,
  });
  const activeError =
    invite.error || recovery.error || removal.error || transfer.error;

  const close = () => dialogRef.current?.close();

  return (
    <>
      <button
        ref={triggerRef}
        className={styles.memberTrigger}
        type="button"
        onClick={() => dialogRef.current?.showModal()}
      >
        メンバー{" "}
        {snapshot.members.filter((member) => member.status === "ACTIVE").length}
        人
      </button>
      <dialog
        className={styles.memberDialog}
        ref={dialogRef}
        aria-labelledby="member-dialog-title"
        onClose={() => {
          setSharedLink(undefined);
          setNotice(undefined);
          triggerRef.current?.focus();
        }}
      >
        <div className={styles.dialogHeading}>
          <div>
            <p className={styles.sectionLabel}>TRAVEL CREW</p>
            <h2 id="member-dialog-title">メンバー</h2>
          </div>
          <button
            type="button"
            onClick={close}
            aria-label="メンバー管理を閉じる"
          >
            閉じる
          </button>
        </div>
        <ul className={styles.memberList}>
          {snapshot.members.map((member, index) => (
            <li key={member.id}>
              <span className={styles.avatar} aria-hidden="true">
                {member.name.slice(0, 1)}
              </span>
              <span>
                <strong>{member.name}</strong>
                <small>
                  {member.role} · 参加順 {index + 1}
                  {member.status !== "ACTIVE" ? ` · ${member.status}` : ""}
                </small>
              </span>
              {member.status === "ACTIVE" && (
                <details>
                  <summary>管理</summary>
                  <div className={styles.memberActions}>
                    <button
                      type="button"
                      onClick={() => recovery.mutate(member.id)}
                    >
                      復旧リンク
                    </button>
                    {member.role !== "OWNER" && (
                      <>
                        <button
                          type="button"
                          onClick={() => transfer.mutate(member.id)}
                        >
                          OWNERを移譲
                        </button>
                        <button
                          type="button"
                          onClick={() => removal.mutate(member.id)}
                        >
                          削除
                        </button>
                      </>
                    )}
                  </div>
                </details>
              )}
            </li>
          ))}
        </ul>
        <button
          className={styles.inviteAction}
          type="button"
          disabled={invite.isPending}
          onClick={() => invite.mutate()}
        >
          {invite.isPending ? "作成しています…" : "招待リンクを作る"}
        </button>
        {notice && <p role="status">{notice}</p>}
        {sharedLink && (
          <div className={styles.shareLink}>
            <input
              aria-label="共有するリンク"
              readOnly
              value={sharedLink}
              onFocus={(event) => event.currentTarget.select()}
            />
            <button
              type="button"
              onClick={() =>
                void copyLink(sharedLink).then(() =>
                  setNotice("リンクをコピーしました。"),
                )
              }
            >
              コピー
            </button>
          </div>
        )}
        {activeError && (
          <p className={styles.error} role="alert">
            {mutationMessage(activeError)}
          </p>
        )}
        <p className={styles.permissionNote}>
          招待・復旧・削除・OWNER移譲はOWNERだけが実行できます。
        </p>
      </dialog>
    </>
  );
}
