# Gakumas Localify Android

學園偶像大師 Android 版 Localify / LSPatch 補丁工具。

本專案提供兩種成品：

- `app-v...`：Localify 補丁 app。
- `game-v...`：已嵌入 Localify module 的遊戲補丁 APK。

## 一般安裝

1. 到 GitHub Releases 下載最新 `app-v...` 的 `GakumasLocalify-*.apk`。   
2. 下載最新 `game-v...` 的 `Gakumas_*_Localify_Embedded.apk`。
4. 安裝Localify app，遊戲補丁 apk。
5. 開啟 Localify app，更新翻譯包後從 app 啟動遊戲。

`game-v...` 乾淨的遊戲包來源預設為 APKPure 的 XAPK，GitHub Actions 會下載乾淨包後重新打包成補丁 APK，apk全自動透過github完成編譯，沒有使用本地端編譯，如不放心也可以自行編譯。

## 簽名與覆蓋安裝

`app-v...` release 的 app APK 使用固定 Android 簽名金鑰產出，後續同一簽名的新版可直接覆蓋安裝。

`game-v...` 是重新簽名的 patched game APK，不會和官方 / APKPure 原版簽名相同：

- 從官方版或 APKPure 原版切換到 patched game 時，通常需要先解除安裝原版。
- 從本專案舊版 patched game 更新到新版 patched game，通常可以直接覆蓋安裝。
- 若 Android 顯示簽名衝突，解除舊版後再安裝新版即可。

## 更新方式

大部分情況只需要在 app 內更新翻譯包。

App 更新和遊戲補丁更新彼此獨立；app 更新不代表需要重新安裝遊戲。

當 app 提示有新版本，或遇到 app 功能異常、亂碼、下載失敗等問題時，再更新 `app-v...` APK。

遊戲本體需要更新時，開啟 Gakumas Localify 更新遊戲即可。



## 自行編譯 app

Fork 或 clone 本倉庫後，可使用 GitHub Actions 的 `Android CI` 編譯 app。

若要產出簽名 release，需設定以下 repository secrets：

- `KEYSTOREB64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

推送 `app-v...` tag 後，Actions 會建立 app release，並附上 `gkms-app-update.json` 供 app 內更新檢查使用。

## 產生遊戲補丁 APK

`Game Patch Release` workflow 會在 GitHub Actions 產出 patched game APK。

常用來源：

- `apkpure`：自動下載 APKPure 最新 XAPK。
- `manual_xapk`：手動提供 XAPK URL 與遊戲版本。
- `google_play`：使用 Google Play 下載，需要額外 secrets。

workflow 會：

1. 下載乾淨遊戲包。
2. 合併 split APK。
3. 下載最新 Localify app APK 作為 module。
4. 移除 module 內建翻譯資源，保留使用者自選語言包模式。
5. 使用 pinned LSPatch embedded patcher 產出 patched game APK。
6. 建立 `game-v...` release 與 `gkms-game-patch.json`。

## Credits

- [原作者 / upstream: chinosk6/gakuen-imas-localify](https://github.com/chinosk6/gakuen-imas-localify)
- [LSPosed / LSPatch](https://github.com/LSPosed/LSPatch)
- [EFForg/apkeep](https://github.com/EFForg/apkeep)
- [REAndroid/APKEditor](https://github.com/REAndroid/APKEditor)
- [Kajaqq/gaku-patcher](https://github.com/Kajaqq/gaku-patcher)
- [GakumasTranslationData](https://github.com/fullpie/GakumasTranslationData)
