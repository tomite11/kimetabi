# タビキメワリ（旅決め割り）仕様書

旧称: 旅程シェア＆ワリカン
作成日: 2026-07-24
最終更新: 2026-07-24
想定開発体制: 3人
ステータス: 設計検討フェーズ（実装前）

---

## 1. 背景と課題

グループ旅行では、以下の3つが繰り返し発生する。

1. **候補が流れる** — LINEに宿やスポットのURLを貼り合うが、トーク履歴に埋もれて後から一覧できない
2. **比べられない** — 価格・立地・条件がバラバラで横並び比較ができず、判断できない
3. **決まらない** — 「どれでもいいよ」が続き、結局幹事が決め切るしかない

加えて、旅行中の立替・精算も煩雑で、誰がいくら払ったのかを把握するのが難しい。

---

## 2. 競合分析

### 2.1 Walica（https://walica.jp/）

- 立替え履歴を入力すると「誰から誰にいくら」を計算する精算特化サービス
- 会員登録・アプリDL不要という徹底した軽量設計
- **守備範囲**: 精算計算のみ

### 2.2 Go! Dutch（https://www.go-dutch.app/）

個人開発者「はるもなか」氏による旅行管理アプリ。2026年1月公開。

- URL共有だけの簡単招待（「マイナスデザイン」＝操作を極力減らす思想）
- 時差を考慮したスケジュール管理（現地時間＋現在地時間の併記）
- Google Map連携
- 予定に紐づく支払い管理（前払い前提）
- 最小送金での割り勘計算、精算済みフラグによる除外
- 35通貨対応（ただし日本円換算は非対応）
- AIによる旅程レビュー機能（開発中）

**守備範囲**: 確定した旅程の管理 ＋ 事前支払いの割り勘

### 2.3 Wariwari / ワリワリ（https://wariwari-app.com/）

個人開発による割り勘管理アプリ。2026年6月頃に公開。

- 飲み会や旅行の割り勘をスマートに管理する無料アプリ
- 複数人の立て替え支払いを記録し、最小限の返済回数で清算方法を自動計算
- URLでイベントを共有
- グループ保存機能により、同じメンバーでの繰り返し利用における幹事の負担を軽減
- PWAとして実装されている（`mobile-web-app-capable` 等のメタタグから確認）

**守備範囲**: 精算特化（Walicaに近いが、グループ保存とイベント共有で一歩進んでいる）

**本アプリへの示唆**

- 計画フェーズ（候補・投票・予算シミュレーション）には踏み込んでいないため、**案Aの差別化戦略はそのまま有効**
- 現地支出のキャプチャ（レシート、オフライン）にも踏み込んでいないため、**案Bも有効**
- ただし「グループ保存」は本アプリの「前回と同じ」按分プリセットと発想が近く、同種の使い勝手は既に提供され始めている
- 命名上の注意: 「ワリ」系のネーミング空間が埋まりつつある

### 2.4 空いている領域

| 領域 | 既存の状況 | 差別化の余地 |
|---|---|---|
| 計画フェーズの合意形成 | どちらも「予定が決まった後」から | 候補出し・投票・比較で**決めるまで**を支援 |
| 旅行中の細かい現地支出 | 事前に金額が分かる前払い中心 | レシートOCR、その場入力 |
| 為替換算 | 通貨別管理はできるが円換算は非対応 | 決済日レートで自動円換算 |
| 精算の実行 | 「いくら払うか」で終わる | 送金リンク生成、催促、完了確認 |
| 部分参加の按分 | 全員均等が基本 | 「このアクティビティはA/Bだけ」を自然に扱う |
| 旅行後 | 精算して終わり | 支出データ×写真で旅の記録に変換 |

---

## 3. コンセプト

> 旅行の**お金の流れ**を、候補を出す段階から精算完了まで一本の線で管理する

Go! Dutchが「確定した旅程の管理」に最適化されているのに対し、本アプリは**支出という軸**で全期間を貫く。候補段階でも「この案なら1人いくら」が見え、現地の細かい支払いも同じ器に入り、最後に一度で精算される。

採用する差別化戦略は以下の組み合わせ。

- **案A**: 「決めるまで」に特化した旅行合意形成（前段）
- **案B**: 現地支出キャプチャ（後段）
- 中央の事前支払い割り勘も自前で持つ（アプリを乗り換えずに済むことが価値）

中央部分はGo! Dutchと正面衝突するが、両端があるからこそ中央も自社で持つ必要がある、という位置づけ。

---

### 3.1 サービス名

**正式名称**: タビキメワリ（旅決め割り）
**通称・ドメイン**: タビキメ / `tabikime.app`
**タグライン**: 「旅は、決めて、割るまで。」

「旅」「決める」「割る」の3要素を並べた造語。正式名称で機能を説明し、日常的には略称で呼ばれることを想定する。ロゴやOGPでは正式名称を用いる。

**命名の経緯**

当初は機能（決める／割る）を軸に検討を始め、途中で「複数人の旅行」を主軸に切り替えた。最終的に、機能軸と旅軸を統合した造語に着地している。

*検討した方向と候補*

| 方向 | 候補 |
|---|---|
| 機能軸（決める＋割る） | キメワリ、ワリキメ、タビワケ、キメタビ |
| 旅の一体感 | タビノワ、ヒトタビ、マルタビ、ミナタビ |
| 幹事の負担軽減 | カンジラク、タビダンドリ、ヨリアイ |
| 英語系 | Splitrip、Tabiwari、PickPay、Wetrip、Tabimate |
| 旅のしおりの比喩 | タビシオリ、ミンナノシオリ、シオリ |
| 同行者を表す | トモタビ、タビナカ、タビグミ、ワイタビ、タビヅレ |
| 機能軸＋旅（最終方向） | **タビキメワリ**、キメワリタビ、キメタビ、タビキメ、キメシオリ、キメワケ |

*調査して見送った候補*

| 候補 | 見送り理由 |
|---|---|
| キメワリ | 検索エンジンが「キマワリ」（ポケモン・昆虫）に自動補正し、名前が検索上のアイデンティティを持てない。同名サービスは存在しなかったが、SEOの初速で不利 |
| Wetrip | 同名の旅行アプリが6件以上存在。うち wetripapp.com（仏）は宿泊先URLの貼付＋グループ投票という案Aとほぼ同一の機能を持つ。ドメインも主要なものが取得済み |
| トモタビ | 製薬会社（タービー®）の患者サポートプログラムとして現行運用中。検索結果に医療情報が混在する。旅行業界でもキャンペーン名として頻用（沖縄観光、H.I.S.等） |
| キメタビ | 同じ旅行カテゴリで既存サービスが稼働中（kimetabi.com、観光スポット自動レコメンド、株式会社deepblue）。ドメインも取得済み |
| タビワリ／ワリ系全般 | 「旅割」はANAの割引商品名として定着。tabiwari.jp（宿泊割引サイト）も存在。加えてWalica・Wariwariで「ワリ」系の命名空間が混雑 |

*選定理由*

- 「旅」「決める」「割る」の3要素を全て含み、名前だけで機能が伝わる
- 造語として一意性が高く、正式名称・略称ともに未使用
- 検索ドリフト先（タヒバリ、タキリビメ等）がいずれも別カテゴリで、混同の実害が小さい
- 長さ（6拍）が唯一の弱点だが、略称「タビキメ」で対処可能

*この調査から得られた命名の評価基準*

今後リブランドや別プロダクトを命名する際にも適用できる。

1. **一文字違いの有名語がないか** — キメワリは「キマワリ」に検索を吸われた。造語の利点（検索で自社だけが出る）が最初から失われる
2. **同カテゴリに同名がないか** — 別カテゴリの同名は共存し得るが、同カテゴリは致命的
3. **一般名詞・既存キャンペーン名に近すぎないか** — 過去の特集記事やツアー商品名と混ざって埋もれる
4. **英語系は基本的に不利** — 旅行アプリは個人開発でも人気ジャンルのため、素直な英単語の組み合わせはほぼ埋まっている。国内市場に集中するなら和名が有利で、競合（Go! Dutch）が英語イディオム系である分、差別化にもなる
5. **略称も同時に検証する** — 略称が既存サービスと衝突すると、口頭での伝達時に混乱する

**確認済み事項**（2026-07-24時点）

- 同名のサービス・アプリ・企業なし（「タビキメワリ」「タビキメ」いずれも）
- 旅行カテゴリでの衝突なし
- 商標: 空き（J-PlatPatで確認済み）
- ドメイン: 空き

**留意点**

- 「タビワリ」と略すと既存サービス（tabiwari.jp、宿泊割引サイト）と衝突するため、略称は「タビキメ」に統一する
- 英語圏では「we + trip」系の命名が飽和しているため、海外展開時は別名義を検討する
- 検索ドリフト先の「タヒバリ」（鳥）は無害だが、ロゴ・OGPでの表記固定とドメインの併記により表記を強く印象づけること

---

## 4. 支出のライフサイクル

```
候補比較・投票 ──→ 確定予定 ──┐
（概算で試算）    （事前支払い）  │
                              ├──→ 支出（統合） ──→ 精算
レシート取込 ─────────────────┘   （按分・円換算）   （送金回数を抑えて精算）
（現地でその場入力）
```

設計上の核心は、**事前支払いと現地支出を同じ `expense` エンティティに統合すること**。ここが分かれていると精算ロジックが二重になり複雑化する。

---

## 5. データモデル

### 5.0 旅行・メンバー

```sql
CREATE TABLE trip (
  id                    BIGINT PRIMARY KEY,
  owner_member_id       BIGINT,
  title                 VARCHAR(200) NOT NULL,
  destination           VARCHAR(200) NOT NULL,
  starts_on             DATE NOT NULL,
  ends_on               DATE NOT NULL,
  timezone              VARCHAR(40) NOT NULL,       -- IANA TZ。フェーズ判定の基準
  expected_member_count INT NOT NULL,               -- 招待状況に左右されない予定人数
  phase_override        VARCHAR(20),                 -- NULL / PLANNING / TRAVELING / SETTLING
  budget_cap            NUMERIC(12,2),
  revision              BIGINT NOT NULL DEFAULT 0,  -- リアルタイム同期の単調増加番号
  CHECK (ends_on >= starts_on),
  CHECK (expected_member_count > 0)
);

CREATE TABLE trip_member (
  id           BIGINT PRIMARY KEY,
  trip_id      BIGINT NOT NULL,
  firebase_uid VARCHAR(128) NOT NULL,                -- 匿名・正規で共通
  name         VARCHAR(100) NOT NULL,
  role         VARCHAR(20) NOT NULL,                 -- OWNER / ORGANIZER / MEMBER
  status       VARCHAR(20) NOT NULL,                 -- ACTIVE / LEFT / REMOVED
  joined_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (trip_id, firebase_uid)
);
```

