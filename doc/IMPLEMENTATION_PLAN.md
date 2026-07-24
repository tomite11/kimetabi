# タビキメワリ 実装プラン

作成日: 2026-07-24

対象仕様: `doc/SPEC.md`（最終更新 2026-07-24）

対象デザイン: `doc/screen-design.html`

想定体制: 3人

## 1. 目的

本書は、`doc/SPEC.md` のMVPを、クリーンなチェックアウトから開発・検証・デプロイできる状態まで段階的に実装するための計画である。

開発は「バックエンドを全て作ってからフロントエンドを接続する」のではなく、次の利用者フローを縦に通す単位で進める。

1. 旅行を作り、メンバーを招待する
2. 枠へ候補を追加し、比較・投票・採択する
3. 支出を素早く記録し、後から確定する
4. 負担額を計算し、精算を完了する

MVPは国内旅行・日本円のみを対象とする。OCR、多通貨、為替換算、送金リンクはコアの一周が成立した後に扱う。

## 2. 実装原則

- 業務仕様と受け入れ条件の正本は `doc/SPEC.md` とする。
- UIは `doc/screen-design.html` の配色、タイポグラフィ、余白、旅程テープ、フェーズ表示、3タブ、画面下部の主アクションを基準にする。
- API契約はバックエンドが公開するOpenAPIを正本とし、フロントエンドにDTOを重複定義しない。
- 事前支払いと現地支出は共通の `expense` / `expense_share` で処理する。
- 更新はREST、リアルタイム通信はコミット済み変更の通知に限定する。
- 変更系APIは旅行内認可、楽観ロック、監査、冪等性をサービス層で一貫して扱う。
- 認証付きAPIレスポンスをCache Storageへ保存しない。
- オフライン操作は成功確認前にDexie.jsから削除しない。
- 各マイルストーンは、実装、テスト、OpenAPI更新、必要な文書更新を含めて完了とする。

## 3. 対象範囲

### 3.1 MVPで実装するもの

- Firebase匿名認証、アカウント昇格、旅行内ロール認可
- 旅行作成、フェーズ判定、初期枠・概算・締切の生成
- 枠の追加、削除、並べ替え、分割
- URLによる候補作成、非同期OGP取得、手入力フォールバック
- 候補比較、仮選択、予算シミュレーション、3値投票、採択
- 招待URL、参加、退出、OWNER移譲、論理削除
- 写真だけの支出DRAFT、金額だけの支出、未確定トレイ
- EQUALおよび「前回と同じ」を含む支出按分、支出確定
- 精算案作成、精算確定、送金状態、再精算
- REST再同期を伴うWebSocket通知
- PWAの基本構成、必要最小限のオフライン再送
- Firebase Hosting、Cloud Run、Cloud SQL等へのクローズドβ向け配備

### 3.2 MVP後に実装するもの

- Web Share Targetの本対応
- JSON-LDの再帰走査
- Cloud VisionによるOCRと金額抽出
- オフライン利用範囲の拡張とBackground Syncの強化
- メタデータキャッシュ、候補・支出の重複警告
- 送金リンク、リマインド
- `DECIDE_LOCALLY`

### 3.3 対象外

- 多通貨、為替換算
- 品目単位の割り勘
- 厳密な最小送金回数の探索
- 旅行横断の分析、通知センター
- Kafka、Kubernetes、JobRunr、Firestore、SockJS

## 4. リポジトリ構成

```text
/
├── README.md
├── frontend/
│   ├── src/
│   │   ├── components/       # 共通UI
│   │   ├── features/         # trip, candidate, expense, settlement等
│   │   ├── routes/           # React Router Data Mode
│   │   ├── api/              # OpenAPI生成物とopenapi-fetch設定
│   │   ├── offline/          # Dexie、未送信操作、再送制御
│   │   └── test/
│   └── e2e/
├── backend/
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   └── db/migration/     # Flywayマイグレーション
│   └── src/test/java/
└── doc/
    ├── SPEC.md
    ├── screen-design.html
    └── IMPLEMENTATION_PLAN.md
```

バックエンドは機能単位のパッケージを基本とし、`trip`、`candidate`、`expense`、`settlement`、`identity`、`sync`、`ingestion` に分ける。各機能内でHTTP、application、domain、persistenceの責務を分離し、精算計算や按分計算をSpringやDBへ依存しない純粋なドメインロジックとしてテストできるようにする。

## 5. 実装マイルストーン

### M0. 仕様の実装可能性を確定する

**目的:** 実装途中のデータモデル変更と受け入れ条件の解釈違いを減らす。

**作業**

- 主要フローごとに、画面、API、権限、状態遷移、監査対象を対応付ける。
- OpenAPIの初版を作り、Problem Details、ページング、`version`、`Idempotency-Key` の共通形式を決める。
- `SPEC.md` のDDLをそのまま写すのではなく、外部キー、索引、列挙値、作成・更新日時を含む実スキーマへ落とす。
- 少なくとも次の不足列・テーブルを設計する。
  - 旅行の投票公開設定
  - 候補のメモ、自由タグ、作成者
  - DRAFTの作成者と画像アップロード情報
  - 確定支出の変更履歴
  - 招待トークンと復旧トークン
  - 冪等キーと保存済みレスポンス
  - 支出ごとの確定負担額
  - 精算時点へ支出・既払送金を固定するための関連
