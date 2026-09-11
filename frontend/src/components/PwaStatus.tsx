import { useSyncExternalStore } from "react";

import {
  activatePwaUpdate,
  getPwaState,
  subscribeToPwaState,
} from "../pwa/pwaLifecycle";
import styles from "./PwaStatus.module.css";

export function PwaStatus() {
  const { offline, updateAvailable } = useSyncExternalStore(
    subscribeToPwaState,
    getPwaState,
    getPwaState,
  );

  if (!offline && !updateAvailable) return null;

  return (
    <aside className={styles.status} aria-live="polite" aria-atomic="true">
      {offline ? (
        <div>
          <strong>オフラインです</strong>
          <span>入力内容は端末に保管し、接続が戻ると再送します。</span>
        </div>
      ) : null}
      {updateAvailable ? (
        <div>
          <strong>新しいバージョンがあります</strong>
          <span>未送信の操作は保ったまま更新できます。</span>
          <button type="button" onClick={() => void activatePwaUpdate()}>
            アプリを更新
          </button>
        </div>
      ) : null}
    </aside>
  );
}
