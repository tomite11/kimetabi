# M0-C3 主要E2Eシナリオ

Playwright/MSW実装時のシナリオ契約。API例は
`openapi/fixtures/m0-c3-api-examples.json` を使用し、生成した
`paths`／`components["schemas"]` 型を介して登録する。

| ID | シナリオ | 主なAPIと検証点 |
|---|---|---|
| E2E-JOIN-01 | 招待URLを開き匿名認証、表示名を入力して参加 | `acceptInvitation`; tokenをログへ出さず、失効Problemを区別 |
| E2E-RECOVER-01 | 復旧URLを新しい匿名UIDで開き、既存member参照を復旧 | `acceptMemberRecovery`; token秘匿、期限切れ・使用済み・UID競合・429を区別 |
| E2E-TRIP-01 | 空の旅行一覧から旅行を作成 | `listTrips`, `createTrip`; 空状態の操作は1つ、IANA TZを保持 |
| E2E-PLAN-01 | 空の枠へURL候補を追加し取得中から完了へ | `getSlot`, `createCandidate`; 入力欄は同一画面、PENDINGを可視化 |
| E2E-PLAN-02 | キーボードだけで候補を比較・投票・採択 | `putMyCandidateVote`, `adoptCandidate`; NO理由、権限、focus復帰 |
| E2E-EXP-01 | 金額のみ／写真のみからDRAFTを作成し確定 | `createExpenseDraft`, receipt upload APIs, `updateExpense`; 1円単位 |
| E2E-EXP-02 | オフライン作成を復帰後に一度だけ再送 | 同じIdempotency-Key、成功前にDexieから削除しない |
| E2E-SET-01 | 支出を固定して精算し送金状態を更新 | `createSettlementDraft`, `confirmSettlement`, `updateSettlementTransfer` |
| E2E-SYNC-01 | 通知重複、revision gap、切断・再接続 | `getTripSnapshot`; REST同期後に再購読 |
| E2E-CONFLICT-01 | 古いversionの更新が409になる | Problemの現在値を表示し自動上書きしない |
| E2E-A11Y-01 | 360px、200% zoom、Tab/矢印/Enter/Escape、reduced motion | focus可視、順序、live region、主操作を遮らない |
| E2E-EMPTY-01 | 旅行・候補・支出が0件 | SPEC 7.9の3空状態と固定3タブを確認 |

各成功シナリオに401/403/404、ネットワーク遅延、再読込を組み合わせる。旅行タイムゾーン
の日付境界（23:59/00:00）と端数を含む金額表示もテストする。比較表は4候補以上かつ
タブレット以上、モバイルは縦カードで同じ操作へ到達できることを確認する。
