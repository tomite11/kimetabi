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
./mvnw spring-boot:run
```

別のターミナルから停止する場合は、起動したターミナルで `Ctrl+C` を入力します。

テストは次のコマンドで実行します。

```shell
cd backend
./mvnw test
```

結合テストはTestcontainersからPostgreSQLを起動するため、Dockerが必要です。
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

テスト、lint、E2EはM1-C3で基盤を追加します。コマンド名は先に固定しています。

```shell
cd frontend
npm test
npm run lint
npm run test:e2e
```

CIの手順はM1-B3で追加します。
