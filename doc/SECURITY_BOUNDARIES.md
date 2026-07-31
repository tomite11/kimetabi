# 認証・認可と脅威境界

この文書は `M0-B1` の成果物である。プロダクト仕様の正本は `doc/SPEC.md` とし、
実装時のセキュリティ境界、拒否順序、監査対象を定義する。

## 信頼主体

| 主体 | 提示する資格情報 | 信頼できる範囲 |
|---|---|---|
| PWA利用者 | Firebase ID token | Firebaseが検証したUIDだけ |
| 匿名参加者 | Firebase Anonymous ID token | 正規利用者と同じく検証したUIDだけ |
| Cloud Tasks | 専用service accountのOIDC token | 対象audienceと許可したservice account |
| Cloud Scheduler | 専用service accountのOIDC token | Outbox回復APIの起動だけ |
| PostgreSQL | アプリ用DB資格情報 | DB制約とcommit済みデータ |
| 外部Webサイト | なし | 応答内容を含め一切信頼しない |
| Cloud Storage | Cloud Runのservice account | 指定object keyのmetadataと内容 |

Firebase custom claims、リクエストbodyのmember ID・role、STOMP destination、
Cloud Tasks payload、Storageの申告content typeを認可判断の正本にしてはならない。

## Firebase認証と旅行内認可

```text
Bearer ID token
  → Firebase Admin SDKで署名・issuer・audience・有効期限を検証
  → 検証済みuidだけをAppPrincipalへ変換
  → trip_memberを (trip_id, firebase_uid) で検索
  → ACTIVE状態を確認
  → DB上のroleと操作権限を照合
  → nested resourceのtrip_idを照合
```

- 認証と旅行内認可を分離し、roleをFirebase custom claimsへ保存しない。
- ID tokenをdecodeするだけで使用せず、Firebase Admin SDKによる検証を必須とする。
- token、Authorization header、STOMP CONNECT headerをログへ出さない。
- 非メンバーや別旅行resourceへのアクセスは、旅行の存在を漏らさない
  `404 Not Found` とする。ACTIVEメンバーだがrole不足の場合は `403 Forbidden`。
- RESTとWebSocketは別々に認可する。REST認可済みであることをSUBSCRIBE許可へ
  流用しない。
- 認可失敗はuidそのものではなく、内部で相関可能なactor ID、trip ID、操作、
  outcome code、trace IDを構造化ログへ残す。

## 操作権限

`doc/SPEC.md` 7.6.1の権限表を正本とし、境界上は次を追加する。

- MEMBERによるDRAFT更新・削除は `created_by_member_id` が本人の場合だけ許可する。
- 支出・候補・枠・精算・送金のIDは、必ずURLのtripと同じ `trip_id` か検証する。
- 送金の `PAID` は送金元本人、`CONFIRMED` は送金先本人だけが通常操作できる。
  OWNER/ORGANIZERの代理操作は専用permissionと監査eventを必要とする。
- OWNER移譲は、移譲先が同じ旅行のACTIVEメンバーであることを確認し、ACTIVEな
  OWNERが常に1人になる同一トランザクションで行う。

## 招待・復旧token

```text
OWNERが発行
  → CSPRNGで128 bit以上
  → URL-safe tokenを利用者へ一度だけ返す
  → DBにはSHA-256 hashだけを保存
  → 受取時はhashで検索
  → expiry / revoked / usedを同一更新で検証
  → member作成またはUID復旧とtoken消費を同一トランザクションでcommit
```

- tokenはURLから受け取っても、APIへはrequest bodyで渡し、アクセスログへ残さない。
- tokenの平文、招待URL、復旧URLをアプリログ、監査JSON、分析eventへ残さない。
- 招待は7日で失効し、単回使用とする。OWNERは使用前に失効・再発行できる。
- 復旧tokenは対象memberへ固定し、使用時に新Firebase UIDが別memberへ割当済みで
  ないことを確認する。過去の投票・支出・送金のmember IDは変更しない。
- token照合の成功・失敗にかかわらず、外部responseはtokenの存在を推測しにくい
  同じProblem Detailsとする。
- IPおよびtoken hash単位でrate limitする。具体的な回数・window・保存方式は
  `doc/SPEC.md` に定義がないため、M0では決定しない。

## 脅威一覧

| 脅威 | 境界 | 必須対策 | 検証 |
|---|---|---|---|
| 偽造Firebase token | REST/STOMP CONNECT | Admin SDKで署名・issuer・audience・expiry検証 | 無効署名、期限切れ、別project |
| 水平権限昇格 | REST resource | ACTIVE membershipとnested trip ownership | 非member、別旅行ID |
| 垂直権限昇格 | 管理操作 | DB roleを毎回照合 | MEMBERによる採択・精算拒否 |
| STOMP盗聴 | CONNECT/SUBSCRIBE | 両段階で独立認証・認可 | CONNECT拒否、別旅行SUBSCRIBE拒否 |
| 招待総当たり | 招待受取 | 高entropy、hash保存、expiry、単回、rate limit | replay、期限切れ、失効 |
| 復旧乗っ取り | 復旧受取 | OWNER発行、対象member固定、UID競合拒否 | 別member、使用済みtoken |
| SSRF | URL metadata | `doc/URL_FETCH_SECURITY.md` の全制御 | offline test vector |
| task偽装・replay | `/internal/**` | 専用OIDC audience/identity、event ID冪等性 | Firebase token拒否、重複配送 |
| Outbox欠落 | commit/dispatch | 業務更新と同一transaction、Scheduler回復 | commit失敗、dispatch再実行 |
| event重複・欠落 | WebSocket | event ID重複排除、revision gapでREST同期 | 重複、逆順、gap |
| 画像差替え | Storage upload | server生成key、size/MIME再検証、短期URL | 不正MIME、過大、別旅行 |
| 秘密情報漏えい | log/audit/error | allowlist loggingとredaction | token・URLを含む入力 |