- 下記「仕様確認事項」を解消し、判断を `SPEC.md` に反映する。
- `screen-design.html` の4画面をルートとコンポーネントへ分解した画面一覧を作る。

**完了条件**

- スキーマ、OpenAPI、状態遷移に、フェーズ1の受け入れ条件を表現できない欠落がない。
- セキュリティ上重要なURL取得、招待、内部API、WebSocketの脅威境界が明文化されている。

### M1. 開発基盤を構築する

**バックエンド**

- Spring Boot、Java、Maven Wrapper、PostgreSQL、Flywayを設定する。
- Testcontainersを利用したDB結合テスト基盤を作る。
- Spring Security、Firebase Admin SDKの検証境界、テスト用Principalを用意する。
- Problem Details、入力検証、trace ID、構造化ログの共通処理を作る。
- Actuatorのヘルスチェックを公開し、管理系エンドポイントは保護する。

**フロントエンド**

- React、TypeScript strict、Vite、React Router Data Modeを設定する。
- TanStack Query、React Hook Form、Zod、Dexie.js、`openapi-fetch` を導入する。
- Vitest、React Testing Library、MSW、Playwrightを設定する。
- CSS ModulesとCSS Custom Propertiesでデザイントークン、基本レイアウト、フォーカス表示、reduced motionを実装する。
- manifest、アプリシェルprecache、オフラインフォールバックを設定する。認証APIはruntime cache対象外にする。

**共通**

- ローカル用PostgreSQLと必要なエミュレーターの起動方法を整備する。
- `README.md` に `./mvnw spring-boot:run`、`./mvnw test`、`npm run dev`、`npm test`、`npm run lint`、`npm run typecheck`、`npm run test:e2e` を記載する。
- CIでバックエンドテスト、フロントテスト、lint、型検査、ビルドを実行する。

**完了条件**

- クリーンなチェックアウトから全品質ゲートを実行できる。
- フロントエンドが生成済みAPI型を使って、認証済みのヘルス確認相当のAPIへ接続できる。

### M2. 認証、旅行、メンバーの縦切りを通す

**バックエンド**

- `trip`、`trip_member`、招待・復旧トークンをマイグレーションする。
- Firebase IDトークンを `AppPrincipal` へ変換する。
- 旅行作成、一覧、スナップショット、更新、フェーズ上書きAPIを実装する。
- 旅行作成と同一トランザクションでOWNERと初期枠を作る。
- 招待発行、匿名参加、再訪時の復帰、アカウント昇格後の同一UID維持を実装する。
- OWNER移譲、退出、削除、未精算残高による制約を実装する。
- `TripAuthorizationService` でロール表を一元管理する。

**フロントエンド**

- 旅行一覧、旅行作成、招待受取、名前入力、メンバー管理を実装する。
- 旅行タイムゾーンに基づくフェーズ表示と手動上書きを実装する。
- 3タブとフェーズ別主アクションのアプリシェルを実装する。
- 匿名アカウントの端末依存に関する注意と昇格導線を表示する。

**重点テスト**

- 期限切れ、失効済み、使用済み招待トークン
- 非メンバーとロール別のREST認可
- OWNER移譲前の退出拒否、論理削除後の参照維持
- IANAタイムゾーン境界、日帰り、`phase_override`
- 同名メンバーと同一UIDによる再アクセス

**完了条件**

- 新規ユーザーがログイン画面を経ず、旅行作成または招待参加後に旅行ホームを表示できる。

### M3. 枠、候補、投票、採択を実装する

**バックエンド**

- `slot`、`candidate`、`candidate_vote`、`plan_item` をマイグレーションする。
- 宿・往路・復路の初期生成、概算、締切クランプを実装する。
- 枠の追加、削除、並べ替え、分割を実装する。
- 候補作成・更新、3値投票、候補採択・変更・解除を実装する。
- `expected_member_count` を使う予算シミュレーションを実装する。
- 全更新に `version` と `409 Conflict` を適用する。
- 採択時に枠更新と `plan_item` 作成を同一トランザクションで処理する。

**フロントエンド**

- 計画ホーム、旅程、枠詳細、候補カード、空状態を実装する。
- 縦積みカード、仮選択、基準との差額、固定予算バーを実装する。
- 3値投票と「むり」の理由必須入力、未投票表示を実装する。
- OWNER/ORGANIZERだけに採択操作を提示する。
- 競合時に現在値を示し、再読み込みまたは再入力を選べるUIを実装する。

**重点テスト**

- 招待人数が変わっても予定人数が同じなら1人あたり金額が不変
- `PER_PERSON` / `TOTAL`、泊数・units、端数
- MEMBERによる採択拒否
- 別旅行・別枠候補の採択拒否
- 確定支出が紐づく採択変更・解除
- 同時投票・同時採択の`409 Conflict`
- キーボード操作、フォーカス順、モバイルと広幅画面

