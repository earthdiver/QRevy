# 合成QRの復元性能計測

既存の検証用ハーネスと依存関係を使い、合成QRに対する現行の探索方針をデスクトップJVMで測定します。任意実行の計測で、製品の復元コードやAndroid設定は変更しません。外部画像や私的なペイロードは使用しません。

## 実行

既存のJDK 17とGradle依存関係が用意された環境で、リポジトリのルートから実行します。

```bash
./gradlew -p scripts/example-harness recoveryBenchmark -PbenchmarkArgs=--self-test --offline
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/recovery-baseline --samples 20 --warmups 5 --seeds 3' --offline
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/recovery-baseline
```

出力先は `scripts/example-harness` からの相対パスです。毎回新しいディレクトリを指定してください。計測は既存結果を上書きしません。集計は `summary.json` と `summary.md` を再生成します。build以下の結果はGit管理対象外です。計測中はビルドなどの高負荷処理を並行実行しないでください。

追加指定は `--case NAME`、`--checks N`（既定100000）、`--guard-ms N`（既定10000）です。Gradleプロパティは空白で引数を分割するため、出力パスに空白を含めないでください。シードは0から始まる整数で、`java.util.Random` を使います。マスクはシードを8で割った余りです。既定では39種類の入力それぞれに5回のウォームアップを行い、計780回測定します。同じ入力の反復は時間計測の標本で、独立した復元試験ではありません。少数の合成例から実環境の復元率は推定できません。

## 入力と探索方針

無損傷、7バイトの全消失、20バイトに分散した部分ビット消失、型番1-Lのパリティ3・4・5シンボル誤り、消失と誤りの混在、局所的な穴、型番3-Qの両ブロックの誤り、2つの有効なQRと整合する入力、誤ったFIXEDビット、作業メモリ0、中断要求（1000チェック後）を含みます。該当ケースではシードにより内容・マスク・損傷位置を変えます。曖昧なケースは別々に符号化したメッセージの相違を消し、残る観測と両方のQRが整合することを検証します。この2例は全候補の網羅的な正解表ではありません。

復元時は生成した型番・ECC・マスクを固定します。部分ビット上限12、リスト拡張1、作業メモリ32 MiB（意図的なメモリ停止ケースを除く）、保持設定は通常の既定値です。観測を厳密に保つケースは不一致上限を0にします。復号を分離するため、形式や向きの探索は計測しません。

既存の注入可能な時計を使い、初期の短いリスト探索枠も含めて時計を停止します。探索は中断チェック回数、既存のノード・保持上限、非常停止用の実時間上限で制限します。これは再現用の探索方針で、製品の時間配分とは異なります。チェック数はCPU命令数ではなく、実装変更で意味が変わる可能性があります。実時間上限に達した試行は明示し、決定的な同値性比較から除外します。中断遅延はエンジンが要求を認識してから戻るまでで、UIからの非同期伝達時間は含みません。

生データにはブロック長、ECC数、消失、既知ビット誤り、厳密なRS解空間の自由度、通常復号・消失位置を除いたJohnson半径に対する区分を保存します。部分バイトはこの区分では消失シンボルです。これは硬判定の問題の分類で、部分ビットやペイロード制約は追加情報を与えます。自由度がnullの場合は既知ビットをすべて保つ条件が矛盾しているという意味で、誤り訂正不能の証明ではありません。

## 指標の読み方

- `runs.jsonl`：保持したコード語全文・テキスト・修復と代替導出の根拠、生成元と既知の有効候補、候補・根拠のハッシュ、探索診断、省略、停止理由、中断遅延。
- `environment.json`：ソースのリビジョン、未コミット変更の有無、ソース指紋、JVM・OS、CPU数、ヒープ上限、計測設定。指紋は共通実装・Androidコア・ハーネスを対象とし、ソース全体や固定したビルド設定の保管を代替しません。
- `summary.md` / `summary.json`：中央値、nearest-rank方式のP95、候補数、生成元の復元、生成元と異なる単独候補、探索完了、反復の安定性、GC・メモリ指標。意図的なFIXED矛盾は復元対象の分母から除外します。

正解判定は文字列だけでなく、インターリーブ済みコード語全体で比較します。他候補も有効な別解の場合があり、その存在だけで誤復元とはしません。「誤った単独候補」は保持候補が1件で生成元と異なることを意味し、アプリが数学的一意性を宣言したという意味ではありません。候補・導出の省略や探索完了の状態も確認してください。COMPLETEは設定された探索方針の完了で、数学的に可能な全解の探索完了ではありません。

メモリ値はJVMの各ヒーププールのピークの合計で、既に生存するオブジェクトも含みます。ピークの時刻は一致するとは限らず、同時点の最大ヒープやプロセスRSSではありません。対応JVMでは復号スレッドの累積割当量も記録しますが、これは保持メモリではありません。入力生成・画像計測・JSON保存は復号の時間と割当量の測定外です。強制GCは行いません。JIT・GC・ケース順序が時間に影響するため、最適化判断の前に新しいJVMでも再測定してください。

