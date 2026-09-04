# データベースのmigration・rollback・復旧手順

## 原則

- Flyway migrationは前進のみとし、適用済みファイルを編集・削除しない。
- schema変更はexpand → アプリ切替 → contractの順で行い、旧Cloud Run revisionが
  expand後のschemaで動作できる期間を確保する。
- `flyway_schema_history` を手で修正しない。migration失敗は原因を修正した新しい
  migrationで前進させる。
- restoreは対象DBを上書きするため、原則として別のCloud SQL instanceまたは
  別databaseへ復旧し、検証後に接続先を切り替える。

## リリース前migration

1. 対象commitで `./mvnw --batch-mode --no-transfer-progress test` を実行する。
2. M8-B2で定義するIaCについて、自動backup、PITR、削除保護が有効であることを確認する。
3. on-demand backupを取得し、backup ID、source instance、取得時刻、commit SHAを
   作業記録へ残す。
4. migration用の単一ジョブからアプリを起動し、Flyway validationとmigrationを行う。
   同時に複数revisionを起動しない。
5. `flyway_schema_history` の全行が成功し、最新versionが期待値と一致することを確認する。
6. 新revisionへ段階的に切り替え、認証、旅行snapshot、候補作成、支出確定、精算作成を
   smoke testする。

## アプリケーションrollback

schemaがexpand段階で旧revisionと互換なら、Cloud Runのtrafficを直前revisionへ戻す。
DB schema自体は戻さない。旧revisionと互換でない変更を検出した場合はtrafficを止め、
データ修復用のforward migrationを作成する。データ損失または破損が疑われる場合は、
下記のbackup／PITR復旧へ移る。

## backupからの復旧

1. 書込みを停止し、復旧対象時刻と影響範囲を確定する。
2. backup一覧から対象backupを選び、別instanceへrestoreする。同一instanceへのrestoreは
   現在データとPITR logを上書きするため、障害対応責任者の明示承認なしでは行わない。
3. 復旧先で次を確認する。
   - `flyway_schema_history` のversionとchecksum
   - 旅行・メンバー・候補・支出・確定負担額・精算snapshot・監査・Outboxの件数
   - ACTIVE OWNERが旅行ごとに1人であること
   - 確定支出の負担額合計と支出額が一致すること
   - 精算残高と送金額が一致すること
4. APIのread-only smoke test後、Cloud Runの接続先Secretを復旧instanceへ切り替える。
5. 書込み再開後にtrace ID、DB error、Outbox滞留を監視する。旧instanceは調査完了まで保持する。

Cloud SQLのCLIで既存backupを復旧する場合の基本形は次のとおり。実際のproject、backup、
instance名を作業記録と相互確認してから実行する。

```bash
gcloud sql backups restore BACKUP_ID --restore-instance=TARGET_INSTANCE_NAME
```

## PITR

backup以降の特定時刻へ戻す場合は、UTCのRFC 3339時刻を確定し、新しいinstanceへcloneする。

```bash
gcloud sql instances clone SOURCE_INSTANCE_NAME NEW_INSTANCE_NAME \
  --point-in-time='2030-01-01T00:00:00.000Z'
```

復旧時刻は障害発生直前より安全側を選び、復旧先で監査イベントと業務件数を照合する。
PITRを無効化・再有効化すると以前のlogを利用できなくなるため、障害対応中に設定を変更しない。

## ローカルでの復旧試験

`DatabaseMigrationReleaseTest` はPostgreSQL 17コンテナ内で次を自動実行する。

1. 空DBへ全Flyway migrationを適用する。
2. 復旧確認データを作成してcustom-formatの`pg_dump`を取得する。
3. backup後に元DBを変更する。
4. 別databaseへ`pg_restore --exit-on-error`で復旧する。
5. backup時点のデータとFlyway V1〜V12の履歴が復元されたことを照合する。

この試験は論理backupの可搬性を検証する。Cloud SQLの自動backup、PITR、削除保護と
実instanceへの復旧演習はM8-B2/B3の環境完成後に別途実施する。

## 参考資料

- [Cloud SQL for PostgreSQL: Restore an instance](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/restore)
- [Cloud SQL for PostgreSQL: Perform point-in-time recovery](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/pitr)
- [Cloud SQL for PostgreSQL: Import and export best practices](https://docs.cloud.google.com/sql/docs/postgres/import-export)
