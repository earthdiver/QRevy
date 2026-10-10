# QR Repair

[English](README.md) | 日本語

QR Repairは、通常のリーダーでは読み取れない損傷したQRコードを、オフラインで修復するAndroidアプリです。画像や個々のセルを調整し、修復候補を比較して、修復の根拠を確認できます。

Android 8.0以降と、QR Code Model 2のバージョン1～40に対応しています。Model 1、Micro QR、rMQRには対応していません。このリポジトリには完成したiOSアプリは含まれていません。

修復結果が元の内容と一致する保証はありません。重要な内容は、別の手段でも確認してください。

## 修復例

| 修復前 | 修復後 | 補完箇所 |
| --- | --- | --- |
| ![不規則な穴がある合成QR](docs/examples/bullet-holes/before.png) | ![修復後のQR](docs/examples/bullet-holes/after.png) | ![補完したセルを青色で表示](docs/examples/bullet-holes/details.png) |

[入力データ・設定・結果](docs/examples/bullet-holes/README.ja.md)を使って、この修復例を試せます。

## 使い始める

1. 画像やテキスト行列を読み込むか、カメラで撮影します。
2. QRコードの外枠、バージョン、向きを確認し、必要に応じて画像や個々のセルを調整します。
3. 「修復」を実行し、候補を確認します。復号したテキストのコピー、修復したQR画像の保存、作業の保存・再開ができます。

操作や設定の詳細は[ユーザーガイド](docs/USER_GUIDE.ja.md)を参照してください。

## ビルドとインストール

このリポジトリからビルドするには、JDK（Java開発キット）17とAndroid SDK（ソフトウェア開発キット）を用意し、SDK Platform 37をインストールしてください。その後、次のコマンドを実行します。

```bash
bash bootstrap-wrapper.sh
bash scripts/verify-wrapper.sh
./gradlew :androidApp:assembleDebug
```

生成されたAndroidパッケージ（APK）`build/androidApp/outputs/apk/debug/androidApp-debug.apk`を端末にインストールしてください。

## 関連文書

- [修復結果の見方](docs/RECOVERY_RESULT_FIELDS.ja.md)
- [QR Repairの修復の仕組み](docs/RECOVERY_STRATEGY.ja.md)
- [テキスト行列テンプレート](templates/text-matrices/README.ja.md)
- [プライバシー](PRIVACY.ja.md)
- [セキュリティ](SECURITY.ja.md)

## ライセンス

[Apache License 2.0](LICENSE)で公開しています。著作権表示、商標、依存ライブラリのライセンスは、[権利表示](NOTICE.md)と[サードパーティーの権利表示](THIRD_PARTY_NOTICES.md)を参照してください。
