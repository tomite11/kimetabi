import { isRouteErrorResponse, Link, useRouteError } from "react-router";

import styles from "./RouteErrorPage.module.css";

export function RouteErrorPage() {
  const error = useRouteError();
  const status = isRouteErrorResponse(error) ? error.status : 500;

  return (
    <main className={styles.page} id="main-content">
      <p className={styles.status}>{status}</p>
      <h1>ページを表示できませんでした。</h1>
      <p>時間をおいてもう一度試すか、旅の一覧へ戻ってください。</p>
      <Link className={styles.link} to="/">
        旅の一覧へ戻る
      </Link>
    </main>
  );
}
