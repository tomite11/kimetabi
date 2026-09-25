# M8-C フロントエンド品質レポート

確認日: 2026-09-25

## 対象と自動検証

M8-C1〜M8-C3として、`npm run test:e2e:release` はproduction buildをpreview serverで
配信し、manifest、Service Worker制御下のオフライン再起動、オンライン状態の文字通知、
mobile Chromium・desktop Chromium・desktop Firefoxの主要フロー、横overflow、空状態、
キーボードフォーカス、`prefers-reduced-motion: reduce` を検証する。

Service Worker更新は自動適用しない。更新可能時に案内し、利用者の操作で有効化する。
Dexieの未送信操作は更新時に削除せず、API success確認後だけ削除する既存契約を維持する。
認証付き `/api` responseはCache Storageへ保存しない。

## プレビュー環境での手動確認

以下はFirebase Hosting previewと同一Cloud Run環境でリリース判定時に実施する。

- manifest、192/512/maskable icon、standalone起動、インストールを確認する
- 旧worker待機時の更新案内と、更新後の再起動を確認する
- airplane modeで起動し、復帰後に未送信DRAFTが一度だけ送信されることを確認する
- 実機のカメラ入口とファイル選択fallbackを確認する
- 360px、200% zoom、キーボードのみで主要フローを確認する
- WebSocket切断後にREST snapshotへ収束することを2ブラウザで確認する

preview URL、実施者、実施日時をリリース記録へ残した時点で手動確認を完了とする。

## ローカル検証結果

- `npm run typecheck`: 成功
- `npm run lint`: 成功
- `npm test`: 55件成功
- `npm run test:e2e`: mobile Chromium 5件成功
- `npm run test:e2e:release`: production PWA 1件、3環境の主要フロー15件成功
- `npm run test:e2e:realtime`: 2ブラウザ欠落回復1件成功
- 390×844、1440×900の目視確認: 横overflowなし、可視フォーカスあり

## Preview検証結果

Firebase Hosting Previewと同一Cloud Run環境で、installability／standalone、Service Worker、
オフライン起動と写真DRAFT再送、camera/file fallback、360px・200%相当表示、keyboard・focus、
reduced motion、空状態、2 browser同期を確認した。Lighthouse 12はAccessibility 100、
Best Practices 100だった。端末、browser、日時、修正内容、スクリーンショットは
`doc/M8_PHASE6_PWA_UI_REPORT.md`へ記録した。

Preview検証中に発見したCloud Storage uploadのCSP／CORS不備と主CTAのコントラスト不備は修正し、
再配備後のPreview E2Eで回帰確認した。未解決の重大・高優先度UI問題はない。
