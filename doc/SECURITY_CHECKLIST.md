# M0セキュリティ設計チェックリスト

この文書は `M0-B3` の成果物である。チェック欄は実装完了を表すものではなく、
M1以降で実装・テスト証跡を要求する項目を表す。

## 認証・認可

- [ ] Firebase tokenの署名、issuer、audience、expiryを検証する
- [ ] Firebase custom claimsを旅行roleの正本にしない
- [ ] 非ACTIVEメンバーをRESTとSUBSCRIBEで拒否する
- [ ] nested resourceの `trip_id` をサービス層とDB外部キーで照合する
- [ ] 非メンバーは404、既知のメンバーのrole不足は403にする
- [ ] OWNER移譲、代理送金操作、確定支出訂正を監査する

## 招待・復旧

- [ ] tokenは128 bit以上のCSPRNGで生成し、hashだけを保存する
- [ ] expiry、revocation、single-useをmember変更と同一transactionで検証する
- [ ] token、招待URL、復旧URLをlog・audit・errorへ出さない
- [ ] IPとtoken hashの両方でrate limitし、429と`Retry-After`を返す
- [ ] replay、期限切れ、失効、別member、UID競合をテストする

## SSRF・外部取得

- [ ] `doc/URL_FETCH_SECURITY.md` の全hop検証を実装する
- [ ] HTTP clientの自動redirectを無効にする
- [ ] IPv4/IPv6のnon-public addressと曖昧なIP表現を拒否する
- [ ] DNS検証結果へconnectをpinしてrebindingを拒否する
- [ ] 80/443以外を拒否する
- [ ] connect 3秒、全体5秒、本文2 MiB、redirect 5回を強制する
- [ ] response bodyやraw URLをlogへ出さない

## internal API・非同期

- [ ] TasksとSchedulerで別service accountとaudienceを使用する
- [ ] `/internal/**` でFirebase tokenを拒否する
- [ ] task payloadをIDだけにし、event IDで冪等化する
- [ ] data、version、revision、audit、Outboxを同一transactionで保存する
- [ ] dispatch後・`published_at`更新前の停止を重複配送テストで再現する
- [ ] Scheduler dispatchで未配信eventが回復することを検証する

## WebSocket

- [ ] CONNECTとSUBSCRIBEを独立して拒否テストする
- [ ] destinationのtrip IDとACTIVE membershipを照合する
- [ ] event ID重複、順序逆転、revision gapをテストする
- [ ] 再接続時はREST snapshot取得後にSUBSCRIBEする
- [ ] 匿名投票の他者情報や秘密URLをevent payloadへ含めない

## Storage・ログ・監査

- [ ] signed URL発行前にDRAFT編集権限を確認する
- [ ] random object key、非公開bucket、短いexpiryを使う
- [ ] JPEG/PNG/WebP、10 MiBを発行時と完了時に検証する
- [ ] PENDING/FAILEDの孤立objectを回収する
- [ ] bearer token、invite/recovery token、Share Target本文、receipt内容、
  signed URL、raw取得URLをlogへ出さない
- [ ] trace ID、actor ID、trip ID、resource ID、outcome codeだけを記録する
- [ ] audit JSONをallowlistで構築し、秘密フィールドをredactする

## 未決事項

次は `doc/SPEC.md` から値を判断できないため、M0-B1〜B3では独断で決めない。

- 招待受取と復旧受取のrate limit回数、window、共有state store
- signed upload URLの有効時間
- 孤立receiptを削除するまでの保持時間
- Outbox滞留alertの時間・回数閾値
- 同一hostへの具体的な同時接続数（仕様は1〜2としている）
- Tasks用・Scheduler用service account名と環境別OIDC audience
- URL取得のtimeoutと`429`を自動再試行するか、および失敗状態の分類
