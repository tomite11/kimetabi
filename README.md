# タビキメワリ

旅行の候補決めから支出・精算までを一つにつなぐPWAです。プロダクトと
アーキテクチャの正本は `doc/SPEC.md`、実装順序は
`doc/IMPLEMENTATION_PLAN.md` を参照してください。

## 必要な環境

- Java 21
- Docker

Mavenはリポジトリに含めるMaven Wrapperを使用します。

## バックエンド

ローカル用PostgreSQLを起動します。

```shell
docker compose -f backend/compose.yaml up -d
```

```shell
cd backend
DATABASE_URL=jdbc:postgresql://localhost:5432/kimetabi \
DATABASE_USERNAME=kimetabi \
DATABASE_PASSWORD=kimetabi \
FIREBASE_PROJECT_ID=your-firebase-project \
./mvnw spring-boot:run
```

別のターミナルから停止する場合は、起動したターミナルで `Ctrl+C` を入力します。

テストは次のコマンドで実行します。

```shell
cd backend
./mvnw test
```

結合テストはTestcontainersからPostgreSQLを起動するため、Dockerが必要です。
Firebase Admin SDKは`FIREBASE_PROJECT_ID`が設定されている場合にApplication Default
Credentialsで初期化され、Bearerとして受け取ったFirebase ID tokenを検証して
`AppPrincipal`へ変換します。ローカルで本番認証を確認する場合は
`gcloud auth application-default login`などでADCを用意してください。固定トークン、
サービスアカウント鍵、テスト用認証ヘッダーはソースへ置きません。テストでは
Spring Security Testによるテスト専用`AppPrincipal`注入を使用します。

Cloud Runで招待・復旧のIP単位レート制限に転送元IPを使用する場合だけ、
`TRUST_GOOGLE_FORWARDED_FOR=true`を設定します。ローカル環境やGoogle proxyを
経由しない環境では設定せず、接続元のremote addressを使用してください。

Cloud Tasksによる候補メタデータ取得を有効にする環境では、次を外部設定します。
TasksとSchedulerには別のservice accountとaudienceを指定してください。未設定時は
内部APIをfail closedで拒否し、Outboxは未配信のまま保持します。

```shell
GOOGLE_CLOUD_PROJECT=your-project
CLOUD_TASKS_LOCATION=asia-northeast1
CLOUD_TASKS_METADATA_QUEUE=metadata
BACKEND_BASE_URL=https://api.example.com
TASKS_SERVICE_ACCOUNT_EMAIL=kimetabi-tasks-invoker@your-project.iam.gserviceaccount.com
TASKS_OIDC_AUDIENCE=https://api.example.com/internal/tasks
SCHEDULER_SERVICE_ACCOUNT_EMAIL=kimetabi-scheduler-invoker@your-project.iam.gserviceaccount.com
SCHEDULER_OIDC_AUDIENCE=https://api.example.com/internal/outbox
```

dev、staging、productionは別projectとし、上記service account IDを各projectで
共通利用します。Cloud RunにはTasks用とScheduler用のaudienceをcustom audienceとして
登録します。metadata queueは初回を含む最大3回、`minBackoff=60s`、
`maxBackoff=600s`の指数backoffで構成します。正確な「1分後・10分後」ではなく、
Cloud Tasks標準のbackoffを使用します。

Cloud Tasksのpayloadには`eventId`と`candidateId`だけを入れ、raw URLはDBから取得します。
`/internal/tasks/**`と`/internal/outbox/**`は、それぞれ対応するOIDC identity以外を
拒否します。

設定は環境変数に加え、Cloud Runでマウントする
`/var/run/secrets/kimetabi/` のconfig treeから外部注入できます。
`/actuator/health` は未認証で公開し、その他のActuator endpointは認証を要求します。
ログは既定でJSON形式となり、`traceId` を含みます。読みやすいローカルログが必要な
場合だけ `SPRING_PROFILES_ACTIVE=local` を指定してください。

ローカルDBを停止する場合は次を実行します。

```shell
docker compose -f backend/compose.yaml down
```

## フロントエンド

Node.js 22以降を使用します。初回だけ依存関係をインストールします。

```shell
cd frontend
npm install
```

開発サーバーを起動します。

```shell
cd frontend
npm run dev
```

OpenAPI型は `openapi/openapi.json` から生成します。型検査とbuild時にも自動生成
されます。

APIクライアントはFirebase Authenticationの匿名ユーザーを自動作成または復元し、
取得したID tokenを認証が必要なリクエストへBearer tokenとして付与します。
ローカルで実APIへ接続する場合は次のVite環境変数を設定してください。
`VITE_ENABLE_MSW=true` のUI開発ではFirebaseへ接続せず、MSW用tokenを使用します。

```shell
VITE_FIREBASE_API_KEY=...
VITE_FIREBASE_AUTH_DOMAIN=...
VITE_FIREBASE_PROJECT_ID=...
VITE_FIREBASE_APP_ID=...
```

開発サーバーの `/api` は既定で `http://127.0.0.1:8080` へproxyされます。別のAPIを
使う場合だけ `VITE_DEV_API_TARGET` を設定してください。本番buildではproxyは使わず、
`VITE_API_BASE_URL` でCloud RunのAPI originを指定します。

```shell
cd frontend
npm run generate:api
npm run typecheck
npm run build
```

単体・コンポーネントテスト、lint、型検査、モバイルE2Eを実行します。E2Eでは
MSWを有効にした開発サーバーをPlaywrightが自動起動します。

```shell
cd frontend
npm test
npm run lint
npm run test:e2e
```

Firebase Auth Emulator、実Spring Boot API、PostgreSQLを通すM2縦切りE2Eは、先に
ローカルPostgreSQLを起動してから実行します。`demo-` project IDとloopback emulator
だけを許可するため、本番Firebase認証情報は不要です。

```shell
POSTGRES_PORT=55432 docker compose -f backend/compose.yaml up -d
cd frontend
npm run test:e2e:integration
```

PWAのService Workerはproduction buildで生成されます。認証付きの
`/api` レスポンスはCache Storageへ保存せず、ネットワークのみを使用します。
