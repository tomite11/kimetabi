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
Firebase Admin SDKの実装はM2で接続します。M1では検証interfaceとSpring Security
Testによるテスト専用`AppPrincipal`注入を提供し、本番用の固定トークンやテスト用
認証ヘッダーは用意しません。

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

PWAのService Workerはproduction buildで生成されます。認証付きの
`/api` レスポンスはCache Storageへ保存せず、ネットワークのみを使用します。
