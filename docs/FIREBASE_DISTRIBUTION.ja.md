# FirebaseによるAPKの限定配布

`Firebase APK distribution`は、非公開upstreamのデフォルトブランチで手動実行するワークフローです。同じリポジトリの公開済みReleaseから署名済みAPKを取得し、そのままFirebaseの指定グループへ配布します。ここでの「公開済み」は非公開リポジトリ内での公開であり、一般公開ではありません。プレリリースは利用でき、ドラフトは対象外です。ビルド・署名・タグやReleaseの作成・リポジトリの公開範囲変更は行いません。テスターにGitHubの閲覧権限は不要です。文書はReleaseに添付せず、Firebaseのリリースノートに要約します。

## 最初の手動配布

1. FirebaseでAndroidアプリ`io.github.earthdiver.qrrepair`を登録し、App Distributionを有効にします。配布だけならFirebase SDKやAndroidの通信権限の追加は不要です。
2. Firebaseで配布グループを作り、テスターを登録します。
3. 該当する[リリース確認](RELEASE_CHECKLIST.md)を完了し、このワークフローの外でデバッグ不可のrelease APKに署名して、非公開upstreamのReleaseに添付します。更新時も同じ署名鍵を使います。既存Android CIのrelease APKは**未署名**のため、そのままでは配布できません。
4. Firebase Consoleで署名済みAPKをアップロードし、グループを選択してテスター向けノートを入力します。Actionsへ移行する前に、招待・インストール・ノートの閲覧・更新をテスター端末で確認します。

## Actionsの設定

非公開upstreamに`firebase-distribution`というGitHub Environmentを作り、デプロイ可能なブランチをデフォルトブランチに制限します。次のEnvironment変数を設定します。実際の値はリポジトリに書き込みません。

| 変数 | 内容 |
| --- | --- |
| `FIREBASE_APP_ID` | FirebaseのAndroid App ID。Androidパッケージ名とは異なります |
| `FIREBASE_GROUPS` | 既存グループのエイリアス。複数の場合は空白なしのカンマ区切り |
| `APK_SIGNER_SHA256` | 信頼するAPK署名証明書のSHA-256フィンガープリント。コロン区切りも可 |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Google Cloud Workload Identityプロバイダの完全なリソース名 |
| `GCP_SERVICE_ACCOUNT` | 配布用サービスアカウントのメールアドレス |

署名証明書のフィンガープリントは、信頼できるローカル署名済みAPKに対して`apksigner verify --print-certs`を実行して確認します。ワークフローに署名鍵やパスワードを渡す必要はありません。

[公式認証Actionの設定手順](https://github.com/google-github-actions/auth#workload-identity-federation-through-a-service-account)に従い、GitHub Actions用のWorkload Identity Federationとサービスアカウントの借用を設定します。プロバイダ条件と借用権限は、このupstream（可能なら不変のrepository/owner ID）、デフォルトブランチ、`firebase-distribution` Environmentのsubjectに限定します。GitHub発行の全リポジトリを信頼する設定にはしません。対象の連携プリンシパルにサービスアカウントの`roles/iam.workloadIdentityUser`を付与します。サービスアカウントには対象Firebaseプロジェクトの`roles/firebaseappdistro.admin`を付与し、Firebase App Distribution APIを有効にします。認証は短期間の資格情報を使い、サービスアカウントのJSON鍵は登録しません。通常のビルドジョブに配布権限を与えないでください。

Release取得には同じリポジトリの読み取り専用`GITHUB_TOKEN`を使うため、別リポジトリ用のトークンは不要です。外部ActionsはコミットSHAで固定しています。Firebase CLI 15.30.2はMITライセンスの配布時ツールで、Android実行時依存関係には追加しません。npmの推移依存関係はインストール時に解決されるため、CLIの依存グラフ全体が固定されるわけではありません。CLI・Actionの固定値を更新するときは内容を確認してください。

## 配布の実行

デフォルトブランチの **Actions → Firebase APK distribution → Run workflow** で次を指定します。

- `release_tag`: upstreamの公開済みReleaseのタグ。
- `apk_asset`: 署名済みAPKの正確なファイル名。パスやワイルドカードは不可。
- `release_notes`: ルートに`RELEASE_NOTE.txt`がない場合だけ使用する任意の本文。

GitHubのWeb画面から実行する場合は、upstreamのデフォルトブランチで`README.md`と同じ場所にUTF-8の`RELEASE_NOTE.txt`をコミットしておきます。このファイルを入力欄より優先し、改行を保持します（UTF-8 BOMがあれば除去します）。この場合、`release_notes`欄は空欄で構いません。読み込むのは選択したAPKのリリースタグではなく、**実行時にチェックアウトされたデフォルトブランチのワークフローコミット**のファイルです。対象APKに合う内容か確認してください。ファイルが空、不正なUTF-8、ディレクトリ、シンボリックリンクの場合は、入力欄へ切り替えずアップロード前に失敗します。ファイルも入力欄も本文がなければ失敗します。

変更点、操作案内、確認項目、制限事項、テスターが利用できる報告先を記載し、非公開upstreamへのリンクや内部向けの内容は含めません。使用したノートの取得元は実行サマリーに記録します。

`RELEASE_NOTE.txt`がない場合は、複数行の代替ノートを非公開upstreamのチェックアウトからGitHub CLIで指定することもできます。

```sh
gh workflow run firebase-distribution.yml \
  -f release_tag=YOUR_EXISTING_TAG \
  -f apk_asset=YOUR_SIGNED_APK_FILENAME.apk \
  -F release_notes=@docs/FIREBASE_RELEASE_NOTES.md
```

[ノート例](FIREBASE_RELEASE_NOTES.md)は実行前に対象APKに合わせて確認・調整してください。例をルートの`RELEASE_NOTE.txt`へコピーして編集・コミットできます。選択された本文を改行を保持して配布し、upstreamのRelease本文は自動転記しません。リポジトリに日本語文書を保存する場合は`.ja.md`で終わる名前を使います。

GitHubが提供する場合はAPKのダイジェストを照合し、APK署名、登録した署名証明書、QR Repairのパッケージ名、デバッグ不可の設定をFirebase認証前に検証します。実行サマリーにはasset IDとAPKのSHA-256を記録します。既存Android/KMP CIは独立して維持されます。配布ジョブは対象APKのリリース確認が完了していることを前提にします。

FirebaseへAPKとノートを送信し、指定グループにアクセスを付与します。メンバーはFirebaseで管理します。再実行では同じビルドのノート更新や再配布が起こり得ますが、ロールバックや通知の再送を保証するものではありません。アップロード・配布後に失敗する場合もあるため、再実行前にFirebaseの状態を確認してください。GitHubの権限削除ではFirebaseの権限は削除されず、いずれもダウンロード済みのコピーは回収しません。

参考: [Firebase CLIによる配布](https://firebase.google.com/docs/app-distribution/android/distribute-cli)、[サービスアカウント認証](https://firebase.google.com/docs/app-distribution/authenticate-service-account)。
