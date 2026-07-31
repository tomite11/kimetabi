import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";

import { tripSnapshotQuery } from "./tripQueries";
import styles from "./TripListPage.module.css";

export function TripStartPage() {
  const { tripId: tripIdParam } = useParams();
  const tripId = Number(tripIdParam);
  const snapshotQuery = useQuery({
    ...tripSnapshotQuery(tripId),
    enabled: Number.isSafeInteger(tripId) && tripId > 0,
  });

  if (!Number.isSafeInteger(tripId) || tripId <= 0) {
    throw new Response("旅行が見つかりません", { status: 404 });
  }

  if (snapshotQuery.isPending) {
    return (
      <p className={styles.status} role="status">
        旅行を読み込んでいます…
      </p>
    );
  }

  if (snapshotQuery.isError) {
    return (
      <section className={styles.status} aria-labelledby="snapshot-error-title">
        <h1 id="snapshot-error-title">旅行を開けませんでした</h1>
        <button type="button" onClick={() => void snapshotQuery.refetch()}>
          再読み込み
        </button>
      </section>
    );
  }

  const { trip, slots } = snapshotQuery.data;

  return (
    <section className={styles.createdPage} aria-labelledby="created-title">
      <div className={styles.journey} aria-hidden="true">
        <span className={styles.journeyLine} />
        <span className={styles.journeyDot} />
      </div>
      <p className={styles.eyebrow}>旅の準備ができました</p>
      <h1 id="created-title">{trip.title}</h1>
      <p className={styles.destination}>{trip.destination}</p>
      <dl className={styles.tripFacts}>
        <div>
          <dt>日程</dt>
          <dd>
            {trip.startsOn} – {trip.endsOn}
          </dd>
        </div>
        <div>
          <dt>人数</dt>
          <dd>{trip.expectedMemberCount}人</dd>
        </div>
        <div>
          <dt>最初の旅程</dt>
          <dd>{slots.length}件</dd>
        </div>
      </dl>
      <p className={styles.createdNotice}>
        旅行ホームの詳しい計画機能は、次のステップで追加されます。
      </p>
      <Link className={styles.textLink} to="/">
        旅行一覧へ戻る
      </Link>
    </section>
  );
}
