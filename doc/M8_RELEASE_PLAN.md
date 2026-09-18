# M8 クローズドβ完了プラン

更新日: 2026-09-18

## 進捗

| Phase | 内容 | 状態 | 完了条件 |
|---|---|---|---|
| 1 | CI品質ゲート復旧 | 完了 | 最新HEADのBackend・Frontend・Infrastructure CIが全成功 |
| 2 | Preview入力・権限確定 | 完了 | project、state、secret、通知先、originが承認済み |
| 3 | Preview基盤適用 | 完了 | Terraform applyと全GCP resource確認が完了 |
| 4 | 同一Previewへの配備 | 完了 | Frontend、Cloud Run、DB、Tasks等の疎通成功 |
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

### 確定結果

- 完了日: 2026-09-11
- Google Cloud project: `kimetabi-preview-998740556155`（表示名`Kimetabi Preview`、project number
  `507802978914`）。既定の別用途projectは流用せず、専用projectを作成して課金を有効化した。
- 環境名とリージョン: `preview`、`asia-northeast1`。
- Terraform remote state: bucket `kimetabi-preview-998740556155-tfstate`、prefix
  `kimetabi/preview`。uniform bucket-level access、public access prevention、versioningを有効化した。
- Firebase Hosting: site ID `kimetabi-preview-998740556155`、preview channel `closed-beta`。
  channelは30日TTLで作成し、後続deployで有効期限を更新する。
- 許可origin: 本番Frontend `https://tabikime.app`、Preview Frontend
  `https://kimetabi-preview-998740556155--closed-beta-ch7b7443.web.app`。Backend public base URLは
  `https://api.tabikime.app`とする。wildcard originは使用しない。
- Artifact Registry repository ID: `kimetabi-preview-backend`。
- Monitoring notification channel:
  - email: `projects/kimetabi-preview-998740556155/notificationChannels/1941396717245681851`
  - Slack: `projects/kimetabi-preview-998740556155/notificationChannels/10974268897495083492`
- Secret Manager container: `kimetabi-preview-database-url`、`kimetabi-preview-database-username`、
  `kimetabi-preview-database-password`。TerraformはcontainerとIAMだけを管理し、payloadはbootstrap
  担当者が`gcloud secrets versions add SECRET --data-file=-`で標準入力から登録する。payloadをGit、
  GitHub Variables／Secrets、Terraform変数・state、shell引数・履歴へ残さない。
- 実行identity:
  - 初回bootstrapとTerraform apply: `bay.take20@gmail.com`のADC。Preview専用projectだけを対象とする。
  - GitHub deploy: `kimetabi-preview-deploy@kimetabi-preview-998740556155.iam.gserviceaccount.com`。
    service account keyは作成せず、Workload Identity Provider
    `projects/507802978914/locations/global/workloadIdentityPools/github-actions/providers/kimetabi-preview`
    を使う。OIDCはGitHub repository ID `1318047987`（`tomite11/kimetabi`）、`main` branch、
    `preview` environmentの全条件へ限定した。project権限はArtifact Registryへのimage push、
    Cloud Run更新、Firebase Hosting deploy、service usageに限定し、Terraform apply権限とSecret
    payloadアクセス権限は付与しない。Cloud Run runtime service accountのactAs権限は、対象account
    作成後にそのaccountだけへ付与する。
- 上記の非秘密値はGitHub `preview` environment variablesへ登録した。DB秘密値は登録していない。

Phase 3ではremote backendを初期化し、Terraform管理対象の既存Firebase project／Hosting siteを
importしてからplanする。Hosting channelはTerraform管理対象外とし、Firebase Hosting側で維持する。
bootstrap済みのstate bucket、Workload Identity、deploy identityはTerraform管理へ重複追加しない。

## Phase 3: Preview基盤適用

remote backend初期化、既存resource import、API・secret container先行作成、secret version登録、
digest固定imageの作成・登録、`terraform fmt`・`validate`・`plan`レビューを行う。plan承認後だけ
applyし、Cloud Run、SQL、Tasks、Storage、Scheduler、Monitoringを確認する。

### 実施結果

- 完了日: 2026-09-18
- remote state: `kimetabi-preview-998740556155-tfstate` の `kimetabi/preview`。既存の
  Firebase project、Hosting site、Artifact Registry、必要API、Secret Manager containerをimportまたは
  先行適用した状態から再開した。DBの3 secretはすべてversion 1が有効で、payloadを
  Terraform stateやGitに保存していない。
- image: Artifact RegistryのBackend image
  `sha256:4061f40bae113c84fc6caf42349a2dc8ba36471543d5531ee50fd7b8376a225a`の存在を確認し、
  Cloud Runにdigest固定で適用した。
