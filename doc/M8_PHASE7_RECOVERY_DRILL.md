# M8 Phase 7 運用・復旧演習記録

- 実施日時: 2026-09-25 11:37〜12:07 JST
- 検証開始時commit: `ed9b7cf`
- Project: `kimetabi-preview-998740556155`
- Region: `asia-northeast1`
- 実施者: Codex

## Cloud SQL backup restore

- 元instance: `kimetabi-preview-postgres`（削除保護あり、backup 14件、PITR 7日）
- on-demand backup ID: `1790303852723`
- backup期間: 2026-09-25 02:37:32〜02:39:24 UTC
- 復元先: `kimetabi-preview-restore-drill-20260925`
- restore operation: `b5b56145-81d8-4949-abdb-f0740000002b`、`DONE`
- 元instanceを上書きせず、空の別instanceへ復元した。検証後に復元先だけを削除し、backupは保持した。

復元先へCloud SQL Auth Proxy経由でread-only SQLを実行した。秘密値、DB dump、業務payloadは
ファイルやlogへ保存していない。

| 照合項目 | 結果 |
|---|---:|
| Flyway V1〜V12 | 12件すべて成功、失敗0件 |
| trip / trip_member | 26 / 37 |
| candidate / expense / expense_share | 8 / 15 / 12 |
| settlement / settlement_expense / settlement_share / transfer | 6 / 9 / 18 / 6 |
| audit_event / outbox_event | 36 / 75 |
| ACTIVE OWNERがちょうど1人でない旅行 | 0 |
| CONFIRMED支出の按分合計不一致 | 0 |
| 精算snapshotの按分合計不一致 | 0 |
| 精算残高・送金後残高の不一致 | 0 |
| 未配信Outbox | 0 |

## Cloud Run rollback

- 最新revision `kimetabi-preview-api-00005-hz5`から直前の
  `kimetabi-preview-api-00004-d8q`へtrafficを100%切り替えた。
- 切替直後はscale-to-zeroからの起動待ちによりplatform `429`となった。trace
  `640dde22cd7c67b79253004532b859d1`のtext payloadが`no available instance`であることを確認した。
- 上限付きretry後、旧revisionの`/actuator/health`が`UP`となりrollback可能性を確認した。
- 同じ手順で最新revisionへ戻し、`UP`、traffic 100%、latest ready revisionが
  `kimetabi-preview-api-00005-hz5`であることを確認した。DB schemaは変更していない。

## Outbox回復・trace

- Production DBの未配信Outboxは実行前0件だった。
- Scheduler job `kimetabi-preview-outbox-recovery`を手動実行し、Scheduler用service accountのOIDCで
  `/internal/outbox/dispatch`が`200`を返した。
- trace ID: `ab5f47557436fa44fa6139ad27e02c00`、latency 0.615秒。
- 実行後も未配信0件、jobは`ENABLED`、status errorなしだった。
- 実データを不正状態へ書き換えて滞留を作らず、失敗・再送・冪等回復は
  `OutboxDispatcherTest`のTestcontainers試験で補完する。

## Alert通知・追跡

- Email `Kimetabi Preview Email`とSlack `Kimetabi-Alerting`が有効で、Cloud Run 5xx、Outbox失敗、
  Cloud SQL CPUの3 policyすべてに2経路が設定されていることを確認した。
- 業務データを変更せず、専用log `phase7-recovery-drill`へ`drill=true`、incident ID
  `M8-PHASE7-20260925`、outcome code `DRILL_ONLY`だけを持つ訓練logを投入した。
- Alert policyは2026-09-25 03:02:10 UTCにincident `0.od17urxiveas`を`OPEN`とし、設定済みの
  Email／Slack 2経路を通知先として評価した。
- 単発metricが評価windowから外れた2026-09-25 03:06:46 UTCにincidentが自動で`CLOSED`となった。
- token、Outbox payload、招待・署名URL、個人情報はlogと演習記録に含めていない。

## 自動検証

- `./mvnw --batch-mode --no-transfer-progress -Dtest=DatabaseMigrationReleaseTest,OutboxDispatcherTest test`:
  8件成功。migration backup／restoreとOutbox失敗・再送・冪等性を確認。
- `./mvnw --batch-mode --no-transfer-progress test`: 223件成功、失敗0、error 0、skip 0。
- Terraform plan: `No changes`。

## Cleanupと最終状態

- 復元先instance: 削除済み。元instanceとon-demand backupは保持。
- Cloud Run: `kimetabi-preview-api-00005-hz5`へtraffic 100%、health `UP`。
- Scheduler: `ENABLED`、直近の手動実行は`200`。
- Production未配信Outbox: 0件。
