# クローズドβ infrastructure

M8-B1〜B3のGoogle Cloud基盤をTerraformで管理する。東京リージョン、Cloud Run
`min=0` / `max=1`、単一ゾーンCloud SQL、自動backup、PITR、二重の削除保護、非公開
receipt bucket、Tasks/Scheduler専用OIDC identityをコードで固定する。

## 適用前の入力

`terraform.tfvars.example` をコピーし、実環境のIDを設定する。tfvarsとstateは
secretを含み得るためcommitしない。Cloud SQL tier、backup/PITR保持期間、
alert閾値、Outbox回復周期は`doc/SPEC.md`のクローズドβ初期値をdefaultとする。
alertの通知channel IDだけは環境固有であり、emailとSlack等の異なる2系統を設定する。

Secret Managerのsecret containerはTerraformが作成するが、secret payloadはstateへ
残さない。最初にremote backendを初期化する。

```bash
terraform init \
  -backend-config='bucket=REPLACE_APPROVED_TERRAFORM_STATE_BUCKET' \
  -backend-config='prefix=kimetabi/ENV'
```

次にAPIとsecret containerを作り、以下の3 secretへversionを登録する。

- `kimetabi-ENV-database-url`
- `kimetabi-ENV-database-username`
- `kimetabi-ENV-database-password`

```bash
terraform apply \
  -target='google_project_service.required' \
  -target='google_secret_manager_secret.database'
```

DB URLはCloud SQL Java Connectorを利用するJDBC URLを指定する。例えば
`jdbc:postgresql:///kimetabi?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy`
の形式とする。値の登録後に全体をapplyする。平文資格情報をCLI historyやTerraform変数へ
渡さず、承認済みのsecret投入手段を使う。Cloud SQL application userのpasswordはTerraformの
ephemeral variableとproviderのwrite-only属性で設定する。Secret Managerのversion番号と
`database_password_version`を一致させ、値そのものはstateへ保存しない。

```bash
terraform fmt -check -recursive
terraform validate
TF_VAR_database_password="$(gcloud secrets versions access 1 \
  --secret=kimetabi-ENV-database-password)" terraform plan -out=release.tfplan
terraform show release.tfplan
TF_VAR_database_password="$(gcloud secrets versions access 1 \
  --secret=kimetabi-ENV-database-password)" terraform apply release.tfplan
```

state bucketは環境とは別のbootstrap手順でversioning、public access prevention、限定IAMを
設定する。local stateのまま実環境へapplyしない。既存Firebase project／Hosting siteを
採用する場合は、最初のplan前にTerraform stateへimportする。

Cloud Runへはdigest固定のimageだけを許可する。Firebase Hostingはリポジトリルートで
frontendをbuildした後、承認済みpreview channelへdeployする。本番反映前に必ずpreviewで
CSP、REST、WebSocket、Service Workerを確認する。

backend imageは `backend/Dockerfile` からbuildし、Artifact Registryへpushしたdigestを
`backend_image`へ設定する。コンテナは非root UIDで実行される。

## 権限境界

- runtime: Cloud SQL接続、receipt object操作、署名URL用の自己署名、Tasks enqueue、
  対象secret読取、log/metric書込
- Tasks identity: `/internal/tasks/**` のOIDC呼出しだけ
- Scheduler identity: `/internal/outbox/**` と `/internal/receipts/**` のOIDC呼出しだけ
- public invoker: Cloud Run到達だけ。Spring SecurityがFirebase認証と内部OIDCを検証する

Cloud Runの既定URLを直接呼べてもアプリ認証は迂回できない。CORSはブラウザ境界であり、
認証・認可の代用にはしない。