- `expected_member_count` は候補段階の「1人あたり」計算に使い、招待前後で金額が変動しないようにする
- `trip_member` は実際に操作・投票・支出参加する人を表す。退出・削除後も投票と支出の参照を保つため物理削除しない
- Firebase Anonymous Authenticationから正規アカウントへ昇格しても同じUIDを維持するため、メンバー参照の付け替えは発生しない
- OWNERは旅行ごとに必ず1人。OWNER移譲後でなければ退出できない
- `trip.revision` は旅行内の業務データ変更と同じトランザクションで1増やし、WebSocketイベント欠落の検出に使う

### 5.1 候補（案A: 決まる前）

```sql
CREATE TABLE candidate (
  id           BIGINT PRIMARY KEY,
  trip_id      BIGINT NOT NULL,
  slot_id      BIGINT NOT NULL,        -- 所属する枠
  category     VARCHAR(20),            -- HOTEL / TRANSPORT / ACTIVITY / MEAL
  title        VARCHAR(200),
  url          TEXT,                   -- 宿・アクティビティの参照先
  normalized_url TEXT,                 -- トラッキングパラメータ除去後
  url_hash     CHAR(64),               -- 重複検出・キャッシュキー
  image_url    TEXT,                   -- OGPから取得
  est_amount   NUMERIC(12,2),          -- 概算
  est_basis    VARCHAR(10),            -- PER_PERSON / TOTAL
  day_index    INT,                    -- 何日目の枠か
  status       VARCHAR(10),            -- OPEN / REJECTED（採択の正本はslot.adopted_candidate_id）
  metadata_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                        -- PENDING / PROCESSING / COMPLETED / FAILED_RETRYABLE / FAILED_PERMANENT
  metadata_error_code VARCHAR(50),
  metadata_attempts INT NOT NULL DEFAULT 0,
  metadata_updated_at TIMESTAMPTZ,
  metadata_request_event_id UUID,       -- 現在有効な取得要求。遅延した旧jobの適用を防ぐ
  version      BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE candidate_vote (
  candidate_id BIGINT,
  member_id    BIGINT,
  choice       VARCHAR(10),            -- YES / ANY / NO
  reason       TEXT,                   -- NOの場合は必須
  PRIMARY KEY (candidate_id, member_id)
);
```

### 5.2 枠（スロット）

```sql
CREATE TABLE slot (
  id          BIGINT PRIMARY KEY,
  trip_id     BIGINT NOT NULL,
  category    VARCHAR(20) NOT NULL,   -- LODGING / TRANSPORT / ACTIVITY / MEAL / OTHER
  title       VARCHAR(100) NOT NULL,  -- 「宿 · 2泊」など表示用
  day_from    INT NOT NULL,           -- 何日目から（1始まり）
  day_to      INT NOT NULL,           -- 連泊・複数日をまたぐ場合
  units       INT NOT NULL DEFAULT 1, -- 泊数・片道数など概算計算用
  sort_order  INT NOT NULL,
  status      VARCHAR(20) NOT NULL,   -- OPEN / TENTATIVE / DECIDED / DECIDE_LOCALLY / SKIPPED
  deadline    DATE,
  est_per_person NUMERIC(12,2),       -- 概算（候補確定で無効化）
  adopted_candidate_id BIGINT,
  auto_generated BOOLEAN NOT NULL DEFAULT FALSE,
  version      BIGINT NOT NULL DEFAULT 0
);
```

- `auto_generated` により「自動生成したが一度も触られていない枠」を後から静かに畳める
- 日をまたぐ枠（連泊の宿）があるため `day_from` だけでは並べ切れず、`sort_order` は必須

### 5.3 確定予定

```sql
CREATE TABLE plan_item (
  id                BIGINT PRIMARY KEY,
  trip_id           BIGINT NOT NULL,
  from_candidate_id BIGINT,           -- 昇格元（トレーサビリティ）
  title             VARCHAR(200),
  starts_at         TIMESTAMPTZ,
  tz                VARCHAR(40),
  place_ref         TEXT
);
```

### 5.4 支出（事前・現地を統合）★最重要

```sql
CREATE TABLE expense (
  id           BIGINT PRIMARY KEY,
  trip_id      BIGINT NOT NULL,
  plan_item_id BIGINT,               -- NULL可（現地の突発支出）
  payer_id     BIGINT,               -- DRAFTではNULL可、CONFIRMEDで必須
  amount       NUMERIC(12,2),        -- DRAFTではNULL可、CONFIRMEDで正数必須
  currency     CHAR(3),              -- DRAFTではNULL可、CONFIRMEDで必須
  fx_rate      NUMERIC(14,6),        -- 決済日レートをスナップショット
  base_amount  NUMERIC(12,2),        -- 円換算（確定値）
  paid_at      TIMESTAMPTZ,          -- DRAFTではNULL可、CONFIRMEDで必須
  source       VARCHAR(10) NOT NULL, -- PLAN / MANUAL / OCR
  receipt_key  TEXT,                 -- Cloud Storageのオブジェクトキー
  upload_status VARCHAR(20),          -- PENDING / UPLOADED / FAILED（画像がある場合）
  status       VARCHAR(10) NOT NULL, -- DRAFT / CONFIRMED
  allocation_type VARCHAR(20),        -- EQUAL / WEIGHT / FIXED_AND_WEIGHT
  version      BIGINT NOT NULL DEFAULT 0,
  CHECK (
    status = 'DRAFT'
    OR (
      status = 'CONFIRMED'
      AND payer_id IS NOT NULL
      AND amount IS NOT NULL AND amount > 0
      AND currency IS NOT NULL
      AND paid_at IS NOT NULL
      AND base_amount IS NOT NULL AND base_amount > 0
      AND allocation_type IS NOT NULL
    )
  )
);

CREATE TABLE expense_share (
  expense_id   BIGINT,
  member_id    BIGINT,
  weight       NUMERIC(8,4),         -- 均等なら全員1.0
  fixed_amount NUMERIC(12,2),        -- 固定額指定時のみ
  PRIMARY KEY (expense_id, member_id),
  CHECK (weight IS NULL OR weight >= 0),
  CHECK (fixed_amount IS NULL OR fixed_amount >= 0)
);
```

**設計上のポイント3点**

1. **`plan_item_id` をnullableにする** — 事前支払い（予定に紐づく）と現地支出（紐づかない）が同じテーブルに同居できる。精算ロジックは `expense` と `expense_share` だけを見ればよく、フェーズを意識しない
2. **`fx_rate` と `base_amount` をスナップショット保存** — 為替は日々動くため、精算のたびに再計算すると金額がブレて揉める。決済日のレートを確定値として保存し、以後変えない。Go! Dutchが円換算に踏み込んでいない部分であり、明確な差別化ポイント
3. **`expense_share` を必ず持つ**（均等でも全員分レコードを作る） — 「このアクティビティはAとBだけ参加」「Cは飲まないから酒代は除外」など実際に揉めるケースが自然に表現できる。均等割り前提の設計だと後から入れるのが辛い

**DRAFTとCONFIRMEDの制約**

- DRAFTは写真だけでも作成できる。`payer_id`・`amount`・`currency`・`paid_at`・`base_amount`・`allocation_type` は未入力でよい
- CONFIRMEDへの遷移時に上記必須項目と、1件以上の `expense_share` を同一トランザクションで検証する
- オフライン撮影中はIndexedDBのローカルIDで保持し、再送時の冪等キーとして使う。サーバーにはアップロード開始時にDRAFTを作成する

**按分の不変条件**

- `EQUAL`: 対象者全員の `weight = 1`、`fixed_amount = NULL`
- `WEIGHT`: 対象者の `weight > 0`、`fixed_amount = NULL`
- `FIXED_AND_WEIGHT`: 固定額指定者には `fixed_amount`、残額の配賦対象者には `weight > 0` を設定できる
- 固定額の合計が `base_amount` を超える入力は拒否する
- 固定額を引いた残額をweight比で配り、最後に端数処理する。確定後の負担額合計は必ず `base_amount` と一致させる
- 対象者0人、weight合計0、負数、旅行に属さないメンバーは確定時に拒否する

### 5.5 精算

```sql
CREATE TABLE settlement (
  id             BIGINT PRIMARY KEY,
  trip_id        BIGINT NOT NULL,
  status         VARCHAR(20) NOT NULL, -- DRAFT / CONFIRMED / COMPLETED / SUPERSEDED
  calculated_at  TIMESTAMPTZ NOT NULL,
  confirmed_at   TIMESTAMPTZ,
  expense_cutoff TIMESTAMPTZ NOT NULL, -- この時刻までに確定した支出が対象
  version        BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE settlement_transfer (
  id             BIGINT PRIMARY KEY,
  settlement_id  BIGINT NOT NULL,
  from_member_id BIGINT NOT NULL,
  to_member_id   BIGINT NOT NULL,
  amount         NUMERIC(12,0) NOT NULL, -- MVPは日本円1円単位
  status         VARCHAR(20) NOT NULL,   -- PENDING / PAID / CONFIRMED
  paid_at        TIMESTAMPTZ,
  confirmed_at   TIMESTAMPTZ,
  CHECK (amount > 0),
  CHECK (from_member_id <> to_member_id)
);
```

- 精算状態は支出単位ではなく、相殺後の `settlement_transfer` 単位で管理する
- CONFIRMED後に支出が追加・修正された場合、既存精算は変更せず「未反映の変更あり」と表示し、新しいDRAFT精算を再計算する
- 新しい精算を確定すると、未完了の旧精算を `SUPERSEDED` にする。支払済み送金は実績として新しい計算へ織り込む
- 全transferが受取確認済みになった時点でsettlementを `COMPLETED` にする

### 5.6 Transactional Outbox

```sql
CREATE TABLE outbox_event (
  id             UUID PRIMARY KEY,
  trip_id        BIGINT NOT NULL,
  trip_revision  BIGINT NOT NULL,
  event_type     VARCHAR(80) NOT NULL,
  resource_type  VARCHAR(40) NOT NULL,
  resource_id    BIGINT NOT NULL,
  payload        JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL,
  published_at   TIMESTAMPTZ,
  attempts       INT NOT NULL DEFAULT 0,
  last_outcome_code VARCHAR(50)
);
```

