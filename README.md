# Look Camera

### 撮る前に、写真の空気を選ぶ。

Look Camera は、フィルムの色や質感をファインダーで確かめながら撮れる Android カメラです。街歩きも、友人とのスナップも、シャッターを押した瞬間の雰囲気を JPEG として残せます。

[**APK をダウンロード**](https://github.com/noppy3325-droid/look-camera/releases/latest) · [できること](#できること) · [開発](#開発)

## できること

- **18 種類の Look** — フィルム、カメラ、クリエイティブの 3 カテゴリから選択。強度とお気に入りも調整できます。
- **見たまま撮影** — 色と質感をライブプレビューに反映。GOLDEN NEGATIVE は暖かい中間調と落ち着いた影が魅力です。
- **撮影に集中** — タップフォーカス、ピンチズーム、露出補正、フラッシュ、構図グリッドを搭載。保存中も次の一枚を撮影できます。
- **すぐに見返す** — ギャラリー、左右スワイプ、拡大表示、Android の共有メニューに対応します。
- **端末内で完結** — 加工済み JPEG を写真ライブラリへ自動保存します。アカウントや通信は必要ありません。

## はじめる

[Releases](https://github.com/noppy3325-droid/look-camera/releases/latest) から APK をダウンロードしてインストールし、カメラへのアクセスを許可してください。Android 10 以上と OpenGL ES 3.0、`GL_OES_EGL_image_external_essl3` に対応した端末が対象です。

写真は `DCIM/Look Camera` に保存されます。ファインダー下の Look 名をタップすると、仕上がりを比較して選べます。

## 開発

Android Studio、Android SDK 37、JDK 17 以上を用意します。

```shell
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Windows の場合は `gradlew.bat` を使用します。実機テストはカメラ権限を許可してから `:app:connectedDebugAndroidTest` を実行してください。

Kotlin・Jetpack Compose・CameraX・OpenGL ES を使用しています。プレビューと JPEG は同じ Look 定義とシェーダーを利用し、撮影時の Look と強度を画像のメタデータに記録します。

## 対応状況

Xiaomi 14T と Android エミュレーターで基本操作を検証しています。機種や光の条件により写真の見え方は変わります。JPEG は最大辺 4096 px、約 1260 万画素を上限に保存します。配布 APK は開発用署名のデバッグビルドです。

## 使用素材

比較サムネイルには生成画像を使用しています。操作アイコンは [Google Material Icons](https://github.com/google/material-design-icons) を使用しています。依存ライブラリのライセンスはアプリ内の設定から確認できます。

