import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
  useOutletContext,
  useParams,
} from "react-router";

import type { components } from "../../api/generated/schema";
import { useAuth } from "../../auth/AuthProvider";
import { MemberManager } from "./MemberManager";
import { updateTrip } from "./tripMutations";
import { tripKeys, tripSnapshotQuery } from "./tripQueries";
import styles from "./TripShell.module.css";

type TripSnapshot = components["schemas"]["TripSnapshot"];
type TripPhase = components["schemas"]["TripPhase"];

const phaseCopy: Record<
  TripPhase,
  { label: string; action: string; progress: string }
> = {
  PLANNING: { label: "計画中", action: "候補を追加", progress: "28%" },
  TRAVELING: { label: "旅行中", action: "支出を記録", progress: "67%" },
  SETTLING: { label: "精算", action: "精算する", progress: "92%" },
};

function actionTarget(tripId: number, phase: TripPhase) {
  if (phase === "PLANNING") return `/t/${tripId}/plan`;
  if (phase === "TRAVELING") return `/t/${tripId}/expenses/new`;
  return `/t/${tripId}/settle`;
}

export function TripShell() {
  const { tripId: value } = useParams();
  const tripId = Number(value);
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const memberTriggerRef = useRef<HTMLButtonElement>(null);
  const { isAnonymous } = useAuth();
  const snapshotQuery = useQuery({
    ...tripSnapshotQuery(tripId),
    enabled: Number.isSafeInteger(tripId) && tripId > 0,
  });
  const phaseMutation = useMutation({
    mutationFn: (phaseOverride: TripPhase | null) =>
      updateTrip(tripId, {
        version: snapshotQuery.data?.trip.version ?? 0,
        phaseOverride,
      }),
    onSuccess: (trip) => {
      queryClient.setQueryData<TripSnapshot>(
        tripKeys.snapshot(tripId),
        (current) => (current ? { ...current, trip } : current),
      );
    },
  });

  if (!Number.isSafeInteger(tripId) || tripId <= 0)
    throw new Response("旅行が見つかりません", { status: 404 });
  if (snapshotQuery.isPending)
    return <p role="status">旅行を読み込んでいます…</p>;
  if (snapshotQuery.isError)
    return (
      <section aria-labelledby="snapshot-error-title">
        <h1 id="snapshot-error-title">旅行を開けませんでした</h1>
        <button type="button" onClick={() => void snapshotQuery.refetch()}>
          再読み込み
        </button>
      </section>
    );

  const snapshot = snapshotQuery.data;
  const phase = phaseCopy[snapshot.trip.phase];
  const isExpenseRoute = location.pathname.startsWith(`/t/${tripId}/expenses`);
  const primaryAction = isExpenseRoute ? "支出を記録" : phase.action;
  const primaryTarget = isExpenseRoute
    ? `/t/${tripId}/expenses/new`
    : actionTarget(tripId, snapshot.trip.phase);
  return (
    <div className={styles.shell}>
      <header className={styles.tripHeader}>
        <div className={styles.headerRow}>
          <div>
            <p className={styles.tripKicker}>
              {snapshot.trip.destination} · {snapshot.trip.startsOn}
            </p>
            <h1>{snapshot.trip.title}</h1>
          </div>
          <MemberManager snapshot={snapshot} triggerRef={memberTriggerRef} />
        </div>
        <div className={styles.phaseLine}>
          <details>
            <summary className={styles.phasePill}>{phase.label}</summary>
            <div className={styles.phaseMenu}>
              {(["PLANNING", "TRAVELING", "SETTLING"] as const).map(
                (nextPhase) => (
                  <button
                    key={nextPhase}
                    type="button"
                    disabled={phaseMutation.isPending}
                    onClick={() => phaseMutation.mutate(nextPhase)}
                  >
                    {phaseCopy[nextPhase].label}
                  </button>
                ),
              )}
              <button
                type="button"
                disabled={phaseMutation.isPending}
                onClick={() => phaseMutation.mutate(null)}
              >
                日程に合わせる
              </button>
            </div>
          </details>
          <span className={styles.routeTrack} aria-hidden="true">
            <span style={{ width: phase.progress }} />
          </span>
          <span className={styles.phaseDate}>
            {snapshot.trip.phaseOverride
              ? "手動切替中"
              : snapshot.trip.timezone}
          </span>
        </div>
        {phaseMutation.isError && (
          <p className={styles.error} role="alert">
            フェーズを変更できませんでした。OWNERまたはORGANIZERだけが変更できます。
          </p>
        )}
      </header>

      {isAnonymous && (
        <aside
          className={styles.promotionNotice}
          aria-label="アカウント登録の案内"
        >
          <strong>旅への参加が完了しました</strong>
          <span>
            登録するまでこの端末でのみ有効です。機種変更前にアカウント登録をしてください。
          </span>
          <button
            type="button"
            disabled
            title="登録方法は今後のマイルストーンで接続します"
          >
            登録方法を見る
          </button>
        </aside>
      )}

      <div className={styles.tripMain} id="trip-content">
        <Outlet context={snapshot satisfies TripSnapshot} />
      </div>

      <footer className={styles.footer}>
        <nav className={styles.tabs} aria-label="旅行のメインナビゲーション">
          <NavLink end to={`/t/${tripId}`}>
            ホーム
          </NavLink>
          <NavLink to={`/t/${tripId}/plan`}>旅程</NavLink>
          <NavLink to={`/t/${tripId}/expenses`}>支出</NavLink>
        </nav>
        <button
          className={styles.primaryAction}
          type="button"
          onClick={() => void navigate(primaryTarget)}
        >
          {primaryAction}
        </button>
      </footer>
    </div>
  );
}

// Outlet context belongs to the shell and is consumed only by its route pages.
// eslint-disable-next-line react-refresh/only-export-components
export function useTripSnapshot() {
  return useOutletContext<TripSnapshot>();
}
