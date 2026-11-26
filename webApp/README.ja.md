# QRevy PWA

[English](README.md) | 日本語

iPhone・Android・パソコンのブラウザーで使えるQRevyです。画像や読めないセルを調整し、修復候補を比較できます。写真とQRコードの内容は端末内で処理し、初回の読み込み後はオフラインでも利用できます。修復結果が元の内容と一致する保証はありません。

インストール、操作、保存については[ユーザーガイド](docs/USER_GUIDE.ja.md)を参照してください。

## ビルドと起動

JDK 17、Node.js 22以降、QRevyリポジトリ全体のチェックアウトが必要です。PWAのビルドにAndroid SDKは不要です。初回の依存関係取得には通信が必要です。

リポジトリのルートから実行します。

```sh
./bootstrap-wrapper.sh
./scripts/verify-wrapper.sh
cd webApp
npm ci --ignore-scripts
npm run build:engine
npm run build
npm run dev
```

[ローカルのアプリ](http://127.0.0.1:4173)を開きます。別の端末で利用する場合は、`webApp/dist/`をHTTPSで配信してください。`dist/_headers`または同等のレスポンスヘッダーを配信先に設定します。このヘッダーは候補を保持する中断処理に必要です。クロスオリジン分離がない場合、中断はWorkerを終了して実行中の未確定候補を破棄します。既存の編集と以前の結果は保持します。

## 検証

`webApp/`から一度、テスト用ブラウザーをインストールします。

```sh
npx playwright install --with-deps webkit chromium firefox
```

リポジトリのルートで`./scripts/check-web.sh`を実行すると、ロックされたnpm依存関係の取得、JVMテスト、エンジン生成、静的アプリのビルド、Node・ブラウザーテストを行います。Playwrightは開発サーバーとは別に、ポート4175でサーバーを起動・終了します。別のポートは`QREVY_TEST_PORT`、ブラウザーテストの並列数は`QREVY_BROWSER_WORKERS`（既定値1）で指定できます。ブラウザーを選ぶ場合は`--project=desktop-chromium`などのPlaywrightオプションを渡します。

リポジトリ全体の検証は`python3 -B scripts/check-changes.py --dry-run`で内容を確認し、`python3 -B scripts/check-changes.py`で実行します。完全検証にはPWAも含まれます。

## ソース構成

独立したGradleビルドで、リポジトリの検証済みWrapperを使います。`shared-sources.txt`に列挙した既存の`shared/`の処理と、Android側の復元・表示・セッション処理を直接コンパイルします。`shared-tests.txt`は共用する既存テストの一覧です。ブラウザー固有のコードとテストは`src/`、生成されたJavaScriptとブラウザー動作の検証は`tests/`に置きます。

`src/main/kotlin/overrides/`の4ファイルは、文字コード参照とメモリー予算をブラウザー向けに調整しています。ビルド時に、この明示的な置換以外の差分がないことを確認します。対応する復元ソースを変更する場合は、ブラウザー用のファイルも更新してください。AndroidのBitmapアクセスやブラウザー連携は`shared/commonMain`の外に置きます。

必要なZXingソースの部分集合とブラウザー用の文字コード対応は`vendor/zxing/`と`src/main/java/`にあります。文字コード表と`web/native-strings.js`の生成スクリプトは`scripts/`に置きます。共用するAndroidの表示文言を変更した場合は、`webApp/`から`python3 -B scripts/extract-ui-strings.py`を実行し、生成された文言を確認してください。生成したエンジン、依存ディレクトリ、テスト結果はGit管理から除外します。

## ライセンスと方針

Apache License 2.0。リポジトリの[ライセンス](../LICENSE)、[権利表示](NOTICE.md)、[依存ライブラリの権利表示](THIRD_PARTY_NOTICES.md)を参照してください。

[プライバシー](PRIVACY.ja.md) · [セキュリティ](SECURITY.ja.md)