- 業務データ更新、`trip.revision` の加算、Outbox追加を同一DBトランザクションで行う
- 配信処理は未配信行を取得してWebSocketへ送信し、成功後に `published_at` を記録する
- 少なくとも1回配信とし、クライアントは `eventId` で重複イベントを無視する
- URL取り込みやOCRのジョブ要求にも同じ仕組みを使い、DBコミット後にCloud Tasksへ投入する
- Cloud Tasksのタスク名にはOutbox IDを含め、Outbox再送による重複登録を抑止する。タスクハンドラー側も処理済み状態を確認して冪等にする

### 5.7 MVP実スキーマの設計判断

5.0〜5.6のDDLは概念説明用の例とし、MVPの実装可能なテーブル定義案は
`doc/DATABASE_SCHEMA.sql` に置く。Flyway migrationはM1以降で同案を前進可能な
単位へ分割する。特に次を確定する。

- MVPの金額列は整数円の `BIGINT` とし、CONFIRMED支出は `currency = 'JPY'`、
  `base_amount = amount` とする。多通貨用の `fx_rate` はMVPスキーマへ入れない
- `expense_share.final_amount` にCONFIRMED時の確定負担額を保存する。按分入力の
  weightやfixed amountだけから精算時に再計算しない
- `settlement_expense` と `settlement_share` に、精算計算時の支出version、
  支払者、支出額、確定負担額を固定する
- 再精算へ織り込んだ既払送金は `settlement_source_transfer` に送金version、
  金額、状態を固定する。時刻cutoffだけで精算対象を決めない
- 監査は追記専用の共通 `audit_event` を採用する。支出訂正と送金の代理操作では
  操作者、操作、対象、対象version、変更前後、trace IDを保存する。トークン、
  署名付きURL、画像の秘密情報を監査JSONへ保存しない
- 旅行に `vote_visibility`、候補に作成者・メモ・自由タグ、支出に作成者、
  画像にアップロード状態を持たせる
- `PENDING` / `FAILED` の領収書画像は作成から24時間保持し、Cloud Schedulerから
  30分周期で1回最大100件を回収する。Storage objectの削除成功（既に存在しない場合を
  含む）後だけDB行を削除し、Storage削除失敗時はDB行を保持して次回再試行する。
  `UPLOADED` は回収対象にしない
- 旅行横断のID取り違えをDBでも防ぐため、旅行配下の主要な外部キーは
  `(resource_id, trip_id)` の複合外部キーとする
- 候補は現在有効なmetadata取得要求のOutbox event IDを保持し、明示的な再取得後に
  到着した旧jobや重複jobが新しい状態へmetadataを再適用しない

DBのCHECK制約だけでは表現できない「ACTIVEなOWNERが必ず1人」「確定負担額合計と
支出額の一致」「採択候補と枠の一致」「確定済み精算の不変性」は、サービスの同一
トランザクション内で検証し、DB結合テストで保証する。

---

## 6. 機能仕様

### 6.1 枠の初期セットアップ

#### 6.1.1 中心的な緊張

- 枠を作りすぎる → 空枠が並び「宿題感」が出て離脱、予算バーも実態とかけ離れる
- 空白から始める → 枠という概念が伝わらない

**解決策**: 「決めないと旅行が成立しない枠」だけを自動生成する。

#### 6.1.2 生成ルール（最小骨組み）

旅行作成時に聞くのは **日程・目的地・人数** の3つのみ。

| 枠 | 生成条件 | 理由 |
|---|---|---|
| 宿 | 1泊以上なら1枠（連泊はまとめ、泊数を `units` に保持） | 最大の支出、早期予約が必須 |
| 往路の移動 | 宿泊旅行では1枠 | 早期予約で価格が大きく変わる |
| 復路の移動 | 宿泊旅行では1枠 | 同上 |
| 往復の移動 | 日帰り旅行では往路・復路をまとめて1枠 | 同上 |

したがって、2泊3日の初期枠は「往路の移動」「宿・2泊」「復路の移動」の
3枠、日帰りの初期枠は「往復の移動」の1枠とする。食事もアクティビティも
自動生成しない。途中で宿を変える場合は、作成後に宿枠を分割する。

#### 6.1.3 概算プレースホルダ

候補ゼロの枠にも概算金額を入れる。これがないと旅行作成直後の予算バーが¥0を指し、何の情報も与えない。

```java
public record CostBaseline(SlotCategory category, Region region, BigDecimal perPersonPerUnit) {}

// 例: 国内・宿 = 10,000円/泊/人、国内・航空 = 18,000円/片道/人、
//     国内・新幹線 = 12,000円/片道/人、海外・宿 = 15,000円/泊/人
```

精度は不要。ユーザーが実際の候補を入れた瞬間に上書きされるため、「だいたいこのくらいかかる旅行だ」という感覚が伝われば役目は終わり。

UI上は概算をグレー表示にして確定値と区別し、予算バーの下に「うち概算 ¥36,000」と内訳を出す。

#### 6.1.4 締切の自動設定

| カテゴリ | 出発日からの逆算 | 根拠 |
|---|---|---|
| 移動（航空・新幹線） | 14日前 | 早割の期限 |
| 宿 | 14日前 | 人気宿が埋まる |
| レンタカー | 7日前 | |
| アクティビティ | 7日前 | |
| 食事 | 2日前 | 予約不要なことも多い |

出発が近い旅行では逆算がマイナスになるため `max(算出日, 今日+1日)` でクランプ。ユーザーは変更可能。

#### 6.1.5 その他の仕様

- **連泊は1枠にまとめる** — デフォルトは「宿・2泊」で1枠。金額入力は「1泊あたり／合計」をトグルで選択。途中で宿を変える場合は「この枠を分割」で2枠に割る（結合は使用頻度が低くMVPでは省略）。分割は候補・採択予定がない宿枠に限り、枠の概算額は泊数比で2枠へ配分する
- **「現地で決める」ステータス** — `DECIDE_LOCALLY` を選ぶと締切通知が止まり、予算では概算だけ計上。日本の旅行では食事を意図的に決めないことが多く、「決めない」を正式な決定として扱えることが体験の質を左右する
- **追加は提案のみ** — 自動生成しないカテゴリはチップとして提案だけ置く（レンタカー、1日目の夕食、アクティビティ、自由に作る）。目的地の都道府県から提案チップの優先順を静的テーブルで変える程度で十分
- **テンプレートはMVPでは作らない** — 種類を揃えないと当たらず、揃えると保守が重い。将来やるなら「過去の自分の旅行を複製」の方が費用対効果が高い

### 6.2 候補比較画面（案Aの核）

#### 6.2.1 構造の核：「枠（スロット）」

候補をフラットに並べず、「1日目の宿」という枠にぶら下げる。これにより、

- 「どこがまだ決まっていないか」が一目で分かり、議論の進行が可視化される
- 各枠から1つずつ選べば、そのまま予算シミュレーションの入力になる
- 候補が10個並んでも「宿の話」「昼食の話」が混ざらない

#### 6.2.2 画面の設計判断

- **金額は「1人あたり」を主役にする** — 参加者が知りたいのは「自分がいくら払うか」。総額は副次情報として小さく添える
- **2番目以降の候補に「基準との差額」を出す**（`+¥3,400` `−¥5,300`） — 絶対額の羅列より差額の方が判断が速い。基準は「仮選択中の候補」
- **「仮選択」という状態を作る** — 候補をタップしてもその場では確定せず仮選択になる。切り替えると予算バーが即座に動く。確定（採択）は別アクション。この2段階により気軽に試せる
- **予算バーは常時表示** — 画面下に固定し、どの枠を見ていても現在の総額が見える。上限超過時は色を変え「あと¥3,200オーバー」と出す

#### 6.2.3 予算シミュレーション

```java
public BudgetSimulation simulate(Long tripId, Set<Long> selectedCandidateIds) {
    int members = tripRepo.getExpectedMemberCount(tripId);
    BigDecimal total = candidateRepo.findAllById(selectedCandidateIds).stream()
        .map(c -> c.estBasis() == PER_PERSON
                  ? c.estAmount().multiply(BigDecimal.valueOf(members))
                  : c.estAmount())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new BudgetSimulation(total,
        total.divide(BigDecimal.valueOf(members), 0, RoundingMode.CEILING),
        budgetRepo.findCapByTripId(tripId));
}
```

- 候補段階の計算人数には `trip.expected_member_count` を使う。招待済み人数を使うと、参加登録のたびに表示金額が変わるため
- MVPの候補金額は全員参加を前提とする。部分参加が分かった候補は、想定参加人数を候補ごとに指定できる拡張を将来追加する
- 確定支出では予定人数ではなく `expense_share` に登録された実際の負担者だけを使う

#### 6.2.4 投票の設計

**3値リアクション（いい / どちらでも / むり）**

- 星評価や5段階は集計の意味が曖昧になるため避ける
- 「どちらでもいい」を正直に表明できることが重要。日本のグループ旅行では本音が「なんでもいい」であることが多く、無理に賛否に振らせると投票が形骸化する
- **「むり」には理由の入力を必須にする** — 理由なき反対は議論を止めるだけだが、「予算オーバー」「相部屋は無理」と出れば次の候補探しの条件になる。最も価値を生む部分

**匿名／記名**

- 未投票者が誰かは催促に必要なので表示する
- 「Aさんがこれに×を付けた」の常時公開は空気を悪くするため、匿名／記名を旅行ごとに設定可能とし、デフォルトは匿名寄り

#### 6.2.5 決着のつけ方

投票を「決定」にしない。全会一致を待つと永遠に決まらない。

- 投票は参考情報。最終決定はOWNERまたはORGANIZERが「採択」ボタンを押す
- 枠ごとに締切を設定でき、締切が近づくと未投票者に通知
- 締切を過ぎたら「未投票＝どちらでもいい」として扱う旨を明示

**「決めるのは人間、支援するのがアプリ」** という割り切り。

**採択時の整合性**

- 採択の正本は `slot.adopted_candidate_id` とし、`candidate.status` にADOPTEDを重複保持しない
- 採択できるのはOWNERまたはORGANIZER。一般MEMBERは投票と候補追加まで
- 採択候補が同じ旅行・同じ枠に属することを検証し、`slot.status = DECIDED` と `plan_item` の作成を同一トランザクションで行う
- 旅行snapshotは採択後の予定を再表示できるよう、枠と同じ旅行に属する `plan_item` の一覧を含める
- 別候補へ変更する場合は既存 `plan_item` を更新し、紐づく確定支出があれば警告して明示確認を求める。支出自体は削除しない
- 採択解除は紐づく確定支出がない場合のみ可能。支出がある場合は候補変更か、支出の紐付け解除を先に行う

