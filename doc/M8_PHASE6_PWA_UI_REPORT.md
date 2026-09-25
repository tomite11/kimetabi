# M8 Phase 6 PWA・UI検証レポート

- 実施日時: 2026-09-25 10:28〜10:56 JST
- 検証開始時ベースcommit: `f58742e689e3b6302f53ec3720969f0439eeee6a`
- Preview URL: `https://kimetabi-preview-998740556155--closed-beta-ch7b7443.web.app`
- 実施者: Codex
- 実行端末: MacBook Air `Mac16,12`、Apple M4、32 GB、macOS 27.0
- Browser: Google Chrome 153.0.8010.54、Playwright 1.57.0 Chromium
- 証跡旅行: `Phase 6 東京の旅`

## 結果

| 項目 | 検証内容 | 結果 | 証跡 |
|---|---|---|---|
| install / standalone | Chrome DevToolsのinstallability errorが0件。manifestの`display: standalone`と192/512/maskable iconを確認 | 成功 | [オフライン起動](evidence/m8-phase6/01-offline-start-360.png) |
| Service Worker更新 | `registerType: prompt`、待機workerの更新案内、利用者操作による適用、未送信Dexie操作を保持する契約をunit testで確認。Previewの`sw.js`は`no-cache` | 成功 | `PwaStatus.test.tsx`、Preview E2E |
| airplane mode / 再送 | SW制御下でオフラインreloadし、写真DRAFTをIndexedDBへ保存。オンライン復帰後に同一DRAFTを1件だけ作成し、署名付きPUTと未確定トレイ反映まで確認 | 成功 | [オフライン撮影fallback](evidence/m8-phase6/04-camera-fallback-offline.png) |
| camera fallback | `capture="environment"`のカメラ入口と、captureなし・JPEG/PNG/WebPのファイル選択fallbackで実ファイルを処理 | 成功 | [オフライン撮影fallback](evidence/m8-phase6/04-camera-fallback-offline.png) |
| 360px / 200% | 360 CSS pxで横overflowなし。720物理pxを200%表示した有効幅360 CSS px相当でナビゲーションと主アクションがviewport内 | 成功 | [360px](evidence/m8-phase6/02-trip-home-360-reduced-motion.png)、[200%相当](evidence/m8-phase6/03-trip-home-200-percent.png) |
| keyboard / focus | Tab先頭がskip link、3pxの可視outline、主要操作のフォーカスとEnter操作を確認 | 成功 | [360px](evidence/m8-phase6/02-trip-home-360-reduced-motion.png) |
| reduced motion | `prefers-reduced-motion: reduce`でtransition durationが0.01ms以下 | 成功 | Preview E2E |
| 空状態 | 旅行一覧、候補、支出の空状態から主要フローへ進めることをrelease E2Eで確認 | 成功 | release E2E 15件 |
| 2 browser同期 | 独立したowner/member contextで招待参加。Outbox通知後、ownerがreloadなしで「メンバー 2人」へREST snapshot同期 | 成功 | [owner](evidence/m8-phase6/05-owner-synchronized.png)、[member](evidence/m8-phase6/06-member-synchronized.png) |
| UI品質 | PreviewをLighthouse 12で監査 | Accessibility 100、Best Practices 100 | 2026-09-25 10:52 JST |

Service Workerのprecacheに認証付き`/api` responseが存在しないこともCache Storageの全requestを
列挙して確認した。スクリーンショットにはtoken、招待URL、署名付きURLを含めていない。

## 検証中に修正した問題

1. Firebase HostingのCSPに署名付きreceipt upload先がなく、オフライン復帰後のPUTが遮断された。
   `connect-src`へ`https://storage.googleapis.com`だけを追加した。
2. Storage bucketのCORSが署名条件`x-goog-if-generation-match`を許可しておらず、preflightが失敗した。
   許可originとPUT限定を維持したまま、当該headerだけを追加した。
3. Firebase Authが使用するGoogle scriptとPreview auth iframeがCSPで遮断された。`script-src`へ
   `https://apis.google.com`、`frame-src`へPreview projectのFirebase auth domainを完全一致で追加した。
4. 主CTAの白文字とコーラル背景が3.17:1でWCAG AA未達だった。デザイン基準のコーラルを
   `#cf432f`へ調整し4.67:1とし、HTML prototypeも同時更新した。

CSPにwildcardは追加していない。receipt bucketは非公開、public access prevention、uniform access、
署名付きURL、世代一致条件を維持している。

## 自動検証

- `npm test`: 16 files / 55 tests成功
- `npm run lint`: 成功
- `npm run typecheck`: 成功
- `npm run test:e2e:release`: PWA 1件、mobile Chromium・desktop Chromium・desktop Firefox 15件成功
- `npm run test:e2e:realtime`: 1件成功
- `npm run test:e2e:preview-ui`: 1件成功（2.3分）
- Terraform `fmt -check` / `validate`: 成功
- Terraform apply: Storage CORS 1件をin-place更新
- Lighthouse 12: Accessibility 100 / Best Practices 100

Lighthouseの`valid-source-maps`監査だけはproduction source mapを公開していないため失敗したが、
Accessibility／Best Practicesのcategory scoreには影響しない低優先度の既知事項とした。
