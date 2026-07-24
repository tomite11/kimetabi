# Repository Guidelines

## プロジェクト構成とモジュール

本リポジトリは現在、設計検討フェーズです。プロダクトおよびアーキテクチャの正本は `doc/SPEC.md` です。設計判断を変更した場合は、他の文書に異なる前提を残さず、仕様書も更新してください。

システムは、React＋TypeScript＋ViteのPWA、Spring Boot API、PostgreSQLで構成します。実装時は `frontend/` と `backend/` に分離し、テストは対象モジュール内に配置してください。フロントエンドは共通UIを `src/components/`、機能単位のコードを `src/features/` に置きます。共通文書は `doc/` で管理します。ビルド成果物、キャッシュ、認証情報、データベースのエクスポートはコミットしないでください。

## ビルド・テスト・開発コマンド

現時点ではビルド設定や実行可能なアプリケーションがないため、正式なコマンドは未定です。実装開始時に、クリーンなチェックアウトから実行できるコマンドをルートの `README.md` に記載してください。想定するコマンドは次のとおりです。

- `./mvnw spring-boot:run` — Spring Boot APIをローカルで起動する
- `./mvnw test` — バックエンドの単体・結合テストを実行する
- `npm run dev` — PWAの開発サーバーを起動する
- `npm test` — フロントエンドテストを実行する
- `npm run lint` — ESLintとPrettierによる検査を実行する
- `npm run typecheck` — `tsc --noEmit` で型を検査する
- `npm run test:e2e` — PlaywrightのE2Eテストを実行する

ツール導入時はMaven WrapperとJavaScriptのロックファイルをコミットし、上記スクリプト名を維持してください。

## コーディング規約と命名

Javaは4スペース、TypeScript、JSON、YAML、CSSは2スペースでインデントします。Javaの型とReactコンポーネントは `PascalCase`、変数や関数は `camelCase` を使用してください。TypeScriptはstrict modeと関数コンポーネントを使用します。スタイルはCSS ModulesとCSS Custom Propertiesで管理します。`Trip`、`Candidate`、`Expense` など仕様書のドメイン用語を優先し、同じ概念に別名を導入しないでください。RESTエンドポイントは `/api` 配下に置き、旅行を親リソースとします。

## フロントエンド設計

画面を実装・変更する際は、`doc/screen-design.html` のHTMLプロトタイプを参照してください。配色、タイポグラフィ、余白、旅程テープ、フェーズ表示、3タブ構成、画面下部の主アクションをデザイン基準として扱います。プロトタイプの見た目だけを写すのではなく、レスポンシブ表示、キーボードフォーカス、reduced motion、空状態も維持してください。業務仕様や受け入れ条件は `doc/SPEC.md` を正本とし、プロトタイプと矛盾する場合は仕様書を優先して差異を報告します。意図的にデザインを変更する場合は、実装と同じ変更でプロトタイプも更新してください。

ルーティングはReact Router Data Modeを使用します。REST由来の状態はTanStack Query、フォームはReact Hook Form＋Zod、永続的なオフラインデータはDexie.js、画面内の一時状態はReactのstateへ置きます。ReduxやZustandを先行導入しないでください。API型はOpenAPIから生成し、`openapi-fetch` から利用します。DTOを重複定義しません。

Service Workerはvite-plugin-pwa＋Workboxで構成します。認証付きAPIレスポンスをCache Storageへ保存しないでください。未送信操作には `Idempotency-Key` と対象versionを保持し、成功確認前にDexie.jsから削除しません。WebSocketは `@stomp/stompjs` のネイティブ接続を使い、再接続後はREST同期してから購読します。

## テスト方針

振る舞いを変更する際はテストも追加してください。バックエンドはJUnit 5とSpring Boot Test、フロントエンドはVitest、React Testing Library、MSWを使用します。主要フローはPlaywrightで検証します。Javaは `*Test`、フロントエンドは `*.test.ts(x)` と命名してください。特に認可、金額計算、冪等性、タイムゾーン境界、`409 Conflict`、オフライン再送、再接続時のrevision回復を重点的に検証します。

## Agent Skillsの使用

作業内容に対応するSkillがある場合は、実装やレビューを始める前に対象の `SKILL.md` を最後まで読み、その手順に従ってください。

- Reactコンポーネント、データ取得、バンドル、性能改善には `vercel-react-best-practices`
- Spring BootのAPI、サービス、設定、テストには `java-springboot`
- manifest、Service Worker、Workbox、キャッシュ、オフライン対応には `pwa-development`
- 認証・認可、入力検証、REST API、SSRFなどの監査には `owasp-security-check`
- ブラウザ動作確認とPlaywrightによる検証には `webapp-testing`
- UIの視覚品質とアクセシビリティのレビューには `ui-design-review`
- PRや変更差分のレビューには `code-review-excellence`

複数領域にまたがる変更では、実装対象のSkillを先に適用し、テスト、セキュリティ、コードレビューの順で確認します。Skillの一般的な例より `doc/SPEC.md` と本ガイドのプロジェクト固有ルールを優先してください。Skillが仕様と矛盾する場合は独断で変更せず、差異を報告してください。

## コミットとプルリクエスト

現在はGit履歴を確認できないため、既存のコミット規約はありません。`backend: 支出按分を検証する` のように、短く命令形で、必要に応じて対象を付けてください。1コミットには関連する変更だけを含めます。

プルリクエストには、変更内容、実施したテスト、仕様への影響を記載してください。関連Issueをリンクし、UI変更にはスクリーンショットを添付します。スキーマ変更、セキュリティ上の影響、後続対応は明示してください。
