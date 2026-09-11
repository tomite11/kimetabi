# M8 クローズドβ完了プラン

更新日: 2026-09-11

## 進捗

| Phase | 内容 | 状態 | 完了条件 |
|---|---|---|---|
| 1 | CI品質ゲート復旧 | 完了 | 最新HEADのBackend・Frontend・Infrastructure CIが全成功 |
| 2 | Preview入力・権限確定 | ブロック | project、state、secret、通知先、originが承認済み |
| 3 | Preview基盤適用 | 未着手 | Terraform applyと全GCP resource確認が完了 |
| 4 | 同一Previewへの配備 | 未着手 | Frontend、Cloud Run、DB、Tasks等の疎通成功 |
| 5 | フェーズ1受け入れ検証 | 未着手 | 全受け入れ条件に自動または手動証跡あり |
| 6 | PWA・UI実機検証 | 未着手 | 品質レポートのPreview手動項目が全完了 |
| 7 | 運用・復旧演習 | 未着手 | backup restore、rollback、Outbox回復を実演 |
| 8 | M8完了判定 | 未着手 | 全合流条件を満たし重大・高指摘なし |

状態は `未着手`、`進行中`、`完了`、`ブロック` のいずれかとする。後続Phaseは直前Phaseの
完了を開始条件とし、例外的に並行する場合も各完了条件は省略しない。

## Phase 1: CI品質ゲート復旧

1. 最新CIの失敗ログとartifactを確認する。
2. 実API integration E2EをCI相当条件で再現し、旅行作成失敗の原因を特定する。
3. テスト隔離または製品コードを最小範囲で修正する。
4. Backend test、Frontend lint・typecheck・unit・通常／release／realtime／integration E2Eを実行する。
5. `code-review-excellence` で全差分をレビューし、重大・高指摘を修正して影響テストを再実行する。
6. commit・push後、GitHub Actionsの全job成功を確認する。

### 実施結果

- 完了日: 2026-09-11
- 対象commit: `c8a645c`
- 原因: Viteの開発用`/api` reverse proxyがbrowserの`Origin`を実APIへ転送し、Preview originだけを
  許可するBackendのCORS境界でintegration E2Eの旅行作成が`403`になっていた。
- 対応: 開発用`/api` proxyに限って転送元`Origin`を除去した。本番CORS設定とWebSocket境界は
  変更していない。
- ローカル: Backend 222件、Frontend unit 55件、通常E2E 5件、release E2E 16件、
  realtime E2E 1件、実API integration E2E 2件が成功。lint、typecheck、buildも成功。
- CI: [Frontend run 34562700813](https://github.com/tomite11/kimetabi/actions/runs/34562700813) の
  `quality`、`e2e`、`integration-e2e`が成功。BackendとInfrastructureは対象ファイルに変更がなく、
  それぞれ直近の該当workflow成功を維持している。
- レビュー: `/ws` proxyにも同じ処理を適用していた初期差分を高優先度指摘として修正。
  未解決の重大・高優先度指摘なし。

## Phase 2: Preview入力・権限確定

Google Cloud project、Firebase Hosting site、remote state bucket、環境名、Artifact Registry、
DB接続secret、異なる2系統の通知channel、許可preview origin、実行identityを確定する。
秘密値はGit、Terraform変数、shell履歴へ残さない。

### 現在のブロッカー

- `gcloud`の有効な認証は確認済みだが、既定project
  `project-47b92713-3093-43be-86b`（表示名`My First Project`）を本Previewへ使う承認がない。
- 承認済みのTerraform remote state bucketとprefixがない。
- Firebase Hosting site IDとpreview channel名がない。
- 本番およびpreviewの許可originが確定していない。
- emailとSlack等、異なる2系統のMonitoring notification channel IDがない。
- Secret ManagerへのDB secret version投入経路と、Terraform／deployの実行identityが確定していない。

上記を承認・提示された時点でPhase 2を再開する。秘密値そのものは提示せず、承認済みの
Secret Manager投入経路を指定する。

## Phase 3: Preview基盤適用

remote backend初期化、既存resource import、API・secret container先行作成、secret version登録、
digest固定imageの作成・登録、`terraform fmt`・`validate`・`plan`レビューを行う。plan承認後だけ
applyし、Cloud Run、SQL、Tasks、Storage、Scheduler、Monitoringを確認する。

## Phase 4: 同一Previewへの配備

Flyway V1〜V12を適用したBackendをCloud Runへ、production FrontendをFirebase Hostingの
preview channelへ配備する。health、Firebase匿名認証、CORS、CSP、SPA fallback、REST、
WebSocket、旅行snapshotをsmoke testする。

## Phase 5: フェーズ1受け入れ検証

旅行・招待、候補・投票・採択、URL非同期取得、DRAFT・offline再送、按分、精算・再精算、
REST／WebSocket認可、409、revision回復、タイムゾーンを同じPreviewで検証し、commit SHA、
環境、日時、結果、trace IDを記録する。

## Phase 6: PWA・UI実機検証

`doc/FRONTEND_QUALITY_REPORT.md` に従い、install／standalone、Service Worker更新、airplane mode、
実機camera fallback、360px・200% zoom、keyboard・focus、reduced motion、空状態、2 browser同期を
確認し、URL、端末、browser、実施者、日時、screenshotを記録する。

## Phase 7: 運用・復旧演習

on-demand backupを別Cloud SQL instanceへrestoreし、Flyway履歴と業務不変条件を照合する。
Cloud Run rollback、Outbox滞留からScheduler回復、alert通知、trace追跡を実演する。既存instanceを
上書きするrestoreや削除保護解除は行わない。

## Phase 8: M8完了判定

同一Previewで3レーンを検証済み、全受け入れ条件に証跡あり、復旧演習済み、全CI成功、
重大・高問題なしを確認する。関連文書と最終レビュー結果を更新してM8を完了とする。

## 停止条件

- CI必須品質ゲートが失敗している。
- Terraform planに意図しない削除・置換がある。
- remote state、billing、secret投入経路、通知先2系統のいずれかがない。
- 認可、金額、冪等性、データ復旧に重大・高問題がある。
- Preview以外の本番resource変更、既存DB上書き、削除保護解除が必要になる。