**完了条件**

- 旅行作成から候補追加、比較、投票、採択、確定予定表示までを一周できる。

### M4. URL候補取り込みと非同期基盤を実装する

**バックエンド**

- `outbox_event` と冪等リクエスト保存をマイグレーションする。
- 候補作成、`trip.revision` 加算、Outbox登録を同一トランザクションにする。
- Outbox dispatch、Cloud Tasks登録、内部OIDC認証を実装する。
- OGP、HTML title、faviconの順でメタデータを抽出する。
- URL正規化、80/443制限、IP分類、毎リダイレクト検証、本文・時間上限を実装する。
- 一時障害と恒久障害を分類し、`metadata_status` を更新する。
- ユーザー編集を遅延メタデータで上書きしない。

**フロントエンド**

- URL貼付から候補を即時表示し、PENDING、失敗、再取得、手入力を表現する。
- `Idempotency-Key` を操作作成時に生成し、通信再試行でも再利用する。
- メタデータ完了通知または再取得でカードを更新する。

**重点テスト**

- 外部サイトの遅延・障害と3秒以内の候補作成レスポンス
- localhost、private、link-local、metadata endpoint、IPv4/IPv6表現、DNS変更、リダイレクト先
- 2MB超過、リダイレクト過多、不正scheme、不正port
- OutboxとCloud Tasksの重複配送
- 同じ冪等キーによる候補の二重作成防止
- メタデータ完了とユーザー編集の競合

**完了条件**

- 外部取得の成否にかかわらず候補が失われず、利用者が手入力で先へ進める。

### M5. 支出DRAFT、画像、按分を実装する

**バックエンド**

- `expense`、`expense_share`、支出変更履歴をマイグレーションする。
- 写真だけのDRAFT作成、画像アップロード、金額だけの作成を実装する。
- Cloud StorageはオブジェクトキーだけをDBへ保存し、認可された期限付きアップロード経路を用意する。
- DRAFTからCONFIRMEDへの状態遷移と業務制約を同一トランザクションで検証する。
- EQUAL、WEIGHT、FIXED_AND_WEIGHTと最大剰余方式を実装する。
- 「前回と同じ」の負担者プリセットを旅行内の直近確定支出から取得する。
- 支出一覧をカーソルページングで提供する。

**フロントエンド**

- 旅行中ホーム、撮影、金額入力、未確定トレイ、連続確定フローを実装する。
- 画像を長辺約1600pxへ圧縮し、Blobと未送信操作をDexie.jsへ保存する。
- 支払者は自分、按分は全員を初期値とし、1人あたり金額を即時表示する。
- アップロード待ち、失敗、恒久エラー、競合を隠さず表示する。
- アプリ起動、オンライン復帰、画面再フォーカス時に再送する。

**重点テスト**

- 写真だけのDRAFT
- 必須値、負担者0人、他旅行メンバー、負数、weight合計0
- 固定額超過と、按分後合計が必ず支出額と一致すること
- 同じ入力に対する端数配分の決定性
- 同じ冪等キーの反復送信と、成功前にローカル操作を消さないこと
- `401`更新、`409`競合、恒久的4xxのキュー保持
- カメラ非対応端末、ファイル選択、画像なしの手入力

**完了条件**

- オフラインで撮影または金額入力を開始しても操作を失わず、オンライン復帰後に重複のない支出として確定できる。

### M6. 精算を実装する

**バックエンド**

- `settlement`、`settlement_transfer` と精算対象のスナップショット関係をマイグレーションする。
- 確定支出からメンバー別の立替額・負担額・残高を計算する。
- 貪欲法で送金回数を抑えたtransferを生成する。
- DRAFT、CONFIRMED、COMPLETED、SUPERSEDEDの遷移を実装する。
- 支出追加後も確定済み精算を変更せず、差分の存在と新しい精算案を返す。
- 既払送金を再精算へ織り込み、送金元のPAIDと送金先のCONFIRMEDを認可する。

**フロントエンド**

- 支出集計、精算案、確定確認、送金状態、受取確認を実装する。
- 「最小送金」ではなく「送金回数を抑えた精算」と表記する。
- 未反映変更と再計算導線、旧精算の履歴を表示する。

**重点テスト**

- 全残高の合計が0
- transfer合計と債務残高合計の一致
- 残高非0メンバー数−1回以内
- 1人、全員0円、同額、小額、多数メンバー
- 精算確定と支出更新の競合
- 支払者・受取者・管理者代理操作の認可と監査
- 支払済みtransferを含む再精算

**完了条件**

- 確定支出から精算完了まで進め、後発の支出を既存精算の改変なしに再精算できる。

### M7. リアルタイム同期と欠落回復を完成させる

**バックエンド**

- STOMP接続時にFirebase IDトークンを検証する。
- SUBSCRIBE時にACTIVEメンバーであることを検証する。
- `/topic/trip/{tripId}` へOutboxから少なくとも1回配信する。
- 全業務更新で `trip.revision` を同一トランザクション内で加算する。
- Cloud Scheduler用のOutbox回復APIを内部OIDC認証で保護する。

**フロントエンド**

