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

リポジトリのルートで`./scripts/check-web.sh`を実行すると、ロックされたnpm依存関係の取得、1回のGradle起動でのJVMテストとエンジン生成、静的アプリのビルド、Node・ブラウザーテストを行います。Playwrightは開発サーバーとは別に、ポート4175でサーバーを起動・終了します。別のポートは`QREVY_TEST_PORT`で指定できます。ブラウザーテストの既定の並列数は利用可能なCPU数の半分（切り捨て、最小1・最大4）で、各テストのブラウザーコンテキストとストレージは独立しています。全5種類のブラウザー設定での検証を維持します。この既定値は`npm run test:browser`にも適用されます。直列実行には`QREVY_BROWSER_WORKERS=1`、並列数の調整には別の正の整数またはパーセント値を指定します。Playwrightの`--workers`オプションを明示した場合はそちらが優先されます。ブラウザーを選ぶ場合は`--project=desktop-chromium`などのオプションを渡します。

リポジトリの検証は`python3 -B scripts/check-changes.py --dry-run`で内容を確認し、`python3 -B scripts/check-changes.py`で実行します。文書のみの変更は空白の検査を行います。PWA固有の変更はAndroid検証を省略し、Android固有の変更はブラウザー検証を省略します。Android固有の復元処理・設定の変更ではAndroidの全単体テストを維持し、UI・セッションの変更では網羅的な幾何検証2種類のみ省略します。共用ファイルやリポジトリ全体のビルド・検証方針の変更は、両方を完全検証します。共用対象にはブラウザーのソース・テスト一覧、ブラウザー版4ファイルのAndroid側の元ソース、共用するJavaデコーダーのディレクトリが含まれ、`androidApp/`内のファイルも該当します。複数の範囲にまたがる変更は必要な検証を組み合わせます。各コマンドの所要時間と検証全体の所要時間も表示されます。

失敗時のトレースには操作履歴とDOMスナップショットを残し、連続スクリーンショットは記録しません。各テストで明示的に撮影するスクリーンショットは引き続き保存します。

Debian系Linuxでは、ビルド済みアプリ、テスト、ツール、インストール済みブラウザーのファイル、ホストのパッケージ、起動状態、実行環境が変わらない場合、完全なブラウザー検証の成功結果を12時間再利用できます。記録はローカルの`build/browser-validation/`に保存し、それより前の検証工程は毎回実行します。CI、対象を絞ったコマンド、ブラウザー・フォント・ライブラリーの独自指定、`check-changes.py --full`では必ずブラウザーテストを実行します。強制的に実行して記録を更新するには`QREVY_BROWSER_CACHE=refresh ./scripts/check-web.sh`、キャッシュを無効にするには`QREVY_BROWSER_CACHE=off`を指定します。直接の`npm run test:browser`も毎回テストを実行します。ブラウザーのREADME 2ファイルだけの変更は文書の検証対象とし、PWAに同梱するプライバシー・ライセンス文書はブラウザー検証の対象です。同梱されるルートの`NOTICE.md`は完全検証の対象です。

強制終了後にキャッシュを初期化する場合は、すべての検証プロセスが停止した後で`build/browser-validation/`を削除します。

## ソース構成

独立したGradleビルドで、リポジトリの検証済みWrapperを使います。`shared-sources.txt`に列挙した既存の`shared/`の処理と、Android側の復元・表示・セッション処理を直接コンパイルします。`shared-tests.txt`は共用する既存テストの一覧です。ブラウザー固有のコードとテストは`src/`、生成されたJavaScriptとブラウザー動作の検証は`tests/`に置きます。

`src/main/kotlin/overrides/`の4ファイルは、文字コード参照とメモリー予算をブラウザー向けに調整しています。ビルド時に、この明示的な置換以外の差分がないことを確認します。対応する復元ソースを変更する場合は、ブラウザー用のファイルも更新してください。AndroidのBitmapアクセスやブラウザー連携は`shared/commonMain`の外に置きます。

必要なZXingソースの部分集合とブラウザー用の文字コード対応は`vendor/zxing/`と`src/main/java/`にあります。文字コード表と`web/native-strings.js`の生成スクリプトは`scripts/`に置きます。共用するAndroidの表示文言を変更した場合は、`webApp/`から`python3 -B scripts/extract-ui-strings.py`を実行し、生成された文言を確認してください。生成したエンジン、依存ディレクトリ、テスト結果はGit管理から除外します。

## ライセンスと方針

Apache License 2.0。リポジトリの[ライセンス](../LICENSE)、[権利表示](NOTICE.md)、[依存ライブラリの権利表示](THIRD_PARTY_NOTICES.md)を参照してください。

[プライバシー](PRIVACY.ja.md) · [セキュリティ](SECURITY.ja.md)
