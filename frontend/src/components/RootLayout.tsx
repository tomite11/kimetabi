import { Outlet } from "react-router";

import styles from "./RootLayout.module.css";

export function RootLayout() {
  return (
    <div className={styles.app}>
      <a className={styles.skipLink} href="#main-content">
        本文へ移動
      </a>
      <header className={styles.header}>
        <div className={styles.brand} aria-label="タビキメワリ">
          <span className={styles.brandMark} aria-hidden="true">
            旅
          </span>
          <span>タビキメワリ</span>
        </div>
        <p className={styles.tagline}>決める、旅する、割り勘する。</p>
      </header>
      <main className={styles.main} id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}
