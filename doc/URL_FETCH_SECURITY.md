# URL metadata取得セキュリティ

この文書は `M0-B3` のSSRF契約である。実装は
`doc/security/url-fetch-policy.json` と同じ制限を使用し、HTTP clientの自動
redirectを無効にする。

## 検証順序

各初期URLと各redirect先について、接続前に必ず次を繰り返す。

1. URLを標準parserで解析し、userinfoを拒否し、fragmentを送信しない
2. schemeは `http` / `https` だけを許可する
3. portは明示・暗黙を問わず80 / 443だけを許可する
4. IP literalは即時分類し、hostnameは全A/AAAA responseを解決する
5. 解決結果が1件以上あり、全addressがpublicであることを確認する
6. 検証済みaddressの1つへ接続先をpinし、TLS SNIとHostは正規化hostnameを使う
7. connect先IPが検証集合から変わった場合はDNS rebindingとして拒否する

IPv4はloopback、private、link-local、carrier-grade NAT、unspecified、multicast、
reservedを拒否する。IPv6はloopback、ULA、link-local、unspecified、multicast、
reserved、documentation prefixを拒否する。IPv4-mapped IPv6は埋め込まれたIPv4も
同じ規則で検証する。`169.254.169.254` とmetadata hostnameは明示的にも拒否する。

整数、16進、8進、省略形など曖昧なIPv4表現はDNSへ渡さず拒否する。IPv4 literalは
canonical dotted decimal、IPv6 literalは角括弧形式だけを受け付ける。

## 制限

| 制御 | 値 |
|---|---:|
| connect timeout | 3,000 ms |
| operation全体のdeadline | 5,000 ms |
| response body上限 | 2,097,152 bytes |
| redirect上限 | 5回 |
| 許可port | 80, 443 |
| 同一hostへの同時接続 | 2 |

- 5秒deadlineはDNS、全redirect、header、body読込を含む単一deadlineとする。
- bodyは展開後の読込byte数で制限し、`Content-Length` 超過は読込前、chunkedや
  虚偽headerはstream中に中止する。
- redirectは相対URLを現在URLに対して解決した後、次hopとして完全に再検証する。
- redirect response bodyは解析せず破棄する。6回目のredirectを追跡しない。
- 外部responseのHTML、JSON-LD、header、error本文をログへ出さない。

## 失敗分類

| outcome | metadata状態 | 自動再試行 |
|---|---|---|
| DNS失敗 | `FAILED_RETRYABLE` | 対象 |
| 一時的`5xx` | `FAILED_RETRYABLE` | 対象 |
| connect/total timeout | `FAILED_RETRYABLE` | 対象 |
| `429` | `FAILED_RETRYABLE` | 対象 |
| connection refused/reset、unexpected EOF | `FAILED_RETRYABLE` | 対象 |
| TLS証明書・hostname検証失敗 | `FAILED_PERMANENT` | なし |
| 不正HTTP response・展開不能な本文 | `FAILED_PERMANENT` | なし |
| URL、scheme、port不正 | `FAILED_PERMANENT` | なし |
| non-public address / rebinding | `FAILED_PERMANENT` | なし |
| redirect超過 | `FAILED_PERMANENT` | なし |
| 本文2 MiB超過 | `FAILED_PERMANENT` | なし |
| その他の恒久的`4xx` | `FAILED_PERMANENT` | なし |

失敗時も候補を削除せず、利用者の手入力と明示的な再取得を許可する。
再試行は初回を含め最大3回とし、Cloud Tasksの1分から最大10分となる指数backoffを
使用する。取得先の`Retry-After`はMVPでは再試行時刻に反映しない。