#### 6.2.6 比較軸

作り込みすぎると破綻するため、以下の折衷案を採る。

- **共通軸（固定）**: 金額、1人あたり、参照URL、メモ
- **カテゴリ別のヒント**: 宿なら「駅からの距離」「設備」など、入力欄のプレースホルダとして提示するだけ。構造化はしない
- **自由タグ**: `大浴場あり` `相部屋` など自由に付与

**入力の軽さ > 比較の精緻さ**。厳密に構造化すると入力が面倒になり誰も使わなくなる。

#### 6.2.7 横並び比較表

モバイル幅では2列が限界で横スクロールは読みにくい。デフォルトは縦積みカード＋差額表示とし、比較表は「候補4件以上のとき」かつタブレット/PC幅でのみ提供する。

### 6.3 候補追加フロー（URL取り込み）

**目標**: URLを渡してから3秒以内に候補が1件増える。

#### 6.3.1 入口は2つ

**1. URL貼り付け**

**2. Web Share Target API**（モバイルの摩擦削減効果が最大）

```json
{
  "share_target": {
    "action": "/candidates/import",
    "method": "POST",
    "enctype": "application/x-www-form-urlencoded",
    "params": { "title": "title", "text": "text", "url": "url" }
  }
}
```

楽天トラベルやじゃらんのアプリから「共有」で直接候補追加画面を開ける。

> **注意**: Androidは `url` にURLが入るが、**iOS Safariの共有では `text` にURLが混じって渡ってくる**ことが多い。`url` が空なら `text` から正規表現でURLを抜き出すフォールバックが必須。

- GETは共有URLやテキストがブラウザ履歴・アクセスログへ残るため使用しない
- 入力上限はtitle 500文字、text 10,000文字、抽出URL 10件とする。複数URLがある場合は選択画面を出す
- title・text・urlの本文をアクセスログやエラーログへ出さず、監視用には件数と処理結果だけを記録する
- Share TargetのPOSTだけでは候補を保存せず、ローカルの一時データとして確認画面へ渡す。ユーザーが旅行・枠を選んで確定した時点で、Firebase IDトークンによる認証済み候補作成APIを呼ぶ

#### 6.3.2 取り込みのフォールバック階段

1. **JSON-LD（schema.org）** — `Hotel` / `LodgingBusiness` / `Product` / `Restaurant`。最も構造化されており信頼できる
2. **OGP** — `og:title`, `og:image`, `og:description`, `og:site_name`
3. **HTMLの `<title>` と `<link rel=icon>`**
4. **手入力** — 何も取れなくてもURLとタイトル空欄で候補は作れる

4が重要。取り込み失敗で候補が作れないとそこで離脱する。**取り込みは「あれば嬉しい」であって必須の経路にしない。**

#### 6.3.3 金額は自動取得しない（重要な判断）

宿の価格は日付・人数・部屋タイプで変わるため、URLから取れる価格は実際に払う額と一致しない。中途半端に自動入力すると誤った金額で予算シミュレーションが走り信頼を失う。

代わりに**タイトル・画像・サイト名を自動で埋め、金額だけ数値キーパッドで入力させる**。入力欄にフォーカスが当たった状態で開けば実質2タップ。「1人あたり／総額」のトグルもここに置く。

JSON-LDから価格が取れた場合も、確定値ではなく `¥9,800〜（参考）` とプレースホルダに出すだけに留める。

#### 6.3.4 非同期取り込み（決定）

候補レコードは即時作成し、外部サイトのメタデータ取得だけをCloud Tasksで非同期実行する。外部サイトの応答速度や障害を候補追加APIの成否から切り離す。

```text
URL入力
  ↓
候補を即時作成（metadata_status = PENDING）
  ↓ 201 Created
仮カードを画面に表示
  ↓
Cloud Tasks → 内部HTTPハンドラー
（SSRF検証 → HTML取得 → JSON-LD/OGP解析）
  ↓
候補更新（COMPLETED または FAILED_*）
  ↓
WebSocketで変更通知
```

```http
POST /api/trips/{tripId}/slots/{slotId}/candidates
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "url": "https://example.com/hotel",
  "estAmount": 25000,
  "estBasis": "TOTAL"
}
```

候補リソース自体は作成済みなので、メタデータを待たず `201 Created` と `Location` を返す。レスポンスには作成した候補全体と `metadataStatus: "PENDING"`、`version` を含め、クライアント自身はWebSocketを待たずこのレスポンスで画面を更新する。

- 候補作成と永続ジョブ登録の取りこぼしを防ぐため、DB更新と同じトランザクションでOutboxへジョブ要求を保存する
- タスクpayloadは `candidateId` とし、タスク名にOutbox IDを使って重複登録を抑止する
- タスクは専用サービスアカウントのOIDCトークンでCloud Runの内部HTTPハンドラーを呼び、一般ユーザーからのアクセスを拒否する
- 接続タイムアウト3秒、処理全体5秒、最大本文2MB、リダイレクト最大5回
- DNS失敗、timeout、接続拒否・reset等の一時的transport failure、`429`、一時的な
  `5xx`は、1分から始まり最大10分となる指数backoffで最大2回再試行する。TLS証明書・
  hostname検証失敗、不正HTTP response、URL不正、SSRF拒否、恒久的な4xxは再試行しない
- 失敗しても候補を削除せず、手入力と「メタデータを再取得」を提供する
- 遅れて取得したメタデータでユーザー編集を上書きしない。優先順位は `ユーザー入力 > 取得メタデータ > 空欄`
- 候補作成APIは `Idempotency-Key` を必須とし、通信再送で候補を重複作成しない

JobRunrと `@Async` は使用せず、URL取り込みとOCRをCloud Tasksへ統一する。再試行回数・
backoff・全体の同時実行数はqueue設定で管理する。Cloud Tasksからは取得先hostを識別
できないため、外部host単位の同時接続はfetcherで2接続に制限する。

#### 6.3.5 メタデータ抽出実装（Spring Boot）

```java
@Service
public class UrlMetadataService {

    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private final SafeUrlFetcher safeUrlFetcher;

    public UrlMetadataService(SafeUrlFetcher safeUrlFetcher) {
        this.safeUrlFetcher = safeUrlFetcher;
    }

    public CandidateMetadata extract(String rawUrl) {
        URI url = UrlSanitizer.validate(rawUrl);   // SSRF対策

        Document doc;
        FetchResult result;
        try {
            // 自動redirectは使わない。各hopの全addressを検証し、
            // 検証済みIPへ接続をpinするfetcherを利用する。
            result = safeUrlFetcher.fetch(
                url, TIMEOUT_MS, MAX_BODY_BYTES);
            doc = Jsoup.parse(result.body(), result.finalUrl().toString());
        } catch (IOException e) {
            throw new MetadataFetchException(e);   // ジョブ側で再試行可否を分類
        }

        return jsonLd(doc)
            .or(() -> ogp(doc))
            .orElseGet(() -> titleOnly(doc))
            .withSourceUrl(result.finalUrl());
    }

    private Optional<CandidateMetadata> ogp(Document doc) {
        String title = meta(doc, "og:title");
        if (title == null) return Optional.empty();
        return Optional.of(new CandidateMetadata(
            title,
            meta(doc, "og:image"),
            meta(doc, "og:site_name"),
            meta(doc, "og:description")));
    }

    private String meta(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        return el != null ? el.attr("content") : null;
    }
}
```

JSON-LDは `<script type="application/ld+json">` をJacksonで読み、`@type` が該当typeなら `name` / `image` / `address` を拾う。配列や `@graph` でネストしているサイトが多いため、**再帰的に走査して該当typeを探す**実装にすると当たり率が上がる。

#### 6.3.6 SSRF対策（最重要）

ユーザー入力の任意URLをサーバーが取得するため、**内部ネットワークへの踏み台**にされる典型パターン。Cloud RunでもメタデータエンドポイントやGoogle Cloud APIへ到達し得る。

```java
public final class UrlSanitizer {

    public static URI validate(String raw) {
        URI uri = URI.create(raw.trim());

        if (!List.of("http", "https").contains(uri.getScheme())) {
            throw new InvalidUrlException("スキームが不正です");
        }
        assertPublicAddress(uri.getHost());
        return uri;
    }

    private static void assertPublicAddress(String host) {
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new InvalidUrlException("到達できないアドレスです");
                }
            }
        } catch (UnknownHostException e) {
            throw new InvalidUrlException("ホストを解決できません");
        }
    }
}
```

さらに以下を押さえる。

- **リダイレクト先も検証する** — `followRedirects(true)` では最初のホストだけ検証しても意味がない。リダイレクトを手動で追い**毎ホップでアドレス検証**する（最大5回程度）
- **DNSリバインディング対策** — 検証時と接続時で名前解決が変わる隙がある。厳密には解決済みIPに対して接続しHostヘッダを付ける方式
- **Cloud Run向け防御** — 80/443以外のポート、Googleメタデータサーバー、プライベート・ループバック・リンクローカルアドレスを明示的に拒否する。将来はURL取得を権限の少ない専用Cloud Runサービスへ分離する

短縮URL（`goo.gl`, `s.tabelog.com` など）は必ず来るため、リダイレクト追跡は実装必須。
具体的な全hop検証、DNS pinning、timeout、本文上限、失敗分類は
`doc/URL_FETCH_SECURITY.md` と `doc/security/url-fetch-policy.json` を契約とする。

#### 6.3.7 キャッシュと重複

- **正規化キー** — URLからトラッキングパラメータ（`utm_*`, `gclid`, セッションID等）を除去して正規化し、そのハッシュをキーにする
- **メタデータキャッシュ** — 正規化URL → 取得結果を24時間程度キャッシュ（Redis or PostgreSQL）
- **重複警告** — 同じ枠に同じ正規化URLの候補が既にあれば「既に追加されています」と表示

#### 6.3.8 サイト別の期待値

| サイト | OGP | JSON-LD | 備考 |
|---|---|---|---|
| 楽天トラベル・じゃらん | ○ | △ | タイトル・画像は取れる。価格は日付依存で不可 |
| Booking.com・Agoda | ○ | ○ | Bot判定が厳しく失敗することがある |
| Airbnb | △ | ✕ | JS描画が主体で静的HTMLからは取りにくい |
| 食べログ・ぐるなび | ○ | ○ | 比較的素直 |
| Googleマップ共有リンク | △ | ✕ | リダイレクト追跡が必須。座標は取れる |
| 公式サイト（旅館等） | ○ | ✕ | OGP頼み。無い場合は `<title>` |

