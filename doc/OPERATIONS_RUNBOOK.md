# クローズドβ 運用・障害対応runbook

M8-B3のCloud Run、Cloud SQL、Cloud Tasks、Outbox、Storage運用手順である。DB migration、
rollback、backup restoreは `doc/DATABASE_OPERATIONS.md` を併用する。

## 観測とアラート

Terraformは次のalert policyを作成する。通知先はemailとSlack等の
異なる2系統とし、環境ごとに承認したnotification channel IDを渡す。

- Cloud Runの5xxが5分間に5件以上
- Outbox dispatch失敗が5分間に1件以上
- Cloud SQL CPU使用率80%超過が5分間継続

Cloud Loggingでは構造化logの `traceId` を起点に同一requestを追跡する。Bearer token、
招待・復旧token、Share Target本文、receipt内容、署名URL、取得対象raw URLを検索条件や
incident記録へ貼らない。対象resource ID、trip ID、event ID、outcome codeだけを記録する。

## 初動

1. incident ID、検知時刻、環境、最初のtrace IDを記録する。
2. 新規書込み、read、内部Schedulerのどこに障害があるか切り分ける。
3. Cloud Run revision、直近deploy、Cloud SQL operation、Tasks queue、Scheduler実行履歴を確認する。
4. データ破損の疑いがあれば書込みを止め、DB復旧手順へ移る。
5. credential漏えいの疑いがあれば対象secret versionを無効化し、新versionへrotationする。

## Outbox滞留・配信失敗

1. `Outbox dispatch failed` のevent IDとoutcome codeを確認する。payloadやraw URLは出力しない。
2. Tasks queue停止、Cloud Run 5xx、OIDC audience／service account不一致を確認する。
3. 原因解消後、Scheduler job `kimetabi-ENV-outbox-recovery` を一度手動実行する。
4. 内部endpointは同一eventを少なくとも1回処理し得る。event IDの冪等性を維持し、DBの
   `published_at` を手作業で更新しない。
5. 未配信件数、最古event時刻、失敗件数が減少し、対象trip revisionのクライアントがREST
   snapshotへ収束したことを確認する。

Outbox滞留時間・件数の専用metricは未実装のため、DB read-only queryと
上記のdispatch失敗alertを併用する。Outbox回復jobはUTCで5分周期とする。

## Cloud Run障害とrollback

1. 5xxをrevision別に比較し、health probeとtrace IDを確認する。
2. schemaが旧revisionと互換であることを `doc/DATABASE_OPERATIONS.md` で確認する。
3. 互換ならtrafficを直前の正常revisionへ戻す。DB migrationは巻き戻さない。
   `min instances = 0`かつ`max instances = 1`では切替直後に旧revisionのinstanceがまだなく、
   platformが一時的に`429`を返すことがある。traceのtext payloadが`no available instance`であることを
   確認し、上限付きretryでreadinessを待つ。アプリ由来の429や5xxと混同しない。
4. Schedulerの対象URLとOIDC audienceが切替後も有効か確認し、Outbox回復を実行する。
5. REST read、候補作成、支出確定、精算作成、WebSocket再接続をsmoke testする。

演習やrollback確認後に最新revisionへ戻す場合も、traffic更新と上限付きreadiness retryを同じ
作業単位で実行する。途中失敗時にも復帰処理を実行し、最後にrevision名とtraffic 100%を確認する。

## Cloud SQL障害・復旧

接続増加、CPU、disk、backup状態を確認する。データ消失・破損時は別instanceへのbackup restore
またはPITRを使う。同一instanceへの破壊的restoreやTerraformの削除保護解除は、障害対応責任者の
明示承認なしに行わない。復旧後はFlyway履歴、不変条件、read-only API、Outboxを検証する。

## Cloud Tasks・Scheduler障害

- Tasksは初回を含め最大3回のat-least-once配送であり、重複を正常系として扱う。
- `429`、一時的5xx、transport failureはretry対象、SSRF拒否や恒久的4xxは手入力へ誘導する。
- Tasks用とScheduler用service account／audienceを入れ替えない。
- queueを再開する前に急増した未処理件数を確認し、同時実行数をTerraform外で変更しない。
- receipt cleanup失敗は次の30分周期で再試行し、24時間未満のobjectを削除しない。

## Secret rotation

1. Secret Managerへ新versionを追加する。
2. Cloud Run revisionが新versionを取得するよう再deployする。
3. healthと認証済みsmoke testを確認する。
4. 旧versionを無効化し、監視後に破棄する。secret値をTerraform stateへ保存しない。

## 復旧完了条件

- healthと主要read/write smoke testが成功する。
- Cloud Run 5xxとOutbox失敗が通常状態へ戻る。
- 未配信eventが回復し、revision gapがREST snapshotで解消する。
- Cloud SQLのbackup/PITR状態と次回backup予定を確認できる。
- incident記録に時系列、trace ID、影響resource、原因、再発防止を残し、秘密情報を含めない。

## Alert通知演習

業務データや実Outboxを壊してalertを起こさない。承認済みの訓練では、専用log名へ`drill=true`、
incident ID、秘密を含まないoutcome codeを付けた単発の構造化logを投入し、log-based metric、alert
policy、email／Slackの2経路を確認する。訓練logを通常障害と区別し、通知文へtoken、payload、URLを
含めない。演習後はincidentが自動closeすることも確認する。