- `@stomp/stompjs` で旅行表示中だけ接続する。
- 自分のREST成功結果で即時更新し、WebSocket待ちにしない。
- `eventId` の重複排除、revision gap検出、対象Queryの再取得を実装する。
- 再接続時はRESTスナップショット同期後に購読する。

**重点テスト**

- 非メンバーによるCONNECT/SUBSCRIBE拒否
- イベントの重複、順序逆転、欠落
- REST成功直後の同一イベント受信
- Cloud Run接続切断後の復旧
- ブラウザ2台での投票、採択、支出、送金状態の反映

**完了条件**

- WebSocketを一時停止しイベントを欠落させても、再接続後にサーバーの最新状態へ収束する。

### M8. PWA、E2E、クローズドβ配備を仕上げる

**作業**

- installable manifest、アイコン、起動URL、表示モードを検証する。
- Service Worker更新時の安全な切替と、古いクライアントへの案内を実装する。
- Firebase HostingのSPA fallback、プレビューチャネル、CSP等のヘッダーを設定する。
- Cloud Run、Cloud SQL、Cloud Tasks、Cloud Storage、Secret Manager、Artifact Registry、Cloud SchedulerをIaCで定義する。
- Cloud Runは東京、request-based billing、min 0、max 1で開始する。
- Cloud SQLのバックアップ、PITR、削除保護を設定する。
- CORSを本番および許可したプレビュードメインへ限定する。
- ログにBearer token、招待トークン、Share Target本文、画像URLの秘密情報を残さない。
- 主要フローのPlaywright E2E、アクセシビリティ検査、実機確認を行う。
- 運用手順、障害時の再送、マイグレーション、ロールバック、バックアップ復旧手順を文書化する。

**リリース判定**

- フェーズ1の主要受け入れ条件が自動テストまたは明記した手動テストで全て確認済み。
- 認可、SSRF、冪等性、按分、精算、タイムゾーン、競合、再接続に未解決の重大問題がない。
- モバイル幅、キーボード、reduced motion、空状態を確認済み。
- Firebase Hostingのプレビュー環境でPWA、オフライン起動、再送、WebSocketを確認済み。
- 監視、アラート、trace IDによる障害追跡、バックアップ復旧方法が用意されている。

## 6. MVP後の拡張順序

### P2-1. Web Share Target

- `POST /candidates/import` でtitle、text、urlを受ける。
- iOS向けにtextからURLを抽出し、最大10件から選択させる。
- POST受信時点では保存せず、旅行・枠・URL確認後に認証済みAPIを呼ぶ。
- 本文やURLをアクセスログへ出さない。

### P2-2. JSON-LD

- 配列と `@graph` を含めて再帰走査する。
- Hotel、LodgingBusiness、Product、Restaurantを対象とする。
- 価格は参考表示だけにし、確定値へ自動入力しない。

### P2-3. OCR

- Cloud Storageへのアップロード後、Cloud TasksからVision APIを呼ぶ。
- 合計キーワード優先、預かり・釣銭除外のヒューリスティックで金額候補を返す。
- OCR結果は提案値とし、CONFIRMEDへ自動遷移させない。

### P2-4. オフライン強化

- 対応ブラウザではWorkbox Background Syncを追加する。
- 旅行スナップショットをDexie.jsへ保存し、オフライン起動時に最終同期時刻を表示する。
- 競合解決、キュー可視化、再送停止・再開を実装する。

### P3

- 正規化URLの24時間キャッシュと候補重複警告
- 金額・時刻による支出重複警告
- 送金リンクとリマインド
- `DECIDE_LOCALLY` と締切通知停止

## 7. 3人での進め方

担当者を固定して知識を分断せず、主担当とレビュー担当をローテーションする。各マイルストーンでは次の3レーンを基本とする。

| レーン | 主な担当 | 並行可能な作業 |
|---|---|---|
| A: 業務コア | 旅行、支出、按分、精算 | 純粋ロジック、DB制約、サービス、結合テスト |
| B: 境界・同期 | 認証、URL取得、Cloud Tasks、Outbox、WebSocket、GCP | セキュリティ境界、外部連携、運用 |
| C: フロント | 画面、フォーム、Query、Dexie、PWA、E2E | MSWでAPI未完成部分を先行実装 |

### 7.1 並行作業の共通ルール

- 各マイルストーンの開始時に、3人でAPI契約、DB所有範囲、イベント名、完了条件だけを合意する。実装詳細の完了を待ってから着手しない。
- OpenAPIの該当pathとschemaは原則としてA、認証・内部API・イベントschemaはBが編集する。同じファイルを触る場合は担当範囲を先に分ける。
- Cは確定したOpenAPI例からMSW fixtureを作り、バックエンド完成前に画面を実装する。手書きDTOは作らない。
- Flyway migrationはマイルストーンごとに連番を予約し、AとBが同じmigrationを並行編集しない。
- 各サブタスクは個別にレビュー・マージ可能な大きさに保ち、統合専用の巨大ブランチを作らない。
- 各担当は自分のレーンのテストまで含めて完了させる。合流後のE2EだけをCへ押し付けない。
- 業務コアはB、セキュリティ境界はA、画面のアクセシビリティはAまたはBがレビューする。