取れないサイトは必ずあるため、**手入力フォールバックが本線**という設計が効く。

#### 6.3.9 規約面の注意

- `<head>` のメタタグを読む行為はリンクプレビューとして広く行われており一般に問題視されない
- **価格や在庫を本文からパースして蓄積する**のは多くの予約サイトの利用規約でスクレイピング禁止に触れる。差別化のために価格自動取得へ踏み込みたくなるが避けるべき（前述の「金額は手入力」はUX上も規約上も同じ結論）
- `robots.txt` の尊重、リクエスト頻度の抑制（同一ホストへの同時接続を1〜2に制限）、`User-Agent` に連絡先URLを含める

### 6.4 レシート取り込み（案Bの核）

**設計目標**: 会計直後の3秒。支出データが失われるのは「あとで入れよう」と思った瞬間。その場でやることを**撮影の1アクションだけ**に削る。

#### 6.4.1 撮る → 後で確定（deferred completion）★核心

支出を2段階の状態に分ける。

1. **未確定（DRAFT）**: 写真だけ存在する。撮った瞬間に完了
2. **確定（CONFIRMED）**: 金額・支払者・按分が入り、精算対象になる

レジ前では1で終わり。移動中や宿に戻ってから未確定トレイに溜まった数件をまとめて確定させる。バッジで「未確定 3件」と表示。

この分離が効くのは、**辛い作業（金額確認・按分）を暇な時間に移せる**から。しかも複数件をまとめて処理する方が1件ずつその場でやるより速い。

#### 6.4.2 オフライン前提

- 撮影画像は即座に**IndexedDBへ保存**し、UIは完了扱い
- アップロードはService Workerのキューに積み、Background Syncで回復時に送信
- オフライン中は未確定トレイに「アップロード待ち」として並べ、状態を隠さない

#### 6.4.3 OCRは提案であって確定値ではない

**金額さえ取れれば合格**と割り切る。店名や日付が外れても実害はほとんどない。

確定画面ではOCR結果を初期値として入れつつ常に編集可能にする。信頼度が低いときは金額欄にフォーカスを当てた状態で開き、数値キーパッドを即出す。

#### 6.4.4 確定画面の設計判断

- **「確定して次へ」で連続処理** — 確定するたびに次のレシートが出る。1件ごとに一覧へ戻す設計は遅い
- **立て替えた人のデフォルトは自分** — 撮影者が支払者であることが大半。ただし1タップで変更可能
- **按分のデフォルトは全員** — プリセット3つ（「全員」「前回と同じ」「金額を指定」）。特に**「前回と同じ」**が実用上よく効く（旅行中は同じ組み合わせが繰り返される）
- **1人あたりの金額を常時表示** — 按分を変えた影響が即座に分かる

#### 6.4.5 品目単位の割り勘はやらない（判断）

「品目ごとにタップして按分」は魅力的に見えるが実装しない。

- 品目抽出の精度が金額抽出よりずっと低い
- 品目ごとのタップは時間がかかる
- 旅行の食事は「みんなでシェアして均等」が9割で、品目単位が必要な場面は稀

代わりに「金額を指定」の逃げ道だけ用意する（「Aさんだけ¥2,000、残りは均等」はこれで足りる）。

#### 6.4.6 レシートがない支出こそ重要

自販機、屋台、チップ、駐車場、割り勘で渡した現金など、レシートが出ない支出は多い。

**レシート撮影と同格の入口として「金額だけ入力」を置く。** 金額パッド → 按分 → 確定の2タップ。撮影ボタンの隣に同じ大きさで配置する。むしろこちらが主経路になる可能性もある。

#### 6.4.7 重複の検出

4人グループで2人が同じレシートを撮る事故は必ず起きる。

```java
boolean isProbableDuplicate(Expense e) {
    return expenseRepo.existsByTripIdAndAmountAndPaidAtBetween(
        e.tripId(), e.amount(),
        e.paidAt().minus(10, MINUTES), e.paidAt().plus(10, MINUTES));
}
```

自動で消さず「同じ支出がすでにあります」と出して**ユーザーに判断させる**。同じ店で連続して2回払うこともあるため。

#### 6.4.8 海外での通貨と為替

- 通貨は旅行の主通貨をデフォルトとし、OCRで通貨記号が検出できたらそれを優先
- 確定時に決済日レートで円換算し、`fx_rate` と `base_amount` をスナップショット保存
- 確定画面には「≈ ¥2,150」と換算値を併記

Go! Dutchが円換算に踏み込んでいないため、**海外旅行では明確な優位点**になる。

#### 6.4.9 技術メモ

**OCRの選定**: 日本語レシートは Google Cloud Vision の `DOCUMENT_TEXT_DETECTION` が現実的。Tesseractは無料だが日本語のレシート書体で精度が落ちる。

**金額抽出のヒューリスティック**: 単純に「最大の数値」を取ると「お預かり ¥20,000」の方が合計より大きく失敗する。

1. `合計` `計` `お会計` `TOTAL` `小計` のキーワード行を探し、同一行または直下の数値を取る
2. 見つからなければ `お預かり` `預り` `現金` `おつり` `釣` を含む行を除外したうえで最大値
3. それも駄目なら空欄でユーザー入力

信頼度スコアを返し、低い場合はUIで金額欄にフォーカスを当てる。

**非同期処理**: アップロード → `202 Accepted` を即返し、OCRはCloud Tasksから専用内部HTTPハンドラーを呼んで処理する。結果はOutbox経由のWebSocketイベントで通知し、未確定トレイを開いたときや再接続時にはRESTで再取得する。撮影時にOCR完了を待たせないのが鉄則。

**画像圧縮**: クライアント側で長辺1600px程度にリサイズしてから送る。海外のモバイル回線を想定すると2MBを300KBに落とす効果は大きい。canvasでのリサイズで十分。

### 6.5 精算

#### 6.5.1 アルゴリズム

各メンバーの収支（立替額 − 負担額）を出し、債権者と債務者を大きい順に突き合わせる貪欲法。これは**厳密な最小送金回数を保証するものではなく、送金回数を抑えるヒューリスティック**として扱う。

```java
public List<Transfer> settle(Long tripId) {
    Map<Long, BigDecimal> balance = calcBalances(tripId);   // 正=もらう, 負=払う

    var creditors = balance.entrySet().stream().filter(e -> e.getValue().signum() > 0)
        .sorted(Map.Entry.<Long,BigDecimal>comparingByValue().reversed())
        .collect(Collectors.toCollection(ArrayDeque::new));
    var debtors = balance.entrySet().stream().filter(e -> e.getValue().signum() < 0)
        .sorted(Map.Entry.comparingByValue())
        .collect(Collectors.toCollection(ArrayDeque::new));

    List<Transfer> result = new ArrayList<>();
    while (!creditors.isEmpty() && !debtors.isEmpty()) {
        var cr = creditors.peek();
        var db = debtors.peek();
        BigDecimal amt = cr.getValue().min(db.getValue().abs());

        result.add(new Transfer(db.getKey(), cr.getKey(), amt));
        cr.setValue(cr.getValue().subtract(amt));
        db.setValue(db.getValue().add(amt));

        if (cr.getValue().signum() == 0) creditors.poll();
        if (db.getValue().signum() == 0) debtors.poll();
    }
    return result;
}
```

厳密な最小送金回数はNP困難だが、この貪欲法は必ず `残高が0でないメンバー数 − 1` 回以内に収まり実用上十分。UI・機能名・受け入れ条件では「最小送金」ではなく「送金回数を抑えた精算」に統一する。

#### 6.5.2 端数処理

MVPは日本円のみとし、支出ごとに次の順序で負担額を確定する。

1. `FIXED_AND_WEIGHT` の固定額を先に差し引く。固定額合計が支出額を超えたら入力エラー
2. 残額をweight比で高精度計算する
3. 各人の負担額を1円未満切り捨てする
4. `支出額 − 切り捨て後の負担額合計` の残余円を、小数部が大きい順に1円ずつ加算する（最大剰余方式）
5. 小数部が同じ場合は `member_id` 昇順をタイブレークにし、再計算しても結果が変わらないようにする

これにより、各支出について次の不変条件を必ず満たす。

```text
全expense_shareの確定負担額合計 = expense.base_amount
全メンバーの残高合計 = 0
全settlement_transferの送金額合計は債務残高合計と一致
```

多通貨対応時も、まず各支出を保存済みの `base_amount` に確定してから同じ端数処理を行い、為替レートを精算時に再取得しない。

---

## 7. ナビゲーション構造

### 7.1 中核判断：フェーズ適応

機能群が5つ（枠・候補・支出・精算・メンバー）あるため、素直にタブを5つ並べると「多機能で使いにくいアプリ」になる。**旅行のフェーズによって顔を変える**ことで解決する。

| フェーズ | 判定 | ユーザーがやりたいこと |
|---|---|---|
| 計画中 | 現地日付 < 出発日 | 候補を貼る、投票する、予算を見る |
| 旅行中 | 出発日 ≤ 現地日付 ≤ 帰着日 | 支出を記録する、今日の予定を見る |
| 精算 | 現地日付 > 帰着日 | 精算する、送金状況を追う |

フェーズが変えるのは**機能の有無ではなく強調**。旅行中に候補を立てることも、旅行前に支払いを記録することもあるため、**全機能は常に到達可能**にしたうえで初期表示と主アクションだけを切り替える。

- 「現地日付」は `trip.timezone` のIANAタイムゾーンで判定し、閲覧端末のタイムゾーンには依存しない
- 自動判定が実態と合わない場合、OWNERまたはORGANIZERは `phase_override` で「計画中」「旅行中」「精算」に切り替えられる
- 日帰り旅行は出発日当日を終日「旅行中」とする。中止時は手動で精算へ進める
- 手動切替は表示の強調だけを変え、候補・支出などのデータを自動確定または削除しない

### 7.2 タブは3つに固定

タブ自体はフェーズで変えない（位置が動くと筋肉記憶が壊れる）。

- **ホーム** — フェーズによって中身が変わる唯一の画面
- **旅程** — 枠、候補、確定した予定
- **支出** — 未確定トレイ、支出一覧、精算

精算を独立タブにしない。旅行につき1回しか使わない機能に常設の枠を与えると、残りの期間ずっと無駄な選択肢になる。支出タブの中に置き、帰着日を過ぎたらその中で目立たせる。

メンバー管理と設定はタブではなくホームのヘッダーに置く。

### 7.3 階層構造

