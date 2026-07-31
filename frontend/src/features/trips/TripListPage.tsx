import { useQuery } from "@tanstack/react-query";

import { tripListQuery } from "./tripQueries";
import styles from "./TripListPage.module.css";

export function TripListPage() {
  const tripsQuery = useQuery(tripListQuery);

  if (tripsQuery.isPending) {
    return (
      <p className={styles.status} role="status">
        旅行を読み込んでいます…
      </p>
    );
  }

  if (tripsQuery.isError) {
    return (
      <section className={styles.status} aria-labelledby="trip-error-title">
        <h1 id="trip-error-title">旅行を読み込めませんでした</h1>
        <p>通信状態を確認して、もう一度お試しください。</p>
        <p className={styles.errorDetail}>{tripsQuery.error.message}</p>
        <button type="button" onClick={() => void tripsQuery.refetch()}>
          再読み込み
        </button>
      </section>
    );
  }

  if (tripsQuery.data.items.length > 0) {
    return (
      <section className={styles.page} aria-labelledby="trip-list-title">
        <p className={styles.eyebrow}>あなたの旅行</p>
        <h1 id="trip-list-title">旅行一覧</h1>
        <ul className={styles.tripList}>
          {tripsQuery.data.items.map((trip) => (
            <li key={trip.id}>
              <strong>{trip.title}</strong>
              <span>{trip.destination}</span>
            </li>
          ))}
        </ul>
      </section>
    );
  }

  return (
    <section className={styles.page} aria-labelledby="trip-list-title">
      <div className={styles.journey} aria-hidden="true">
        <span className={styles.journeyLine} />
        <span className={styles.journeyDot} />
      </div>
      <p className={styles.eyebrow}>旅のはじまり</p>
      <h1 id="trip-list-title">次の旅を、ここから一本につなごう。</h1>
      <p className={styles.description}>まずは新しい旅行を作ります。</p>
      <button className={styles.primaryAction} type="button" disabled>
        旅行を作る
      </button>
      <p className={styles.notice} role="status">
        旅行作成は次の実装ステップで利用できるようになります。
      </p>
    </section>
  );
}
