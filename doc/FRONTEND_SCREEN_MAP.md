# M0-C1 フロントエンド画面・ルート契約

`doc/SPEC.md` 7章と `doc/screen-design.html` を実装へ落とすための画面契約である。
業務仕様は `doc/SPEC.md`、APIの要求・応答は `openapi/openapi.json` を正本とする。

## 共通シェル

- `AppShell` はヘッダー、旅程テープ、フェーズ表示、本文、固定3タブ
  （ホーム／旅程／支出）、画面下部の主アクション1つから成る。
- 色、タイポグラフィ、余白はプロトタイプの `--ink`、`--sky`、`--paper`、
  `--coral`、`--leaf`、`--sun` をCSS Custom Propertiesへ移す。
- タブは `NavLink`、操作は `button`、入力は関連する `label` を使う。カード全体を
  `tabindex` 付きクリック領域にせず、候補選択は名前を持つボタンまたはradio groupに
  する。ネストした操作を持つカードでも投票ボタンへ独立して到達できる。
- `:focus-visible` は3px以上の高コントラストな輪郭を常に表示する。モーダルは
  フォーカスを閉じ込め、閉じた後は起点へ戻す。通知は `aria-live` を使う。
- 640px以下は候補を縦1列、固定アクションを安全領域込みで表示する。比較表は
  4候補以上かつタブレット以上だけにし、モバイルで横スクロールを必須にしない。
- `prefers-reduced-motion: reduce` では旅程テープ、進捗、画面遷移のアニメーションを
  止める。状態の意味を動きだけで伝えない。

## ルート対応表

| URL | 画面／主な部品 | 読み取りAPI | 権限と主アクション | 空状態 |
|---|---|---|---|---|
| `/` | `TripListPage`, `TripCard`, `CreateTripDialog` | `listTrips` | 認証済みUID。主アクションは「旅行を作る」 | カードや説明を出さず「旅行を作る」だけ |
| `/t/:tripId` | `TripHomePage`, `JourneyTape`, `PhaseSummary` | `getTripSnapshot` | ACTIVEメンバー。フェーズに応じた主アクション | スロット未作成時は作成導線 |
| `/t/:tripId/plan` | `PlanPage`, `SlotList`, `SlotCard` | `getTripSnapshot` | 閲覧はACTIVE、編集はOWNER/ORGANIZER | 枠の作成導線 |
| `/t/:tripId/plan/:slotId` | `SlotPage`, `CandidateList`, `VoteControl` | `getSlot` | 閲覧・投票はACTIVE、採択はOWNER/ORGANIZER | 「URLを貼るか、共有から追加できます」＋同一画面の入力欄 |
| `/t/:tripId/expenses` | `ExpenseListPage`, `ExpenseSummary` | `listExpenses` | ACTIVEメンバー | 一覧枠を出さず、撮影／金額入力の大きな2操作 |
| `/t/:tripId/expenses/new` | `ExpenseDraftPage`, `ReceiptCapture`, `AllocationForm` | `getTripSnapshot` | ACTIVEメンバー | 未入力時も撮影と金額入力を等価に提示 |
| `/t/:tripId/settle` | `SettlementPage`, `TransferList` | `listSettlements` | 閲覧はACTIVE、作成・確定はOWNER/ORGANIZER | 対象支出なしを説明し支出タブへ戻す |
| `/join/:inviteToken` | `JoinPage`, `DisplayNameForm` | なし | 匿名認証後に `acceptInvitation`。tokenをログ・分析へ送らない | 無効・期限切れ・上限到達をProblem別に表示 |
| `/candidates/import` | `ShareImportPage`, `ImportConfirmForm` | `listTrips`, `getTripSnapshot` | フェーズ2。保存前に旅行・枠・URLを確認 | 対象の計画中旅行がなければ作成導線 |

旅行作成はURLを新設せず `/` 上のダイアログとして `createTrip` を呼ぶ。支出の確認・
編集も `/expenses/new` 内の段階表示とし、仕様にないルートを増やさない。メンバー管理の
URLは `doc/SPEC.md` に未定義のため、M2着手前に決定する必要がある。

## 画面状態とエラー

全画面は `initial loading`、`ready`、`empty`、`refreshing`、`offline snapshot`、
`recoverable error` を区別する。再取得中は既存内容を消さない。route error boundaryは
`401`（token更新後に元URLへ復帰）、`403`、`404`、`409`、その他Problemを区別する。
`409` は現在値とローカル編集を提示し、自動上書きしない。オフラインのスナップショット
には取得時刻を表示し、書き込みは送信済みになるまで「同期待ち」と表示する。

ローディング、エラー、空状態でも3タブの位置を変えない。ページタイトル、主見出し、
現在タブの `aria-current="page"` を維持し、キーボードのTab順は見た目の順と一致させる。