画像処理は別計測です。1モジュール6画素と余白を持つ画像に対し、既存のZXing Java境界検出、グリッド検証、既知の幾何でのサンプリングを測ります。抽出値の一致を検証しますが、元の入力制約を置き換えません。Android JNI、カメラ、幾何の頑健性、実機電力、全体を通した復元の計測ではありません。現在のAPIは完了時に結果を返すため、最初の正解発見までの時間は取得できず、呼び出し完了までを測ります。

## 比較と検証

```bash
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/new-baseline --baseline scripts/example-harness/build/recovery-baseline
python3 -B -m unittest discover -s scripts -p test_recovery_benchmark_summary.py
python3 -B scripts/check-changes.py --dry-run
python3 -B scripts/check-changes.py
```

ケース・シード・入力ハッシュ・資源設定が一致する試行で、候補・根拠・完了状態の変化を報告します。一致しない入力も表示します。ハッシュ一致は保持した根拠の一致で、全解探索の証明ではありません。時間・メモリ制限による停止は、完了した試行と分けて確認してください。自己検証では入力の再現性、曖昧な正解候補、コード語の復元、FIXED保護、メモリ停止、中断を確認します。ハーネス・検証コードの変更にもリポジトリの全検証が必要です。

## 復号処理の詳細プロファイル

JDK 17内蔵のFlight RecorderでJava実行サンプル（要求周期1ms）と重み付き割当サンプル（要求上限1000件/秒）を記録できます。追加依存や製品コードへの計測処理は不要です。混在ケースについて、ウォームアップ後の通常計測と、別JVMでのプロファイル2回を実行します。

```bash
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/mixed-control --case mixed --samples 300 --warmups 50 --seeds 3' --offline
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/mixed-profile --case mixed --samples 300 --warmups 50 --seeds 3 --profile true' --offline
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/mixed-profile-repeat --case mixed --samples 300 --warmups 50 --seeds 3 --profile true' --offline
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/mixed-control
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/mixed-profile --baseline scripts/example-harness/build/mixed-control
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/mixed-profile-repeat --baseline scripts/example-harness/build/mixed-control
```

`profile.jfr` に生の記録、`profile.json` に全体・入力別の集計を保存し、集計スクリプトが `profile.md` も生成します。独自イベントで測定対象の復号呼び出しだけを区切り、同じスレッドかつ開始時刻以上・終了時刻未満のサンプルのみを集計します。ウォームアップ、入力解析、画像処理、JSON保存、他スレッド、区間外のJIT・コンパイラ処理は除外します。除外イベント数、欠落・途中で切れたスタック数も記録します。自己検証では時刻境界、再帰フレームの重複除去、集計値の合計を確認します。

実行比率はJava実行サンプルの割合で、経過時間の厳密な内訳や呼び出し回数ではありません。最も内側のコアメソッドの表には、その配下のライブラリ処理も含み、他行との重複はありません。呼び出し元を含む表（inclusive）は各メソッドを1サンプルにつき1回ずつ数えるため、行同士が重なり、割合を足してはいけません。インライン化などで共通の有限体演算にサンプルが集まる場合は、inclusiveと呼び出し経路で利用側のアルゴリズムを確認します。少数・ゼロのサンプルは処理コストがない証明にはなりません。

割当の重みは割当負荷の推定で、厳密な割当量・保持量ではありません。1つの重みには復号区間外の前回サンプル以降の割当が含まれる場合があり、時刻で絞っても呼び出しごとの正確なバイト数にはなりません。復号スレッドの割当量カウンタと照合してください。GC、セーフポイント、JIT、記録自体の負荷がサンプル分布や時間に影響します。候補・由来・探索診断が通常計測と一致することを先に確認し、速度には通常計測の値を使います。ウォームアップを増やしたことによる短縮を製品コードの高速化として扱いません。

少数の混在ケースから、全型番・全損傷に共通するボトルネックとは判断できません。独立した計測でも大まかな順位が一致することを確認してから、変更対象を選びます。高コストの補完処理が見つかっても、削除してよい根拠にはなりません。後続の最適化でも、対応する候補集合、制約、由来を維持します。

## GS/Wuプランナーの校正

`./gradlew -p scripts/example-harness plannerCalibration --offline`で、同じ訂正範囲に
対する両復号器を比較できます。合成データは6種類のブロック構成、最大2消失、3シードを
対象とし、対応しない訂正半径はスキップします。各入力・復号器について25回ウォームアップし、
実行順を交互に変えて100回測定します。毎回、元の符号語が復元され、候補集合が一致することを
確認します。CSVには未校正の計算量推定値と実行時間の中央値（ナノ秒）を出力します。
デスクトップJVMでの測定であり、Android実機のコストや追加誤り数2・3の範囲は校正しません。
ウォームアップによって順位が変わる結果だけでは選択基準を変更せず、安定した測定結果が
得られるまで既存のヒューリスティックを維持します。
