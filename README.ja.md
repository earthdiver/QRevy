# QR Repair

[English](README.md) | 日本語

QR Repairは、通常のQRリーダーでは読み取れない損傷したQR Code Model 2を、端末上で解析・修復するAndroidアプリです。画像の状態を確認しながら、位置、濃淡、位置検出パターン、セルを手動で補助でき、復元結果の根拠も確認できます。

修復結果の正しさを保証するものではありません。重要な内容は、必ず別の手段でも確認してください。

UIでは、位置検出パターンを「Finder」、モジュールを「セル」、形式情報を「形式」と短く表示する場合があります。

文書の本文では「ビット」「モジュール」「コード語」「ECCレベル」「マスク」を使います。UI名・表示例・ソース識別子は原表記を維持します。UIの「セル」はモジュール、「Codeword」はコード語、「Finder」は位置検出パターンに対応します。

## 現在の状態

| 項目 | 状態 |
| --- | --- |
| Android | ソースからビルド・テスト可能。対応範囲はAPI 26以上 |
| iOS | 共有コアのXCFramework出力とブートストラップ例のみ。完成したXcodeアプリは未提供 |
| 対応QR | QR Code Model 2、バージョン1～40 |
| 非対応 | Model 1、Micro QR、rMQR |
| 配布 | リリース署名設定と公式署名済みパッケージは未提供 |
| ネットワーク | Androidの`INTERNET`権限を使用しない |

## 主な機能

- 画像ファイルの読み込みとカメラ撮影
- `0 / 1 / ? / *`だけで構成されたQR行列テキストの読み込み
- 回転、クロップ、四隅、濃淡、QRバージョンの調整
- ZXing-C++、格子周期、2つのFinderによる四隅・バージョンの初期推定
- 十分な画像一致がある場合のFinder位置・役割の自動設定、手動解除・指定と局所補正
- 対角の2つのFinderも、横・縦のタイミング照合で右上・左下へ自動設定（バージョン2以上、位置合わせパターン不要）。曖昧な場合は未設定、設定後は手動解除可能
- `0 / 1 / ? / *`によるセル編集とUndo／Redo
- QR構造と誤り訂正を利用した修復
- Binary、HEX、復号内容の確認
- 復号不能時のモード・文字数（`mode／count`）と、数字・英数字・バイト・漢字モードで先頭から連続して確定した内容の部分情報表示
- セル単位の修復理由と同じコード語の表示
- 修復結果の画像保存
- 作業履歴とセッション保存
- 終端後の追加QRセグメントの診断解析と、生ビット列・補正の由来の確認
- インターネット接続を使用しない端末内処理

対応対象はQR Code Model 2のバージョン1～40です。Model 1、Micro QR、rMQRには対応していません。

## 使い方

1. 画像を読み込むか、カメラで撮影します。`0 / 1 / ? / *`のQR行列テキストを読み込むこともできます。
2. 必要に応じて画像を回転し、QRの範囲をクロップします。
3. 自動推定された四隅、バージョン、黒／不明／白の閾値を確認し、必要なら調整します。観測が不安定なセルは`?`になります。色付き不明領域を使う場合は基本設定でONにできます（初期値はOFF）。
4. Finderの位置・役割を確認します。十分な画像一致があれば自動設定されます。誤っている場合は「解除」を押して緑のFinderをタップし、必要な役割を指定し直します。
5. 必要なら詳細設定でセルや探索条件を調整します。
6. 修復を開始し、復号内容とセルの修復理由を確認します。

セル編集、詳細設定、結果確認、保存、クラッシック／モダン画面の操作は[ユーザーガイド](docs/USER_GUIDE.ja.md)を参照してください。内部の探索順序、制約、Reed–Solomon処理、候補の検証方法は[修復戦略](docs/RECOVERY_STRATEGY.ja.md)にまとめています。

## ローカルビルド

必要環境:

- JDK 17
- Android SDK（compile/target SDK 37）
- Android API 26以上

```bash
bash bootstrap-wrapper.sh
bash scripts/verify-wrapper.sh
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest :androidApp:lint :androidApp:assembleDebug
```

debug APKは`build/androidApp/outputs/apk/debug/androidApp-debug.apk`に生成されます。

公開用application IDは`io.github.earthdiver.qrrepair`です。リリース署名、ストア識別子、公開Privacy Policy URLはリポジトリに含まれていません。公開前の確認については[リリースチェックリスト](docs/RELEASE_CHECKLIST.md)を参照してください。

## ドキュメント

- [修復戦略](docs/RECOVERY_STRATEGY.ja.md)
- [ユーザーガイド](docs/USER_GUIDE.ja.md)
- [検出ライブラリの比較](docs/DETECTOR_COMPARISON.ja.md)
- [初期グリッド推定とFinder自動設定](docs/ZXING_CPP_INTEGRATION.ja.md)
- [幾何近傍探索の合成画像比較](docs/GEOMETRY_SEARCH_STUDY.ja.md)
- [修復結果情報の読み方](docs/RECOVERY_RESULT_FIELDS.ja.md)
- [復号・修復理由の読み方](docs/REPAIR_REASONS.ja.md)
- [Kotlin Multiplatformの分離境界](docs/KMP_MIGRATION.md)
- [iOS統合計画](iosApp/README.md)
- [プライバシー](PRIVACY.ja.md)
- [セキュリティ](SECURITY.ja.md)
- [コントリビューションガイド](CONTRIBUTING.md)
- [第三者ライセンス](THIRD_PARTY_NOTICES.md)

## ライセンス

本プロジェクトは[Apache License 2.0](LICENSE)で提供されます。著作権表示と依存コンポーネントのライセンスは[NOTICE.md](NOTICE.md)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、`licenses/`を参照してください。

修復手法はRobert Xiaoによる“MMA CTF 2015 - QR Code Recovery”の記事から着想を得ていますが、文章・画像・コードを複製せず独自に実装しています。“QR Code”は株式会社デンソーウェーブの登録商標です。本プロジェクトは同社の提携・推奨を受けたものではありません。
