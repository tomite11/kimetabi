# バックエンド品質境界

## 目的

M8-A2のクローズドβ判定として、金額計算、認可、冪等性、楽観ロックが
想定上限でも正しさを失わず、退行を検出できる境界を定義する。本書の時間予算は
共有CIで異常な計算量増加やDBアクセス増加を検知するための回帰ゲートであり、
Cloud Runの本番SLOや容量保証ではない。本番環境の負荷試験とSLO決定はIaC構築後に行う。

## 自動検証する境界

| 領域 | 入力境界 | 合格条件 | 自動試験 |
|---|---:|---|---|
| 按分 | 100人、5,000回 | 5秒以内、毎回合計一致 | `ExpenseAllocationReleaseRegressionTest` |
| 精算 | 100人、2,000支出 | 5秒以内、残高合計0、送金99回以内 | `SettlementCalculatorTest` |
| 認可 | 異なる非メンバーから100回参照 | 10秒以内、全件404、revision・Outbox不変 | `CandidateApiTest` |
| 冪等性 | 同じkeyとpayloadを100回送信 | 10秒以内、resource・revision・eventが各1件 | `CandidateApiTest` |
| 競合 | 同じversionで16並行更新 | 10秒以内、成功1件・409が15件、versionは1だけ進む | `CandidateApiTest` |

金額計算は固定seedのランダム回帰も併用し、最大剰余方式の合計一致、非負、
member IDによる決定的な端数配分を検証する。認可負荷は存在隠蔽のため404を維持し、
拒否処理が業務データ、revision、Outboxへ副作用を残さないことまで確認する。

## 実行方法

Docker daemonを起動して、リポジトリの `backend/` で次を実行する。

```bash
./mvnw --batch-mode --no-transfer-progress test
```

時間予算超過、HTTP statusの変化、金額・version・件数不変条件の失敗は
リリース阻止とする。共有CIより大幅に遅い開発端末で時間予算だけが失敗した場合も、
閾値を緩和して通すのではなく、CI結果とクエリ回数を確認して原因を記録する。
