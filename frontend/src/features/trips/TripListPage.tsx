import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";

import { CreateTripForm } from "./CreateTripForm";
import { tripListQuery } from "./tripQueries";
import styles from "./TripListPage.module.css";

export function TripListPage() {
  const tripsQuery = useQuery(tripListQuery);
  const [isCreating, setIsCreating] = useState(false);

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

  if (isCreating) {
    return <CreateTripForm onCancel={() => setIsCreating(false)} />;
  }

  if (tripsQuery.data.items.length > 0) {
    return (
      <section className={styles.page} aria-labelledby="trip-list-title">
        <div className={styles.listHeading}>
          <div>
            <p className={styles.eyebrow}>あなたの旅行</p>
            <h1 id="trip-list-title">旅行一覧</h1>
          </div>
          <button
            className={styles.compactAction}
            type="button"
            onClick={() => setIsCreating(true)}
          >
            旅行を作る
          </button>
        </div>
        <ul className={styles.tripList}>
          {tripsQuery.data.items.map((trip) => (
            <li key={trip.id}>
              <Link to={`/t/${trip.id}`}>
                <strong>{trip.title}</strong>
                <span>{trip.destination}</span>
                <span className={styles.tripDate}>
                  {trip.startsOn} – {trip.endsOn}
                </span>
              </Link>
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
      <h1 id="trip-list-title">旅行一覧</h1>
      <button
        className={styles.primaryAction}
        type="button"
        onClick={() => setIsCreating(true)}
      >
        旅行を作る
      </button>
    </section>
  );
}