```
旅行一覧
 ├─ ホーム（フェーズで変化）
 ├─ 旅程（枠と確定予定）
 │   └─ 候補比較・投票（枠ごとに決める）
 └─ 支出（記録と一覧）
     └─ 支出確定・精算（未確定トレイ）
```

3階層まで。深い階層は旅行中の片手操作で辛くなる。

### 7.4 ホームの中身（フェーズ別）

**計画中** — 締切が近い枠、自分がまだ投票していない候補、予算バー、出発までのカウントダウン。「あなたが今やるべきこと」が上に来る

**旅行中** — 今日の確定予定、未確定トレイの件数、今日の支出合計、1人あたり累計。予算バーは「あといくら使えるか」の表示に変わる

**精算** — 誰が誰にいくら払うかの一覧、送金状況、未精算の催促、旅の支出サマリー

### 7.5 主アクションは1つだけ

画面下部に常時1つの主アクションボタンを置き、意味だけをフェーズで変える。

| フェーズ | 主アクション |
|---|---|
| 計画中 | 候補を追加（URL貼付／共有から） |
| 旅行中 | 支出を記録（カメラ／金額入力の2択） |
| 精算 | 精算する |

複数のアクションを並べないことが、機能過多に見せないための最大の防御。

### 7.6 招待とゲスト参加

招待URLを開いた人に**ログインを要求しない**。

1. 招待URLを開く → 名前だけ入力 → 参加完了
2. Firebase Anonymous Authenticationで匿名UIDを発行し、そのUIDで旅行へ参加
3. 後から必要になったタイミング（機種変更、複数旅行の管理）でメールやGoogleログインを匿名UIDへリンクして昇格

ログイン必須にした瞬間、幹事以外が参加しなくなる。Go! Dutchが招待をURL共有だけで完結させているのも同じ理由で、ここで負けると勝負にならない。

匿名アカウントの資格情報を失うと通常の自己復帰はできないため、参加後に「登録するまでこの端末でのみ有効です」と明示し、任意でアカウント昇格を促す。

#### 7.6.1 権限

| 操作 | OWNER | ORGANIZER | MEMBER |
|---|---:|---:|---:|
| 候補追加・投票・支出追加 | ○ | ○ | ○ |
| 候補採択・締切変更・支出修正 | ○ | ○ | × |
| 精算の作成・確定 | ○ | ○ | × |
| メンバー招待・削除、権限変更 | ○ | × | × |
| 旅行削除、OWNER移譲 | ○ | × | × |

- MEMBERは自分が作成した未確定DRAFTのみ編集・削除できる。確定支出の訂正はORGANIZER以上が行い、変更履歴を残す
- 支出の送金元本人が `PAID`、送金先本人が `CONFIRMED` にできる。管理者が代理操作した場合も監査履歴を残す
- APIとWebSocket購読の両方で、対象ユーザーが該当旅行のACTIVEメンバーであることを検証する

#### 7.6.2 招待・復旧・退出

- 招待トークンは128bit以上の推測困難な乱数とし、保存時はハッシュ化する。有効期限は7日、OWNERがいつでも失効・再発行できる
- 参加完了時に使用した単回トークンは失効する。追加メンバー用には別トークンを発行する
- 同名は許可し、画面ではアバター色や参加順を併記する。同じFirebase UIDによる再アクセスは新規参加ではなく既存メンバーへ復帰させる
- 匿名アカウントの資格情報を失った場合は自力復旧不可。OWNERが旧メンバーを選んで復旧用単回リンクを発行し、新しいFirebase UIDへメンバー参照を移す
- アカウント昇格はFirebase Authenticationのcredential linkingを使い、匿名時と同じUIDを維持する
- 30日を超える旅行計画を保持するため、Firebase Authentication with Identity Platformの匿名アカウント自動削除は有効にしない
- 退出・削除は論理削除とし、過去の投票・支出・送金記録を保持する。未精算残高があるメンバーは精算完了まで削除できない
- OWNERは他のACTIVEメンバーへOWNERを移譲するまで退出できない
- 招待受取・名前入力にはIPおよびトークン単位のレート制限を設ける
- 招待・復旧tokenの受取は、PostgreSQLにIP hashおよびtoken hash単位の固定windowを
  保存し、それぞれ15分あたり5回までとする。6回目以降は`429 Too Many Requests`と
  `Retry-After: 900`を返す。IPアドレス自体は保存しない
- 復旧tokenの有効期限は24時間とする
- 招待発行responseは招待IDを返し、OWNERは
  `DELETE /api/trips/{tripId}/invitations/{invitationId}` で未使用tokenを個別に
  失効できる。複数の単回招待は並行して有効にできる
- Cloud Runで転送元IPを利用する場合は、Googleのproxyを経由する環境だけで
  `TRUST_GOOGLE_FORWARDED_FOR=true`を設定し、`X-Forwarded-For`の末尾にGoogleが
  追加した2要素のうちclient側を使用する。未信頼環境ではremote addressを使用する

#### 7.6.3 Firebase AuthenticationとSpring Security

匿名・正規ユーザーをFirebase UIDで統一し、Firebase IDトークンをSpring Bootで検証して共通のPrincipalへ変換する。

```java
public record AppPrincipal(String firebaseUid) implements Principal {
    @Override
    public String getName() {
        return firebaseUid;
    }
}
```

RESTではFirebaseクライアントSDKが取得したIDトークンをBearerトークンとして送る。

```http
Authorization: Bearer <Firebase ID Token>
```

Spring SecurityのカスタムフィルターでFirebase Admin SDKの `verifyIdToken()` を実行し、検証済みUIDから `AppPrincipal` を作って `SecurityContext` へ格納する。Bearerトークン方式のため、独自ゲストCookieとそのCSRF対策は持たない。

`ROLE_OWNER` など旅行ごとに変わる権限はFirebase custom claimsやAuthenticationへ固定で持たせない。認証は「誰か」だけを確定し、旅行内の認可は毎回 `trip_member` を参照する。

```java
@PreAuthorize(
    "@tripAuthorization.can(authentication.principal, #tripId, 'ADOPT_CANDIDATE')"
)
```

- `TripAuthorizationService` はPrincipalに対応するACTIVEメンバーとroleをDBから取得して権限表に照合する
- URLの `tripId` だけでなく、操作対象のslot・candidate・expenseなどが同じ旅行に属することをサービス層でも検証する
- WebSocketではSTOMP `CONNECT` ヘッダーにFirebase IDトークンを付け、`ChannelInterceptor` で検証後のPrincipalをセッションへ設定する
- WebSocketのSUBSCRIBE時にも、宛先の旅行に属するACTIVEメンバーかを `AuthorizationManager<Message<?>>` で検証する

### 7.7 URL設計

```
/                          旅行一覧
/t/{tripId}                ホーム
/t/{tripId}/plan           旅程
/t/{tripId}/plan/{slotId}  候補比較
/t/{tripId}/expenses       支出
/t/{tripId}/expenses/new   支出の記録
/t/{tripId}/settle         精算
/join/{inviteToken}        招待の受け取り
/recover/{recoveryToken}   メンバー資格情報の復旧
/candidates/import         Web Share Targetの受け口
```

Share Targetの受け口は**直近の計画中の旅行を初期選択**するが、保存前に旅行・枠・抽出URLを必ず確認させる。

### 7.8 リアルタイム同期のスコープ

**更新処理はRESTだけ、WebSocketはコミット済み変更の通知だけ**にする。STOMPのSENDで候補追加や投票を受け付けず、入力検証・権限・エラー形式・冪等性・監査の経路をRESTに一本化する。

