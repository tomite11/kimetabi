# M8 Phase 5 フェーズ1受け入れ検証レポート

- 実施日時: 2026-09-18 19:00〜19:08 JST
- 対象commit: `cffa2896ed3546166b9dbb2c5777deefcd46cc60`
- Frontend: `https://kimetabi-preview-998740556155--closed-beta-ch7b7443.web.app`
- Backend: Cloud Run revision `kimetabi-preview-api-00005-hz5`
- GCP project / region: `kimetabi-preview-998740556155` / `asia-northeast1`
- 証跡旅行ID: `13`
- 結果: 成功（Playwright 1件、4.4分）

## 検証結果

| 領域 | 検証内容 | 結果 | 代表trace ID |
|---|---|---|---|
| 旅行・招待 | 旅行作成、招待作成、匿名参加 | 成功 | `392aaef4b6d413a6319280a007296ea0`, `193479070048f7e90d935f6314651352` |
| REST / WebSocket認可 | 非メンバーRESTを404、会員STOMP購読を許可、非メンバー購読をERRORで拒否 | 成功 | `4a847d82bd22387ba0b2e1bc8395926a` |
| 候補・投票・採択 | URL候補、冪等再送、手入力候補、投票、OWNER採択、MEMBER採択拒否 | 成功 | `ebf4ef6436e77b41badc653ee03ddf2a`, `23d4a8aeb494dd412730f7783fc83ede`, `8d3489b45c1d706df69a3cb5887ae3b2` |
| URL非同期取得 | API応答3秒以内、`PENDING`からCloud Tasksを経由して終端状態へ遷移 | 成功 | `0623177232b86524de9db29ec7c17d88` |
| DRAFT・offline再送 | 写真先行DRAFT、同一Idempotency-Key再送で同一支出、不完全確定を422 | 成功 | `9768844ee4dba786f6c61a77d42d3766`, `abc8393b3ca4e39edc0660ca21995acd`, `cd680cd8e75e07b5b93f4e81e61c2b58` |
| 按分 | 101円を2名へ決定的に50円・51円で配賦し総額一致 | 成功 | `9c198902549ef8a30e39ea86c1947c63` |
| 精算・再精算 | 101円時点を1送金に圧縮・確定、40円追加後も旧精算不変、未反映表示、新精算141円 | 成功 | `623eb2c1345d410351f6f759198a8386`, `3ee1b466e126e95014c0c80e53504f6c`, `adf9764be25895e742c7a6173002a7b0` |
| 競合・revision回復 | 採択・支出・旅行の古いversionを409、WebSocket再接続後のREST snapshotで最新revisionへ回復 | 成功 | `9ff67842313d2f9de311f150f89f6af4`, `d959f25e59f57c415a1df637a374511c`, `f375e6af1d5474e9e8f1cca5b87a9a4f`, `7ae37633115e23be9b73bdd5d58187bf` |
| タイムゾーン | `Asia/Tokyo`の旅行日付・支払日時・手動フェーズ変更を維持 | 成功 | `07b88cb10d46fac0e80baba7ced70701` |

全28 REST確認のstatusとtrace ID、およびWebSocket認可結果はPlaywrightの
`phase5-preview-evidence.json` attachmentへ保存する。token、招待URL、秘密値は証跡へ含めない。

## 検証中に修正した問題

Cloud Tasksの配送先と内部OIDC audienceが、DNS未開通の`api.tabikime.app`を参照していたため、
metadata taskがHTTP status 0で失敗していた。公開URLと内部配送URLを分離し、内部経路をCloud Runの
既定URLへ変更した。Terraformは3 resourceのin-place更新後に`No changes`となり、修正後のtaskは
内部endpointへ204で到達した。

## 補足

OS Share Target POSTは`doc/IMPLEMENTATION_PLAN.md`第10節の裁定によりPhase 2扱いであり、
M8 Phase 5の対象外である。通常のURL貼付と非同期metadata取得は本検証で確認した。