### 7.2 マイルストーン間の依存関係

推奨する依存関係は次のとおり。

```text
M0 → M1 → M2 → M3 → M5 → M6 → M8
                  ↘ M4 ↗
             M2 → M7 ─────────↗
```

M4の非同期基盤はM3の候補作成API確定後、M5/M6と並行できる。M7はM2で認証と旅行スナップショットができた時点から骨格を作り、各機能のイベントを順次追加する。

### 7.3 M0: 仕様の実装可能性を確定する

3人とも同時に調査・設計し、最後に仕様変更点だけを合流する。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M0-A1 | A | 旅行、枠、候補、支出、精算の状態遷移と不変条件を整理する | 状態遷移表、業務ルール一覧 |
| M0-A2 | A | DDL例を実スキーマへ落とし、確定負担額、精算スナップショット、監査履歴を設計する | ER図またはテーブル定義案 |
| M0-A3 | A | REST APIのresource、request、response、Problem Detailsを設計する | OpenAPI初版の業務API部分 |
| M0-B1 | B | Firebase認証、旅行内認可、招待・復旧トークンの境界を設計する | 認証・認可フロー、脅威一覧 |
| M0-B2 | B | Outbox、Cloud Tasks、WebSocket、Storageの責務とイベント形式を設計する | 非同期シーケンス、イベント一覧 |
| M0-B3 | B | SSRF、内部OIDC、ログ秘匿、監査、レート制限の要件を具体化する | セキュリティ設計チェックリスト |
| M0-C1 | C | プロトタイプとSPECから画面・route・権限・空状態を列挙する | 画面一覧とroute map |
| M0-C2 | C | サーバー状態、フォーム、一時状態、Dexie永続状態の境界を設計する | フロント状態設計 |
| M0-C3 | C | 主要フローをE2Eシナリオとfixtureへ変換する | E2Eシナリオ一覧、API例 |

**合流条件**

- AのAPI/DB、Bの境界/イベント、Cの画面/状態の用語とIDが一致している。
- 仕様確認事項8件の判断または暫定方針が決まり、必要な変更が `SPEC.md` に反映されている。
- M1で使うOpenAPIの共通形式とディレクトリ構成が確定している。

### 7.4 M1: 開発基盤を構築する

M0で決めたディレクトリと契約を入力として、3レーンを独立して構築する。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M1-A1 | A | Spring Boot、Java、Maven Wrapper、基本パッケージを構築する | 起動可能なbackend |
| M1-A2 | A | PostgreSQL、Flyway、Testcontainers、repository結合テストを構築する | DBテスト基盤 |
| M1-A3 | A | Bean Validation、Problem Details、trace IDの共通処理を実装する | APIエラー基盤 |
| M1-B1 | B | Spring Securityのfilter chain、テスト用Principal、Firebase検証interfaceを構築する | 認証基盤の骨格 |
| M1-B2 | B | Actuator、構造化ログ、外部設定、Secret Manager接続境界を用意する | 運用・設定基盤 |
| M1-B3 | B | CIのbackend jobと依存関係・シークレット検査を設定する | backend CI |
| M1-C1 | C | React、TypeScript strict、Vite、React Router Data Modeを構築する | 起動可能なfrontend |
| M1-C2 | C | Query、Form、Zod、Dexie、OpenAPI生成、MSWを構築する | フロントデータ基盤 |
| M1-C3 | C | Vitest、RTL、Playwright、ESLint、Prettier、PWA基本設定を構築する | frontend CIとテスト基盤 |

**合流条件**

- 生成したAPIクライアントから、テスト用Principalを使う疎通APIを呼べる。
- ローカル起動と全コマンドがルート `README.md` に記載されている。
- CIでbackend test、frontend lint/typecheck/test/buildが成功する。

### 7.5 M2: 認証、旅行、メンバー

旅行APIの契約を先に固定し、Aは業務データ、Bは本人確認と認可、CはMSWで画面を並行実装する。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M2-A1 | A | `trip`、`trip_member` と旅行作成・一覧・snapshotを実装する | 旅行API |
| M2-A2 | A | 初期枠、フェーズ判定、手動上書き、タイムゾーン境界を実装する | 旅行ドメインとテスト |
| M2-A3 | A | OWNER移譲、退出、論理削除、未精算残高制約を実装する | メンバー業務API |
| M2-B1 | B | Firebase ID token検証と `AppPrincipal` 変換を実装する | 本番認証filter |
| M2-B2 | B | `TripAuthorizationService` とresource所属検証を実装する | ロール別認可 |
| M2-B3 | B | 招待・復旧トークン、匿名参加、失効、レート制限を実装する | 招待・復旧API |
| M2-C1 | C | 旅行一覧、旅行作成、空状態を実装する | 旅行開始フロー |
| M2-C2 | C | 招待受取、名前入力、匿名参加、昇格案内を実装する | ゲスト参加フロー |
| M2-C3 | C | 3タブ、フェーズ表示、メンバー管理、主アクションを実装する | アプリシェル |

**合流条件**