- RESTは初期表示、一覧・詳細取得、全更新、入力検証、権限判定、楽観ロック、再接続後の回復を担当する
- 変更系RESTは変更後のリソース全体と `version` を返し、操作したクライアント自身はWebSocketを待たず画面を更新する
- WebSocketは他メンバーによる確定済み変更と、URLメタデータ・OCRなど非同期処理の完了を通知する
- 購読単位は旅行1件（`/topic/trip/{tripId}`）。旅行を開いている間だけ接続する

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "tripId": 123,
  "tripRevision": 842,
  "type": "CANDIDATE_METADATA_COMPLETED",
  "resourceType": "candidate",
  "resourceId": 456,
  "resourceVersion": 2,
  "occurredAt": "2026-07-24T10:15:30Z"
}
```

イベントは画面データ全体ではなく変更通知として小さく保ち、受信後は原則として対象リソースをRESTで再取得する。投票のように頻度が高く単純な変更だけ、表示用の最小payloadを含めてよい。

**配信対象**

- 候補の作成・更新・メタデータ取得完了／失敗
- 投票変更、候補採択
- 支出DRAFT作成・確定・更新
- 精算確定、送金状態変更
- メンバー参加・権限変更

DBコミット前には送信しない。業務更新と同一トランザクションで `outbox_event` を保存し、コミット後に配信してプロセス停止による通知消失を防ぐ。

**欠落回復と競合**

- 各イベントに `tripRevision` を含め、クライアントが保持する直前revisionから飛んだ場合はRESTで旅行スナップショットを再取得する
- WebSocket再接続時も、REST同期を完了してから購読を再開する
- 更新対象は `version` を持ち、REST更新時に `If-Match` またはリクエスト内versionで楽観ロックする
- 古いversionによる更新は `409 Conflict` を返し、現在値を添える。採択・支出・精算では最終書き込み勝ちを使用しない

### 7.9 空状態

作った直後がほぼ空になるアプリのため、空状態は手を抜けない。

- **旅行一覧が空** → 「旅行を作る」1つだけ。説明文は不要
- **枠に候補が0件** → 「URLを貼るか、共有から追加できます」＋入力欄をその場に置く。別画面へ飛ばさない
- **支出が0件** → 撮影と金額入力のボタンを大きく。一覧のレイアウトは見せない

### 7.10 やらないこと

- **ドロワーメニュー** — 3タブで足りる。作ると何でも放り込んでしまう
- **旅行をまたぐ横断機能**（全旅行の支出統計など） — 需要はあるが構造を複雑にする割に使われない
- **通知センター** — プッシュ通知で足り、アプリ内に履歴画面はまだ不要

---

## 8. 技術アーキテクチャ

### 8.1 スタック

| 層 | 技術 |
|---|---|
| フロント | React＋TypeScript＋ViteによるSPA/PWA、Firebase Hosting |
| ルーティング | React Router（Data Mode、クライアントサイドルーティング） |
| サーバー状態 | TanStack Query |
| フォーム・入力検証 | React Hook Form＋Zod |
| オフライン保存 | Dexie.js（IndexedDB） |
| Service Worker | vite-plugin-pwa＋Workbox |
| APIクライアント | OpenAPIから型生成＋`openapi-fetch` |
| WebSocketクライアント | `@stomp/stompjs`（ネイティブWebSocket、SockJSなし） |
| フロントテスト | Vitest＋React Testing Library＋MSW、Playwright（E2E） |
| スタイル | CSS Modules＋CSS Custom Properties |
| 認証 | Firebase Authentication（匿名認証＋アカウントリンク） |
| バックエンド | Spring Boot on Cloud Run |
| DB | Cloud SQL for PostgreSQL |
| リアルタイム | WebSocket（STOMP） |
| 非同期処理 | Cloud Tasks＋Transactional Outbox |
| 定期起動 | Cloud Scheduler（Outbox未配信回復） |
| 画像保存 | Cloud Storage |
| OCR | Google Cloud Vision（`DOCUMENT_TEXT_DETECTION`） |
| HTMLパース | Jsoup + Jackson（JSON-LD） |
| キャッシュ | PostgreSQL（MVP）。水平スケール時はMemorystoreを検討 |
| シークレット | Secret Manager |
| コンテナ保存 | Artifact Registry |
| 実行リージョン | `asia-northeast1`（東京） |

> Kafka・Kubernetes・JobRunrはMVPでは使用しない。金額整合性とトランザクションのためSpring Boot＋PostgreSQLを維持し、認証・非同期処理・静的配信をGoogle Cloud/Firebaseのマネージドサービスへ寄せる。

#### 8.1.1 フロントエンド技術選定

**採用方針: React＋TypeScript＋Viteの静的SPA**

本アプリはログイン後の操作画面が中心で、検索流入向けのページ単位SEOやSSRを必要としない。バックエンドもSpring Bootへ一本化済みであるため、Next.jsなどのサーバー実行を伴うフルスタックフレームワークは採用しない。Viteで静的ファイルを生成し、Firebase Hostingから配信する。これにより、Service WorkerとIndexedDBを中心としたPWA実装を直接制御でき、フロント用サーバーの運用も不要になる。

**ライブラリの責務**

| 領域 | 選定・運用方針 |
|---|---|
| UI | Reactの関数コンポーネントを使用する。画面単位で遅延読み込みし、共通UIは `src/components/`、機能固有コードは `src/features/` に置く |
| 型 | TypeScriptのstrict modeを有効にする。API型はSpring Bootが公開するOpenAPIを正本として生成し、手書きの重複DTOを作らない |
| ルーティング | React Router Data Modeを使い、旅行IDを含むURL、画面単位のエラー境界、認証後リダイレクトを管理する。データ取得の正本はTanStack Queryとし、loaderへ二重キャッシュしない |
| サーバー状態 | TanStack QueryでRESTの取得・更新・キャッシュ無効化を管理する。WebSocketイベントはデータ本体として保存せず、`tripRevision` を確認してQueryキャッシュの更新または旅行スナップショット再取得を行う |
| フォーム | React Hook Formで入力状態を局所化し、Zodでクライアント側の即時検証を行う。サーバーのProblem Detailsと`fieldErrors`が最終的な判定であり、同じ業務規則をフロントへ完全複製しない |
| オフライン | Dexie.jsに旅行スナップショット、未送信操作、撮影画像Blobを保存する。未送信操作には生成済み`Idempotency-Key`、対象version、作成時刻、再試行状態を持たせる |
| PWA | vite-plugin-pwaでmanifestとService Workerを生成し、Workboxでアプリシェルをprecacheする。APIの認証レスポンスはCache Storageへ保存しない |
| リアルタイム | `@stomp/stompjs`から`wss://api.tabikime.app`へ直接接続する。Firebase IDトークンをSTOMP `CONNECT` ヘッダーに付け、自動再接続後は購読前にREST同期する |
| スタイル | CSS ModulesとCSS Custom Propertiesを使う。MVPでは汎用UIフレームワークを導入せず、セマンティックHTMLと必要最小限のアクセシブルな部品を実装する |

**状態管理の境界**

- REST由来の状態はTanStack Query、永続的なオフラインキューはDexie.js、フォームはReact Hook Form、画面内だけの状態はReactのstateに置く
- ReduxやZustandは初期導入しない。複数画面にまたがるクライアント状態が実際に発生した場合のみ再評価する
- 金額は表示用文字列へ早期変換せず、API仕様に従う整数円として扱う。日付は`YYYY-MM-DD`、日時はISO 8601文字列、タイムゾーンは旅行のIANA TZを明示して扱う

**オフライン再送**

Background Syncだけに依存しない。オンライン復帰イベント、アプリ起動時、画面再フォーカス時にフォアグラウンドでもキューを送信し、対応ブラウザではService Workerからの再送も追加する。送信成功を確認するまでDexie.jsの操作を削除しない。`409 Conflict` は自動上書きせず競合解決UIへ送り、`401` はIDトークン更新後に再試行する。恒久的な4xxはキューへ残してユーザーに修正を促す。

**テストと品質ゲート**

- Vitest＋React Testing Library: 金額表示、投票、按分入力、フォーム検証、revision判定
- MSW: REST成功・Problem Details・遅延・オフライン・`409 Conflict` をブラウザに近い境界で再現
- Playwright: 匿名参加、候補追加、投票・採択、撮影DRAFT、オフライン再送、再接続後の復旧を主要ブラウザで確認
- ESLint＋Prettier＋TypeScript `tsc --noEmit` をCI必須とし、`npm run lint`、`npm run typecheck`、`npm test`、`npm run test:e2e` に統一する
- Service Worker更新、manifest、Share Target、オフライン起動はユニットテストだけで保証せず、Firebase Hostingのプレビューチャネルでも確認する

**見送る選択肢**

- **Next.js／React Router Framework Mode**: SSR、Server Components、フロント用サーバーが本要件では不要で、Spring Bootとの責務が重複する
- **Firebase App Hosting**: 動的レンダリングを使わないため、静的SPAに適したFirebase Hostingを維持する
- **Redux／包括的UIフレームワーク**: MVPの状態・画面規模に対して導入コストが先行する
- **SockJS**: 対象ブラウザはネイティブWebSocketを利用でき、フォールバック通信を追加すると認証・再接続の検証面が増える

#### 8.1.2 Cloud Run開発・クローズドβ構成

```text
リージョン: asia-northeast1
CPU: 1 vCPU
メモリ: 1〜2 GiB
課金方式: request-based billing
min instances: 0
max instances: 1
request timeout: 60分
```

- MVPはSpringのSTOMP Simple Brokerを使うため `max instances = 1` に制限する
- 開発・クローズドβではscale-to-zeroとコールドスタートを許容し、Cloud Runの固定費を抑える
- WebSocketは最大接続時間で切断される前提で自動再接続し、revision比較とREST再取得で状態を回復する
- Firebase Hostingは `tabikime.app` でPWAを配信し、REST／WebSocketは `api.tabikime.app` からCloud Runへ直接接続する。Hosting rewriteをWebSocket経路には使わない
- Cloud RunのCORSはFirebase Hostingの本番・プレビュードメインだけを許可し、Bearerトークンをログへ出さない
- JobRunrの常駐ワーカーは置かず、URL取得とOCRはCloud TasksからCloud Runの内部HTTPハンドラーを呼ぶ
- request-based billingではリクエスト外のOutboxポーリングに依存しない。通常は更新リクエストのコミット後に配信し、取りこぼし回復はCloud Schedulerから認証付き内部APIを定期実行する
- Cloud SQLは東京・単一ゾーン・非HAから開始し、自動バックアップ、Point-in-Time Recovery、削除保護を有効にする
- Cloud Storageも東京リージョンに置き、Cloud Runと同じプロジェクトのサービスアカウントでアクセスする
- 水平スケールが必要になった時点で、複数Cloud Runインスタンス間のWebSocketイベント配信にMemorystore for Redis Pub/Subを追加する
- 一般公開後はWebSocketのアクティブ時間、コールドスタート、月額を計測し、`min instances = 1` またはinstance-based billingへの変更を判断する

### 8.2 API設計

REST APIは `/api` 配下に置き、旅行を親リソースとして認可対象を明確にする。

```text
GET    /api/trips/{tripId}                              旅行スナップショット
GET    /api/trips/{tripId}/slots/{slotId}               枠・候補・投票
POST   /api/trips/{tripId}/slots/{slotId}/candidates    候補作成
PATCH  /api/trips/{tripId}/candidates/{candidateId}      候補更新
PUT    /api/trips/{tripId}/candidates/{candidateId}/vote 自分の投票
POST   /api/trips/{tripId}/slots/{slotId}/adoption      候補採択
GET    /api/trips/{tripId}/expenses                     支出一覧
POST   /api/trips/{tripId}/expenses                     支出DRAFT作成
PATCH  /api/trips/{tripId}/expenses/{expenseId}         支出更新・確定
POST   /api/trips/{tripId}/settlements                  精算DRAFT作成
POST   /api/trips/{tripId}/settlements/{id}/confirmation 精算確定
POST   /internal/tasks/candidates/{candidateId}/metadata Cloud Tasks専用
POST   /internal/tasks/expenses/{expenseId}/ocr          Cloud Tasks専用
POST   /internal/receipts/orphans/cleanup                 Cloud Scheduler専用
POST   /internal/outbox/dispatch                          Cloud Scheduler専用
```

**レスポンスとエラー**

- 作成は `201 Created`、非同期ジョブだけを受け付ける操作は `202 Accepted`、更新成功は更新後のリソースを `200 OK` で返す
- 一覧はカーソルページングとし、初期画面に必要な集約データは旅行スナップショットでまとめて返す。WebSocketイベントを履歴取得APIの代わりにしない
- エラーはProblem Details形式に統一し、`code`、`message`、`fieldErrors`、`currentVersion`、`traceId` を必要に応じて返す
- 冪等性が必要な候補・支出作成は `Idempotency-Key` を必須にする
- `/internal/tasks/**` と `/internal/outbox/**`、`/internal/receipts/**` は専用サービス
  アカウントのOIDCトークンだけを受け付け、Firebase利用者のIDトークンでは呼び出せない
- 更新は `version` による楽観ロックを使い、競合時は `409 Conflict`
- 認可失敗は、旅行の存在を外部へ漏らさない必要がある入口では `404 Not Found`、参加済みだが権限不足の場合は `403 Forbidden`

### 8.3 役割分担（3人）

