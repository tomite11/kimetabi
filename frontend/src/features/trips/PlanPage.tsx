import { useTripSnapshot } from "./TripShell";
import styles from "./TripShell.module.css";

export function PlanPage() {
  const { slots } = useTripSnapshot();
  return (
    <section aria-labelledby="plan-title">
      <p className={styles.sectionLabel}>JOURNEY TAPE</p>
      <h2 className={styles.pageTitle} id="plan-title">
        旅程
      </h2>
      {slots.length ? (
        <ol className={styles.planList}>
          {slots.map((slot) => (
            <li key={slot.id}>
              <span>DAY {slot.dayFrom}</span>
              <strong>{slot.title}</strong>
              <small>
                {slot.status === "OPEN" ? "候補を決める" : "予定確定"} ·{" "}
                {slot.estPerPerson == null
                  ? "概算未設定"
                  : `${slot.estPerPerson.toLocaleString("ja-JP")}円 / 人`}
              </small>
            </li>
          ))}
        </ol>
      ) : (
        <div className={styles.emptyState}>
          <strong>予定を入れる枠がありません</strong>
          <p>枠の追加機能はM3-C1で接続します。</p>
        </div>
      )}
    </section>
  );
}
