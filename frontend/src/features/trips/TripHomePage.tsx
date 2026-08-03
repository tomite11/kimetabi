import { useTripSnapshot } from "./TripShell";
import styles from "./TripShell.module.css";

const phaseHeading = {
  PLANNING: "決めることを、旅の順番に。",
  TRAVELING: "今日の旅を、身軽に記録。",
  SETTLING: "最後の割り勘まで、迷わず。",
} as const;

export function TripHomePage() {
  const { trip, slots, members } = useTripSnapshot();
  const activeSlots = slots.filter((slot) => slot.status === "OPEN");

  return (
    <section aria-labelledby="trip-home-title">
      <p className={styles.sectionLabel}>NEXT FOR YOU</p>
      <h2 id="trip-home-title" className={styles.pageTitle}>
        {phaseHeading[trip.phase]}
      </h2>
      <p className={styles.lead}>
        {members.filter((member) => member.status === "ACTIVE").length}人で、
        {trip.startsOn}から{trip.destination}へ。
      </p>

      {activeSlots.length > 0 ? (
        <ol className={styles.journeyList}>
          {activeSlots.slice(0, 3).map((slot) => (
            <li key={slot.id}>
              <span className={styles.routeDot} aria-hidden="true">
                {slot.title.slice(0, 1)}
              </span>
              <span>
                <strong>{slot.title}</strong>
                <small>
                  {slot.deadline ? `${slot.deadline}まで` : "締切なし"} ·{" "}
                  {slot.estPerPerson == null
                    ? "概算未設定"
                    : `${slot.estPerPerson.toLocaleString("ja-JP")}円 / 人`}
                </small>
              </span>
              <span className={styles.slotBadge}>
                {slot.adoptedCandidateId ? "決定済み" : "候補を募集"}
              </span>
            </li>
          ))}
        </ol>
      ) : (
        <div className={styles.emptyState}>
          <strong>旅程はまだありません</strong>
          <p>「旅程」タブから、最初の予定枠を作れます。</p>
        </div>
      )}
    </section>
  );
}