| 担当 | 範囲 |
|---|---|
| Aさん（バックエンド／精算コア） | expense・share・精算アルゴリズム、端数処理 |
| Bさん（バックエンド／取り込み・同期） | URLメタデータ取得、SSRF対策、OCR連携、Cloud Storage、Cloud Tasks、Outbox、WebSocket同期 |
| Cさん（フロント） | 候補比較UI、予算シミュレーター、レシート撮影フロー、PWA化、Firebase Authentication、オフライン対応 |

---

## 9. MVPスコープ

### フェーズ1（コアの一周）

1. 旅行作成（日程・目的地・人数）→ 宿泊旅行は往路・宿・復路の3枠、
   日帰り旅行は往復移動の1枠を自動生成
2. 概算プレースホルダと締切の自動設定
3. 枠の手動追加・削除・並べ替え
4. 候補追加（URL貼付 → 候補即時作成 → Cloud TasksでOGP取得 → タイトル・画像を補完、金額手入力）
5. 候補比較・仮選択・予算シミュレーション
6. 3値投票と採択
7. 支出の手入力（撮影 → 未確定トレイ → まとめて確定、OCRなし）
8. 「金額だけ入力」の入口
9. 按分プリセット（全員／前回と同じ）
10. 精算（送金回数を抑えた精算）
11. 招待URL、Firebase匿名認証、アカウント昇格

**国内旅行・日本円のみ**で一周させる。この時点で「候補段階から1人あたりが見える」という差別化は成立する。

### フェーズ1の主要受け入れ条件

| 対象 | 受け入れ条件 |
|---|---|
| 候補予算 | 招待メンバーが増減しても `expected_member_count` が同じなら1人あたり金額は変わらない |
| 採択 | MEMBERは採択できず、OWNER/ORGANIZERによる採択後は枠・予定が矛盾しない |
| DRAFT | 写真だけで保存でき、オフライン再送を繰り返しても同じDRAFTが重複作成されない |
| 支出確定 | 必須項目または負担者が欠ける場合は確定できず、負担額合計は常に支出額と一致する |
| 端数 | 同じ入力を何度計算しても同じメンバーへ端数が配られる |
| 精算 | 全メンバーの残高合計が0となり、送金は残高非0のメンバー数−1回以内になる |
| 精算後の変更 | 支出追加・修正で確定済み精算を書き換えず、「未反映の変更あり」と再計算導線を表示する |
| ゲスト | 失効・期限切れ招待では参加できず、退出後も過去の投票・支出の表示が壊れない |
| 権限 | REST APIとWebSocketの双方で、非メンバーによる旅行データの参照・変更を拒否する |
| Share Target | POST受信だけでは候補を保存せず、旅行・枠・URLの確認後に1件だけ作成する |
| URL取り込み | 外部サイトがタイムアウトしても候補作成APIは3秒以内に成功し、失敗後も手入力できる |
| リアルタイム | WebSocketイベントを欠落・重複させても、revision比較とREST再取得で最新状態へ復帰できる |
| 楽観ロック | 古いversionによる候補採択・支出更新を拒否し、現在値を返す |
| フェーズ | 端末のタイムゾーンを変えても旅行の基準タイムゾーンに基づくフェーズは変わらず、手動上書きが優先される |

### フェーズ2

12. Web Share Target対応（モバイルの摩擦が激減。効果が最も大きい）
13. JSON-LD対応で取り込み当たり率を上げる
14. レシートOCR（金額抽出のみ）
15. オフライン対応（IndexedDB＋Background Sync）

### フェーズ3

16. メタデータキャッシュ、重複検出
17. 送金リンク生成・リマインド
18. 「現地で決める」ステータス

**多通貨と為替換算はMVP対象外**とする。MVPおよび上記フェーズの実装・受け入れ条件・担当範囲には含めず、国内旅行・日本円のみをサポートする。将来あらためて対応を決定した時点で、為替レート取得元を含む仕様を再検討する。

> OCRは「あると嬉しい」機能であって、体験の核は「撮る／入れるのが速い」と「後でまとめて処理できる」こと。この順序で作ればOCRの精度が出なくてもプロダクトは死なない。

---

## 10. リスク

| リスク | 内容 | 対策 |
|---|---|---|
| **機能過多による複雑化**（最大） | Go! Dutchは「マイナスデザイン」（操作を極力減らす）が強み。機能が多い分「多機能だけど使いにくい」に落ちやすい | 画面の入口は常に主アクション1つに固定。候補比較や精算は必要なときだけ開く階層に置く。機能の多さをトップ画面に出さない |
| **招待の摩擦** | ログイン必須にすると幹事以外が参加せず、一気に負ける | ゲスト参加（名前だけ）を本線にする |
| **SSRF** | ユーザー入力URLをサーバーが取得するため内部ネットワークへの踏み台になる | アドレス検証＋毎ホップ検証＋メタデータサーバー拒否。将来は最小権限の専用Cloud Runサービスへ分離 |
| **スクレイピング規約違反** | 価格自動取得は多くの予約サイトの規約に触れる | メタタグ読み取りに留め、金額は手入力 |
| **競合の追随** | Go! Dutchはユーザーフィードバックで機能を追加していく方針のため、空いている領域も埋まる可能性がある | 構造上やりにくいこと（精算を主機能から外す、決済実行まで踏み込む）ほど持続的な差別化になる |
| **精算領域の飽和** | Walica・Go! Dutch・Wariwariと精算特化の個人開発サービスが増えており、精算だけでは価値を主張できない | 精算は「あって当然」の機能と割り切り、計画フェーズ（案A）と現地キャプチャ（案B）を訴求の主軸にする |
| **OCR精度** | 日本語レシートの読み取りは完璧にならない | OCRは提案に留め、手入力へ即座に落ちられるUXにする。MVPではOCRなしで成立させる |

---

## 11. 設計判断

### 11.1 今回決定した事項

- **フロントエンド**: React＋TypeScript＋Viteの静的SPA/PWAをFirebase Hostingへ配信する。サーバー状態はTanStack Query、オフライン永続化はDexie.js、Service Workerはvite-plugin-pwa＋Workboxで構成し、SSR用フレームワークと包括的なグローバル状態管理は導入しない（8.1.1参照）
- **認証・認可**: Firebaseの匿名・正規ユーザーを同じUIDで扱い、検証済みIDトークンを `AppPrincipal` へ変換する。旅行内権限は `trip_member.role` を毎回参照する（7.6.3参照）
- **API・リアルタイム**: 更新はRESTへ一本化し、WebSocketはOutbox経由のコミット済み変更通知に限定する（7.8、8.2参照）
- **URL取り込み**: 候補を即時作成して `201 Created` を返し、メタデータはCloud Tasksで非同期取得する（6.3.4参照）
- **Google Cloud構成**: Firebase Hosting／Authentication、Cloud Run、Cloud SQL、Cloud Tasks、Cloud Storage、Cloud Visionを東京リージョン中心に構成し、Kubernetes・JobRunr・FirestoreはMVPで使用しない（8.1参照）
- **Cloud Run課金**: 開発・クローズドβはrequest-based billing、`min instances = 0`、`max instances = 1` とし、Outbox回復をCloud Schedulerから起動する。一般公開後に実測を基に再評価する（8.1.2参照）
- **通貨**: MVPは日本円のみとし、多通貨・為替換算と為替レート取得元の選定はMVP仕様から除外する（9参照）
- **確定負担額と精算スナップショット**: CONFIRMED時の円単位負担額を
  `expense_share.final_amount` に保存し、精算には対象支出version、支払者、
  支出額、負担額、織り込み済み送金を関連テーブルで固定する。時刻cutoffだけには
  依存しない（5.7参照）
- **監査モデル**: 支出訂正と送金代理操作を含む業務監査は追記専用の共通
  `audit_event` に統一し、秘密情報を変更前後JSONへ含めない（5.7参照）
- **投票公開設定**: `NAMED` では各投票をメンバー情報付きで返す。
  `ANONYMOUS` ではOWNER/ORGANIZERを含む全利用者に集計、自分の投票、自分が
  未投票かだけを返し、他者の投票者ID・選択・理由を返さない。監査上のactorは
  サーバー内に保持する
- **写真アップロード**: DRAFTを先に冪等作成し、その旅行とDRAFTへの編集権限を
  検証してから、有効期限の短い署名付きアップロードURLを発行する。MVPで許可する
  MIME typeはJPEG、PNG、WebP、1画像の上限は10 MiBとし、元ファイル名を
  オブジェクトキーに使わない。アップロード後は完了APIでStorage上の実サイズと
  content typeを検証してからUPLOADEDにする。未完了・失敗オブジェクトは定期削除
  対象とする
- **招待・復旧の制限**: 招待は7日、復旧は24時間で失効する。受取試行は
  PostgreSQLの固定windowでIP hash／token hashごとに15分5回へ制限する。
  招待はIDを持ち、OWNERが未使用tokenを個別失効できる
- **業務API初版**: `openapi/openapi.json` をM0の業務API契約とする。旅行を
  親リソースとし、書き込みは許可フィールドだけのrequest schema、競合は現在の
  安全なresource表現を伴うProblem Details、一覧は最大100件のカーソル方式とする
- **セキュリティ境界**: Firebase認証と旅行内認可を分離し、RESTとSTOMPを独立
  認可する。Cloud TasksとSchedulerは別service accountと環境別audienceで
  `/internal/**` を呼ぶ。詳細は `doc/SECURITY_BOUNDARIES.md` を契約とする
- **非同期event**: 共通envelopeとevent名は
  `doc/ASYNC_EVENT_CONTRACTS.md` および `openapi/openapi.json` の `TripEvent` を
  契約とし、少なくとも1回配送、event ID重複排除、revision gap時のREST同期を行う
- **SSRF制限**: 初期URLと全redirect先で全A/AAAA addressを検証し、検証済みIPへ
  接続をpinする。80/443、connect 3秒、全体5秒、展開後本文2 MiB、redirect 5回を
  上限とする（`doc/URL_FETCH_SECURITY.md` 参照）
- **フロントエンド画面・状態契約**: ルート、権限、空状態、レスポンシブ／
  アクセシビリティ要件は `doc/FRONTEND_SCREEN_MAP.md`、TanStack Query・フォーム・
  Dexie・一時状態の所有境界は `doc/FRONTEND_STATE_DESIGN.md` を契約とする。
  OpenAPI型は `openapi/openapi.json` から
  `frontend/src/api/generated/schema.d.ts` へ生成し、手書きDTOを作らない。
  主要フローの受け入れシナリオとAPI例は `doc/E2E_SCENARIOS.md` および
  `openapi/fixtures/m0-c3-api-examples.json` を使用する。