- 匿名ユーザーが旅行作成または招待参加し、認可済みsnapshotを表示できる。
- CのMSWシナリオを実APIへ切り替えても画面コードの変更が不要である。
- RESTのロール表、期限切れ招待、OWNER移譲、タイムゾーン境界のテストが成功する。

### 7.6 M3: 枠、候補、投票、採択

候補のCRUD、予算・採択ルール、比較画面を分け、同じ機能内でも待ち時間を減らす。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M3-A1 | A | `slot`、`candidate`、`candidate_vote`、`plan_item` とCRUDを実装する | 候補業務API |
| M3-A2 | A | 予算シミュレーション、概算、units、締切計算を実装する | 予算ドメイン |
| M3-A3 | A | 投票、採択・変更・解除、楽観ロックを実装する | 決定フローAPI |
| M3-B1 | B | 各resourceの旅行所属とロール別認可テストを実装する | 候補認可テスト |
| M3-B2 | B | 候補作成の冪等性保存と `Idempotency-Key` 共通処理を実装する | 冪等API基盤 |
| M3-B3 | B | revision加算と候補系Outbox eventの書込み口を用意する | 同期イベント生成 |
| M3-C1 | C | 旅程、枠一覧、枠追加・分割・並べ替え、空状態を実装する | 旅程画面 |
| M3-C2 | C | 候補カード、仮選択、差額、固定予算バーを実装する | 比較画面 |
| M3-C3 | C | 3値投票、理由入力、採択、409競合UIを実装する | 投票・採択フロー |

**合流条件**

- 旅行作成から候補の手入力、比較、投票、採択、予定表示までE2Eで通る。
- `expected_member_count`、権限、別旅行resource、同時採択の受け入れテストが成功する。
- M4が利用する候補作成、metadata状態、Outbox eventの契約が固定されている。

### 7.7 M4: URL取り込みと非同期基盤

Aは候補メタデータの状態管理、Bは安全な外部取得と配信基盤、Cは非同期UIを並行実装する。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M4-A1 | A | 候補作成とOutbox登録を同一transactionへ統合する | 永続ジョブ要求 |
| M4-A2 | A | metadata状態遷移、再取得、ユーザー編集優先を実装する | metadata更新API |
| M4-A3 | A | タイムアウト時も候補作成が3秒以内に返る結合テストを作る | 非同期受入テスト |
| M4-B1 | B | URL正規化、scheme/port/IP/DNS/redirect検証を実装する | SSRF防御済みfetcher |
| M4-B2 | B | OGP、title、favicon抽出とエラー分類を実装する | metadata extractor |
| M4-B3 | B | Outbox dispatcher、Cloud Tasks、内部OIDC、再送を実装する | 非同期実行基盤 |
| M4-C1 | C | URL入力、金額・basis入力、即時仮カードを実装する | 候補入力UI |
| M4-C2 | C | PENDING、PROCESSING、失敗、再取得、手入力を実装する | 非同期状態UI |
| M4-C3 | C | Idempotency-Keyの生成・保持・再利用とMSW遅延試験を実装する | 再送可能なmutation |

**合流条件**

- 外部サイトを停止・遅延させても候補作成は成功し、後から完了または手入力へ収束する。
- SSRF、redirect、2MB、timeout、重複task、ユーザー編集競合のテストが成功する。
- 本番相当のOIDC経路以外から内部task APIを呼べない。

### 7.8 M5: 支出DRAFT、画像、按分

按分コア、画像・オフライン境界、入力体験を分離して進める。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M5-A1 | A | `expense`、`expense_share`、変更履歴とDRAFT/CONFIRMED遷移を実装する | 支出API |
| M5-A2 | A | EQUAL、WEIGHT、FIXED_AND_WEIGHT、最大剰余方式を実装する | 按分ドメイン |
| M5-A3 | A | 「前回と同じ」、一覧paging、確定制約を実装する | 支出補助API |
| M5-B1 | B | Storage object key、署名付きupload、MIME/容量制限を実装する | 画像upload境界 |
| M5-B2 | B | DRAFT作成の冪等性、孤立画像回収、監査ログを実装する | 安全なDRAFT保存 |
| M5-B3 | B | offline再送時の401/409/4xx契約と支出event生成を実装する | 再送・同期境界 |
| M5-C1 | C | 撮影、画像圧縮、金額だけ入力を実装する | 支出記録UI |
| M5-C2 | C | DexieのBlob・未送信操作・再送状態機械を実装する | offline queue |
| M5-C3 | C | 未確定トレイ、連続確定、按分preset、1人額表示を実装する | 支出確定UI |

**合流条件**

- オフライン開始した写真または金額入力が、復帰後に重複なくCONFIRMEDになる。
- 按分合計、端数の決定性、他旅行メンバー、固定額超過のテストが成功する。
- upload待ち、恒久エラー、409が画面から識別・解決できる。

### 7.9 M6: 精算

