# M0-C2 フロントエンド状態設計

API型の正本は `openapi/openapi.json` とし、M1-C2で
`frontend/src/api/generated/schema.d.ts` へ生成する。API呼び出しは
`openapi-fetch` の `paths`、表示モデルは `components["schemas"]` を参照し、同名の
手書きDTOを作らない。MSW fixtureも生成型に `satisfies` させる。

## 所有境界

| 状態 | 所有者 | 例 |
|---|---|---|
| REST応答 | TanStack Query | Trip、SlotDetail、ExpensePage、SettlementPage |
| 入力途中 | React Hook Form＋Zod | 旅行、候補、投票理由、支出、按分 |
| 永続オフライン | Dexie.js | UID別snapshot、未送信操作、レシートBlob |
| 画面内一時状態 | React state | ダイアログ開閉、選択候補、段階表示 |
| URLで共有すべき状態 | React Router | tripId、slotId、現在画面 |

loaderはパラメータと認証を検証してQueryをpreloadするだけとし、取得データを別管理しない。
代表Query Keyは `["trips"]` (`listTrips`)、`["trip", tripId]`
(`getTripSnapshot`)、`["trip", tripId, "slot", slotId]` (`getSlot`)、
`["trip", tripId, "expenses", cursor]` (`listExpenses`)、
`["trip", tripId, "settlements", cursor]` (`listSettlements`) とする。

mutation成功時はOpenAPI応答でキャッシュを更新し、関係Queryをinvalidateする。
`version` を更新要求へ、生成済みUUIDを `Idempotency-Key` へ渡す。Problem Detailsの
`fieldErrors` をフォームへ対応付け、業務規則の最終判定はサーバーに委ねる。

## Dexie契約

- `snapshots`: `[firebaseUid+tripId]`, `tripRevision`, `schemaVersion`,
  `fetchedAt`, `body`
- `pendingOperations`: `idempotencyKey`, `[firebaseUid+tripId]`,
  `operationId`, `resourceId`, `expectedVersion`, `createdAt`, `state`,
  `attemptCount`, `lastProblem`, `body`
- `receiptBlobs`: `localId`, `[firebaseUid+tripId]`, `expenseId`,
  `contentType`, `size`, `blob`

保存値は永続化境界で生成型に対応するZod schemaにより再検証する。UID変更時は他UIDの
内容を表示・送信しない。API応答をCache Storageへ入れず、Service Workerはアプリシェル
と静的assetだけをprecacheする。

未送信操作は `queued → sending → succeeded`、または `needs-auth`、
`conflict`、`needs-fix` へ遷移する。成功応答を確認した後だけ操作と不要Blobを削除する。
起動、online、画面focusで前景再送し、対応ブラウザではBackground Syncを補助に使う。
`401` はtoken更新後に再試行、`409` は競合UI、恒久的4xxは修正待ち、5xx/timeoutは
指数backoffとする。同一trip内は作成順に送り、依存する操作を追い越さない。

## リアルタイム回復

WebSocketはデータを保持せず通知として扱う。同じ `eventId` は無視する。
`tripRevision` が現在値+1なら対象Queryを再取得し、重複・古いrevisionは無視する。
gapまたは再接続では `getTripSnapshot` によるREST同期を完了してから購読する。画面に未送信編集が
ある場合は上書きせず競合候補として保持する。
