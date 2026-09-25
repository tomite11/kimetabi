# M8 クローズドβ完了判定レポート

- 判定日: 2026-09-25
- 判定対象commit: `1c5c231002b2028ff4ca7979e49099fd1888aa1f`
- GCP project / region: `kimetabi-preview-998740556155` / `asia-northeast1`
- Frontend: `https://kimetabi-preview-998740556155--closed-beta-ch7b7443.web.app`
- Backend: `https://kimetabi-preview-api-zgwys6oq3a-an.a.run.app`
- Cloud Run revision: `kimetabi-preview-api-00005-hz5`
- 判定: **M8完了、クローズドβリリース可能**

## 合流条件

| 条件 | 結果 | 証跡 |
|---|---|---|
| A／B／Cを同一Previewで検証 | 合格 | Phase 5〜7の各レポートと上記環境識別子 |
| 全受け入れ条件に証跡あり | 合格 | `doc/M8_PHASE5_ACCEPTANCE_REPORT.md` |
| PWA・UI実機相当検証済み | 合格 | `doc/M8_PHASE6_PWA_UI_REPORT.md`、`doc/evidence/m8-phase6/` |
| 復旧演習済み | 合格 | `doc/M8_PHASE7_RECOVERY_DRILL.md` |
| 必須CI成功 | 合格 | 下記「CI品質ゲート」 |
| Terraformに意図しない差分なし | 合格 | 最終plan `No changes` |
| 重大・高問題なし | 合格 | OWASP監査と最終差分レビュー |

## M8タスク別判定

| タスク | 判定 | 主な証跡 |
|---|---|---|
| M8-A1 回帰テスト | 完了 | Backend全223件成功、`doc/DOMAIN_STATE_INVARIANTS.md` |
| M8-A2 品質境界 | 完了 | `doc/BACKEND_QUALITY_REPORT.md` |
| M8-A3 DB運用 | 完了 | `doc/DATABASE_OPERATIONS.md`、Phase 7の別instance restore |
| M8-B1 Preview基盤 | 完了 | Terraform適用済み、remote state、最終plan `No changes` |
| M8-B2 セキュリティ境界 | 完了 | `doc/SECURITY_CHECKLIST.md`、`doc/SECURITY_BOUNDARIES.md` |
| M8-B3 運用・復旧 | 完了 | `doc/OPERATIONS_RUNBOOK.md`、Phase 7のrollback／alert／Outbox演習 |
| M8-C1 主要フローE2E | 完了 | Phase 5のPreview受け入れE2E |
| M8-C2 PWA・offline | 完了 | Phase 6のService Worker、DRAFT再送、camera fallback検証 |
| M8-C3 UI・アクセシビリティ | 完了 | Phase 6の360px、200%、keyboard、focus、reduced motion検証 |

## CI品質ゲート

| Workflow | 対象commit | 結果 | 内容 |
|---|---|---|---|
| [Frontend](https://github.com/tomite11/kimetabi/actions/runs/36085843677) | `ed9b7cf` | 成功 | lint、typecheck、unit、build、release E2E、integration E2E |
| [Infrastructure](https://github.com/tomite11/kimetabi/actions/runs/36085843667) | `ed9b7cf` | 成功 | fmt、init、validate、M8-B infrastructure test |
| [Backend](https://github.com/tomite11/kimetabi/actions/runs/35321615614) | `cffa289` | 成功 | 全test、gitleaks secret scan |

Backend／OpenAPIにはBackend CI対象commit以降、Frontend／Firebase／InfrastructureにはFrontendと
Infrastructure CI対象commit以降の差分がない。判定対象commitまでに増えた変更はPhase 6・7の
レポート、運用手順、検証画像であり、検証済み成果物との対応を維持している。

## 最終環境確認

- Backend healthは`UP`。Cloud Run最新Ready revisionへtraffic 100%、`min=0` / `max=1`である。
- Cloud SQLはRUNNABLE、PostgreSQL 17、backup 14件、PITR 7日、削除保護有効である。
- Outbox回復とreceipt cleanupのSchedulerはENABLEDで、直近実行を確認した。
- Cloud Run 5xx、Outbox dispatch failure、Cloud SQL CPUの3 alert policyは有効で、各policyが
  email／Slackの2 notification channelを持つ。
- Hosting preview channelは有効で、CSPは接続先を完全一致で限定する。HSTS、`nosniff`、frame拒否、
  referrer policy、permissions policyを確認した。
- `npm audit --omit=dev --audit-level=high`は脆弱性0件。認証・認可、SSRF、CORS、内部OIDC、秘密値、
  REST／STOMP境界は自動試験と設定レビューで確認した。

## リリース判定と残存制約

停止条件に該当するCI失敗、Terraformの破壊的差分、基盤入力不足、重大な認可・金額・冪等性・復旧問題、
Preview外resourceの変更はない。したがってM8を完了とし、現在のPreviewをクローズドβとして
リリース可能と判定する。

次の項目はM8の停止条件ではないが、公開リリース前に対応する。

- `api.tabikime.app`のDNSとCloud Run custom domain mappingを構成する。クローズドβはCloud Runの
  既定URLを使用する。
- OS Share Target POSTは実装計画の裁定どおりPhase 2で扱う。M8では通常URL貼付を検証済みである。
- 訓練alertはincidentのopen／自動closeと2通知channelの有効性まで確認した。受信者側でのemail／
  Slack既読確認は運用担当者の定期訓練へ引き継ぐ。