計算コア、認可・監査、精算画面を独立させ、共通fixtureで合流する。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M6-A1 | A | 精算snapshot、残高計算、貪欲transfer生成を実装する | 精算ドメイン |
| M6-A2 | A | DRAFT、CONFIRMED、COMPLETED、SUPERSEDED遷移を実装する | 精算API |
| M6-A3 | A | 後発支出、既払transfer、再精算を実装する | 再精算ロジック |
| M6-B1 | B | 精算作成・確定のOWNER/ORGANIZER認可を実装する | 管理者認可 |
| M6-B2 | B | 送金元PAID、送金先CONFIRMED、代理操作監査を実装する | 送金状態API |
| M6-B3 | B | 精算系revision、Outbox event、競合テストを実装する | 精算同期イベント |
| M6-C1 | C | 支出集計と精算DRAFTの確認画面を実装する | 精算案UI |
| M6-C2 | C | 送金状態、受取確認、完了表示を実装する | 送金進行UI |
| M6-C3 | C | 未反映変更、再計算、旧精算履歴を実装する | 再精算UI |

**合流条件**

- 確定支出から精算完了、後発支出、再精算までE2Eで通る。
- 残高合計0、transfer合計一致、回数上限、再計算決定性が自動検証される。
- 本人・管理者代理操作の認可と監査履歴が確認できる。

### 7.10 M7: リアルタイム同期と欠落回復

M2完了後にBが接続骨格、Cがclient骨格を先行し、Aは各機能のtransactionへeventを組み込む。M3〜M6と並行して段階的に完成させる。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M7-A1 | A | 全業務更新のrevision加算とOutbox書込みを点検・統一する | transaction整合性 |
| M7-A2 | A | 旅行snapshotをrevision付きで再取得できるようにする | 回復用REST API |
| M7-A3 | A | 重複・欠落・順序逆転を再現する結合fixtureを作る | 同期受入テスト |
| M7-B1 | B | STOMP CONNECTのFirebase検証を実装する | 認証済みWebSocket |
| M7-B2 | B | SUBSCRIBEの旅行内認可と旅行単位topicを実装する | 購読認可 |
| M7-B3 | B | at-least-once配信、Scheduler回復、配信監視を実装する | Outbox配信運用 |
| M7-C1 | C | stomp client、token更新、旅行表示中だけの接続を実装する | WebSocket client |
| M7-C2 | C | eventId重複排除、revision gap、Query無効化を実装する | event適用処理 |
| M7-C3 | C | REST同期後の再購読と複数browser E2Eを実装する | 欠落回復フロー |

**合流条件**

- イベントを意図的に欠落・重複・逆順配送しても全クライアントが最新snapshotへ収束する。
- REST更新元はWebSocketを待たず更新され、同一event受信でも二重反映されない。
- 非メンバーのCONNECT/SUBSCRIBEと内部dispatch呼出しが拒否される。

### 7.11 M8: PWA、E2E、クローズドβ配備

品質検証、インフラ、リリース体験を分けて並行し、最後に同一preview環境で判定する。

| ID | 担当 | サブタスク | 成果物 |
|---|---|---|---|
| M8-A1 | A | 全受け入れ条件とDB migrationの回帰試験を完成させる | backend release suite |
| M8-A2 | A | 金額・認可・冪等性・競合の性能/負荷境界を確認する | 品質レポート |
| M8-A3 | A | migration、rollback、backup restore手順を検証する | DB運用手順 |
| M8-B1 | B | Hosting、Run、SQL、Tasks、Storage、Scheduler等をIaC化する | 環境構築コード |
| M8-B2 | B | CORS、CSP、Secrets、backup、PITR、削除保護を設定する | security baseline |
| M8-B3 | B | 監視、alert、trace、Outbox再送、障害対応runbookを整備する | 運用基盤 |
| M8-C1 | C | manifest、icon、Service Worker更新、offline fallbackを完成させる | installable PWA |
| M8-C2 | C | 主要フロー、複数browser、offline、再接続E2Eを完成させる | E2E release suite |
| M8-C3 | C | モバイル、keyboard、focus、reduced motion、空状態を確認する | UI品質レポート |

**合流条件**

- 同じFirebase Hosting previewとCloud Run環境で3レーンの成果物を検証する。
- フェーズ1の全受け入れ条件について、自動テストまたは手動確認の証跡がある。
- 重大なセキュリティ・データ整合性問題がなく、バックアップ復旧と障害対応を実演できる。

### 7.12 担当ローテーションとレビュー

レーンは責務を示すもので、特定の人へ固定しない。知識の偏りを防ぐため、マイルストーン単位で次のように主担当を交代する。

| マイルストーン | Aレーン | Bレーン | Cレーン |
|---|---|---|---|
| M0〜M2 | 担当者1 | 担当者2 | 担当者3 |
| M3〜M5 | 担当者2 | 担当者3 | 担当者1 |
| M6〜M8 | 担当者3 | 担当者1 | 担当者2 |

UIやインフラの経験差が大きい場合は無理に主担当を交代せず、同じレーンのレビュー担当を交代する。各サブタスクのレビュー担当は次のレーンとし、本人だけで完結させない。

```text
Aの成果物 → Bがレビュー
Bの成果物 → Cがレビュー
Cの成果物 → Aがレビュー
```

## 8. テスト戦略