- plan: `31 to add, 0 to change, 0 to destroy`をresource単位でレビューし、意図しない
  削除・置換・本番projectの変更がないことを確認した。apply後の再planは`No changes`。
- Cloud Run: `kimetabi-preview-api`は`asia-northeast1`でReady、runtime service account、
  `min=0` / `max=1`を確認し、`/actuator/health/liveness`は`200` / `UP`だった。
- Cloud SQL: PostgreSQL 17、`db-g1-small`、ZONAL、backup 14件、`18:00 UTC`、PITR 7日、
  resourceとinstance設定の二重の削除保護を確認した。application databaseとuserは
  Secret Manager version 1と同じpasswordをwrite-only属性で設定した。
- Tasks・Storage・Scheduler: metadata queueはRUNNINGで仕様のretry/rate limit、receipt bucketは
  uniform access、public access prevention、許可済み2 originの`PUT` CORS、Outbox回復は5分周期、
  receipt cleanupは30分周期でいずれもENABLEDを確認した。
- Monitoring: Cloud Run 5xx、Outbox dispatch failure、Cloud SQL CPUの3 alertが有効で、
  それぞれemailとSlackの2 notification channelを持つことを確認した。
- IAM: runtime、Tasks、Schedulerを別identityとし、deploy identityの`actAs`は対象runtime
  service accountだけに付与した。runtimeのSecret読取りも3つのDB secretだけに限定した。
- レビュー: deploy identityが別projectのservice accountでも入力できる点を高優先度指摘として
  修正し、`project_id`内のidentityだけを許可するvalidationを追加した。未解決の重大・高優先度
  指摘はない。

## Phase 4: 同一Previewへの配備

Flyway V1〜V12を適用したBackendをCloud Runへ、production FrontendをFirebase Hostingの
preview channelへ配備する。health、Firebase匿名認証、CORS、CSP、SPA fallback、REST、
WebSocket、旅行snapshotをsmoke testする。

### 実施結果

- 完了日: 2026-09-18
- Backend: `linux/amd64` image
  `sha256:63638aa2c2469eb40e929f61c7556f3cd6f43abe4ff368ea6bf6d96d8221cd5c`を
  Artifact Registryへ登録し、Cloud Run revision `kimetabi-preview-api-00004-d8q`へdigest固定で
  配備した。100% traffic、health `200`を確認し、適用後のTerraform planは`No changes`だった。
- Database: 新revisionの起動ログで12 migrationの検証成功、schema version `12`、追加migration不要を
  確認した。Cloud SchedulerからOutbox回復endpointへのOIDC付きrequestも`200`だった。
- Frontend: production buildをFirebase Hosting preview channel `closed-beta`へ配備した。URLは
  `https://kimetabi-preview-998740556155--closed-beta-ch7b7443.web.app`、有効期限は
  2026-10-18 16:27 JSTである。SPAの深いrouteは`200`でfallbackした。
- Firebase Auth: Preview projectにWeb Appを登録し、Identity Platform API、匿名sign-in、Hostingと
  Previewの完全一致authorized domainをTerraform管理へ追加した。ブラウザから匿名ID tokenを取得し、
  `/api/session`が`200`となることを確認した。
- Browser smoke: Hosting URLから旅行を作成し、REST snapshot取得とreload後の再取得がともに`200`、
  WebSocket upgradeが`101`、STOMP `CONNECTED`が1回以上到達した。異なるoriginの`/ws`が当初
  `403`だったため、RESTと同じ完全一致CORS originをSTOMP handshakeにも適用し、回帰テストを追加した。
- Security headers: Preview originのCORS preflightが完全一致の`Access-Control-Allow-Origin`で`200`、
  CSPの`connect-src`はCloud Runの`https` / `wss` endpointを明示し、wildcardは使用していない。
  `X-Content-Type-Options: nosniff`と`X-Frame-Options: DENY`も確認した。
- 検証: Backend 223件、Frontend unit 55件が成功。Frontend lint、typecheck、Terraform fmt、
  validate、`git diff --check`も成功した。
- レビュー: WebSocket handshakeの許可originが未設定だった問題を高優先度指摘として修正し、
  Firebase deploy cacheを追跡対象外へ追加した。未解決の重大・高優先度指摘はない。
- 制約: `api.tabikime.app`はDNSとverified domainが未準備のため、今回のPreview buildはCloud Runの
  既定URLへ接続した。CSPと内部OIDC audienceには予定どおりcustom domainを残しており、公開前に
  DNSとCloud Run domain mappingを完了する。

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
