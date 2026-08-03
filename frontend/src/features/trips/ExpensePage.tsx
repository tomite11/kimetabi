import styles from "./TripShell.module.css";

export function ExpensePage() {
  return (
    <section aria-labelledby="expenses-title">
      <p className={styles.sectionLabel}>TRIP EXPENSES</p>
      <h2 className={styles.pageTitle} id="expenses-title">
        支出
      </h2>
      <div className={styles.emptyState}>
        <strong>支出はまだありません</strong>
        <p>旅行中は、レシート写真または金額だけで素早く記録できます。</p>
      </div>
    </section>
  );
}

export function FutureActionPage({
  kind,
}: {
  kind: "candidate" | "expense" | "settlement";
}) {
  const copy = {
    candidate: ["候補を追加", "候補追加はM3で接続します。"],
    expense: ["支出を記録", "支出記録はM5で接続します。"],
    settlement: ["精算する", "精算フローはM6で接続します。"],
  } as const;
  return (
    <section aria-labelledby="future-action-title">
      <p className={styles.sectionLabel}>COMING NEXT</p>
      <h2 className={styles.pageTitle} id="future-action-title">
        {copy[kind][0]}
      </h2>
      <div className={styles.emptyState}>
        <p>{copy[kind][1]}</p>
      </div>
    </section>
  );
}