### 8.1 バックエンド

- **単体テスト:** フェーズ判定、初期枠、予算、按分、端数、残高、精算アルゴリズム、URL分類
- **DB結合テスト:** 外部キー、論理削除、楽観ロック、トランザクション、Outbox、冪等性
- **HTTP結合テスト:** Firebase Principal、旅行内認可、Problem Details、内部OIDC境界
- **プロパティテスト:** 按分合計、残高合計、transfer合計、再計算の決定性
- **外部連携テスト:** URL取得とCloud Tasksは偽サーバーを使い、実インターネットへ依存しない

### 8.2 フロントエンド

- **Vitest:** 金額・日付表示、revision判定、キュー状態遷移
- **React Testing Library:** フォーム、投票、按分、権限別UI、空状態、フォーカス
- **MSW:** 成功、遅延、オフライン、Problem Details、401、403/404、409、再送
- **Playwright:** 匿名参加、候補の一周、DRAFTの一周、精算、複数ブラウザ同期、オフライン復帰
- Service Worker、manifest、Share TargetはHTTPSのプレビュー環境または対応するローカル環境で検証する。

### 8.3 必須品質ゲート

```text
backend: ./mvnw test
frontend: npm run lint
frontend: npm run typecheck
frontend: npm test
frontend: npm run test:e2e
```

金額計算、認可、冪等性、SSRF、タイムゾーン、`409 Conflict`、オフライン再送、revision回復のテスト失敗はリリース阻止とする。

## 9. セキュリティと運用

- 認証はFirebase UIDの検証まで、認可は旅行とリソースの所属確認まで毎リクエスト実施する。
- ロールをFirebase custom claimsへ固定しない。
- 招待・復旧トークンは128bit以上、保存時ハッシュ化、期限・単回利用・失効を実装する。
- 招待受取と名前入力をIP・トークン単位でレート制限する。
- URL取得は専用クライアントを使い、一般的なHTTPクライアントの自動リダイレクトへ任せない。
- 内部APIは専用サービスアカウントのOIDCだけを許可する。
- Cloud Storageオブジェクトは非公開とし、旅行内認可後の短時間アクセスだけを許可する。
- 認証情報、招待URL、共有本文、レシート内容をログへ記録しない。
- 支出修正、管理者の代理送金操作、権限変更、OWNER移譲を監査記録へ残す。
- DBマイグレーションは後方互換の追加を先に行い、アプリ展開後に不要列を除去する。

## 10. 仕様確認事項

実装着手前に次を決定し、設計変更がある場合は `doc/SPEC.md` を更新する。

1. **フェーズ1とオフラインの境界**

   フェーズ1の受け入れ条件は「オフライン再送」を要求する一方、オフライン対応はフェーズ2に置かれている。本計画では、DRAFTを失わないDexie保存とフォアグラウンド再送をMVP必須、Background Syncとオフライン閲覧範囲の拡張をフェーズ2と解釈する。

2. **Share Targetの扱い**

   フェーズ1の主要受け入れ条件にShare Targetがある一方、機能一覧ではフェーズ2である。本計画では通常のURL貼付をMVP、OS共有からのPOST受取をフェーズ2とする。フェーズ1のリリース条件に含める場合はM4またはM8へ前倒しする。

3. **確定負担額の保存先**

   `expense_share` の例示DDLには計算後の円単位負担額がない。精算の再現性と監査のため、CONFIRMED時の確定負担額を保存する列または明細テーブルが必要である。

4. **精算のスナップショット**

   `expense_cutoff` だけでは、同時刻境界、過去支出の修正、既払transferの織り込みを厳密に再現しにくい。精算と対象支出・支出versionの関連を保存する方式を決める。

5. **監査モデル**

   仕様は支出訂正や管理者代理操作の履歴を要求するがDDLが未定義である。汎用監査イベントか、支出・送金別の履歴テーブルかを決める。

6. **候補と旅行設定の不足属性**

   自由タグ、メモ、投票の匿名/記名設定、DRAFT作成者など、画面仕様と権限制御に必要な属性を確定する。

7. **写真アップロードAPI**

   DRAFT作成と画像アップロードの順序、署名付きURL、失敗時の孤立オブジェクト削除、最大容量・MIME typeを確定する。

8. **匿名投票の意味**

   API上は未投票者や権限者へどこまで投票者情報を返すか、匿名設定時の閲覧ルールを定義する。

これらは機能拡張ではなく、現在の受け入れ条件を一貫して実装・検証するための設計確定事項である。

## 11. Definition of Done

各変更は次を満たしたとき完了とする。

- 仕様の受け入れ条件と権限表を満たす。
- 正常系、主要な異常系、境界値の自動テストがある。
- OpenAPIと生成型が更新され、重複DTOがない。
- DB変更には前進可能なFlywayマイグレーションがある。
- UI変更はモバイル、キーボード、フォーカス、reduced motion、空状態を確認している。
- セキュリティ、個人情報、ログ、監査への影響を確認している。
- 関連する `SPEC.md`、`screen-design.html`、README、運用文書が更新されている。
- CIの全品質ゲートが成功している。
