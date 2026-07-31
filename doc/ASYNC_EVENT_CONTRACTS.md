# 非同期処理・イベント契約

この文書は `M0-B2` の成果物である。Cloud Tasks、Transactional Outbox、
WebSocket、Cloud Storageの責務と、少なくとも1回配送での回復方法を定義する。

## 責務

| 要素 | 責務 | 正本にしないもの |
|---|---|---|
| 業務transaction | resource更新、version、trip revision、監査、Outboxを原子的に保存 | 外部配送の成功 |
| Outbox | commit済み変更とjob要求を永続化 | 画面の現在状態 |
| dispatcher | 未配信OutboxをCloud Tasks/STOMPへ少なくとも1回配送 | exactly-once保証 |
| Cloud Tasks | metadata handlerを再試行可能に起動 | 候補状態の正本 |
| metadata handler | SSRF-safe取得、状態更新、結果event生成 | 利用者権限 |
| STOMP | commit済み変更通知 | 更新API、履歴API |
| REST snapshot | 再接続・revision gap後の収束 | push通知 |
| Cloud Storage | receipt objectを非公開保存 | 支出の認可・状態 |

## 候補metadata

```text
候補作成transaction
  candidate(PENDING) + trip.revision + outbox(job requested)
  → commit
  → dispatcherがOutbox IDをCloud Task名として作成
  → handlerが専用OIDCを検証
  → candidate状態とevent IDで重複確認
  → PENDING/FAILED_RETRYABLE → PROCESSING
  → URLを全hop検証して取得
  → COMPLETED または FAILED_*
  → trip.revision + audit + outbox(result)を同一transactionで保存
  → STOMP通知
```

- task payloadは `eventId`、`candidateId` だけとし、raw URLやFirebase tokenを
  入れない。URLは認可済みDBレコードから取得する。
- task名はOutbox event IDから決定し、Cloud Tasksへの重複登録を抑える。
- handlerもevent IDとcandidate状態で冪等にする。既にterminal状態なら成功応答し、
  metadataを再適用しない。
- 仕様で自動再試行対象と確定しているDNS失敗と一時的`5xx`だけを
  `FAILED_RETRYABLE` とし、1分後・10分後の最大2回再試行する。
- URL形式、SSRF、port、redirect上限、本文上限、恒久的`4xx`は
  `FAILED_PERMANENT` とする。timeoutと`429`の自動再試行分類は仕様から判断できず、
  M0では決定しない。
- 利用者の明示的な再取得は新しいevent IDで別操作として記録する。
- metadataはユーザーが編集したtitle/imageを上書きしない。

## Outbox dispatchと回復

- 1業務transactionが同じtrip revisionで複数eventを生成することを許可する。
- dispatcherは未配信行を取得し、外部配送成功後にだけ `published_at` を記録する。
- process停止により外部配送後・記録前となった場合は重複配送される。consumerは
  event IDで重複排除する。
- Cloud Scheduler用 `/internal/outbox/dispatch` は専用service accountだけを許可し、
  Firebase利用者tokenやTasks用service accountを許可しない。
- attempts、最終outcome code、ageを観測し、一定時間未配信のeventをalert対象にする。
  alert閾値は運用要件が未定義のためM0では決定しない。

## WebSocket通知

```json
{
  "eventId": "UUID",
  "tripId": 123,
  "tripRevision": 842,
  "type": "CANDIDATE_METADATA_COMPLETED",
  "resourceType": "candidate",
  "resourceId": 456,
  "resourceVersion": 2,
  "occurredAt": "2026-07-24T10:15:30Z"
}
```

- destinationは `/topic/trip/{tripId}`。
- CONNECT時にFirebase ID tokenを検証し、SUBSCRIBE時にdestinationのtripに対する
  ACTIVE membershipを別途検証する。
- payloadは通知に必要なID、revision、typeだけを基本とし、token、raw URL、
  receipt URL、投票匿名設定で隠す情報を含めない。
- clientは自身のREST成功responseで即時更新し、同じeventを後から受けてもevent IDで
  無視する。
- revisionが現在値より2以上大きければsnapshotを再取得する。同一transactionの
  複数eventは同じrevisionを持てるため、現在値と同じrevisionでも未処理event IDは
  処理する。再接続では先にREST同期し、得たrevisionを基準にしてからSUBSCRIBEする。

## イベント名

| event type | resource type | 発生契機 |
|---|---|---|
| `MEMBER_JOINED` | `member` | 招待参加commit |
| `MEMBER_ROLE_CHANGED` | `member` | role変更commit |
| `CANDIDATE_CREATED` | `candidate` | 候補作成commit |
| `CANDIDATE_UPDATED` | `candidate` | 利用者編集commit |
| `CANDIDATE_METADATA_REQUESTED` | `candidate` | metadata job登録 |
| `CANDIDATE_METADATA_COMPLETED` | `candidate` | metadata取得成功 |
| `CANDIDATE_METADATA_FAILED` | `candidate` | metadata取得失敗 |
| `CANDIDATE_VOTE_CHANGED` | `candidate` | 投票commit |
| `SLOT_ADOPTION_CHANGED` | `slot` | 採択・変更・解除commit |
| `EXPENSE_DRAFT_CREATED` | `expense` | DRAFT作成commit |
| `EXPENSE_CONFIRMED` | `expense` | 支出確定commit |
| `EXPENSE_UPDATED` | `expense` | 確定支出訂正commit |
| `SETTLEMENT_CONFIRMED` | `settlement` | 精算確定commit |
| `SETTLEMENT_TRANSFER_UPDATED` | `settlementTransfer` | 送金状態commit |

イベント名と共通envelopeはBレーン所有とし、A/Cは文字列を別定義しない。

## Storage upload

```text
認可済みDRAFT
  → serverがランダムなobject keyとreceipt PENDINGを作成
  → 短期signed upload URLを返す
  → clientが直接upload
  → completion API
  → serverがStorage metadataのsize/content typeを再検証
  → receipt UPLOADED
```

- bucketは非公開とし、DBにはobject keyだけを保存する。
- clientの元filenameをobject keyに使わない。
- JPEG、PNG、WebPだけを許可し、最大10 MiB。申告値だけでなくStorage上の実値を
  completion時に確認する。
- PENDING/FAILEDの孤立objectを定期削除する。削除までの保持時間は仕様にないため
  M0では決定しない。
