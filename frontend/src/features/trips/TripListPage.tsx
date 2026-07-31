import styles from "./TripListPage.module.css";

export function TripListPage() {
  return (
    <section className={styles.page} aria-labelledby="trip-list-title">
      <div className={styles.journey} aria-hidden="true">
        <span className={styles.journeyLine} />
        <span className={styles.journeyDot} />
      </div>
      <p className={styles.eyebrow}>旅のはじまり</p>
      <h1 id="trip-list-title">次の旅を、ここから一本につなごう。</h1>
      <p className={styles.description}>
        行き先を決めるところから、現地の支出、帰宅後の精算まで。
        まずは新しい旅行を作ります。
      </p>
      <button className={styles.primaryAction} type="button" disabled>
        旅行を作る
      </button>
      <p className={styles.notice} role="status">
        旅行作成は次の実装ステップで利用できるようになります。
      </p>
    </section>
  );
}
