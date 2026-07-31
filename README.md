# タビキメワリ

旅行の候補決めから支出・精算までを一つにつなぐPWAです。プロダクトと
アーキテクチャの正本は `doc/SPEC.md`、実装順序は
`doc/IMPLEMENTATION_PLAN.md` を参照してください。

## 必要な環境

- Java 21

Mavenはリポジトリに含めるMaven Wrapperを使用します。

## バックエンド

```shell
cd backend
./mvnw spring-boot:run
```

別のターミナルから停止する場合は、起動したターミナルで `Ctrl+C` を入力します。

テストは次のコマンドで実行します。

```shell
cd backend
./mvnw test
```

フロントエンド、PostgreSQL、CIの手順は、それぞれM1-C1〜C3、M1-A2、
M1-B3で追加します。
