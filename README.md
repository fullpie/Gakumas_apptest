# Gakumas Localify Android

這是學園偶像大師 Android 版的 Localify / LSPatch 管理工具。

## 使用方式

1. 到本倉庫的 Releases 下載最新 `app-v...` 版本的 `GakumasLocalify-*.apk`。
2. 安裝並開啟 app。
3. 在 app 內檢查更新：
   - App APK：更新這個管理工具本身。
   - Game Patch：下載雲端預先 patch 好的遊戲 APK。
   - 翻譯包：仍由 app 內的翻譯資源更新功能處理，不需要觸發本倉庫 Actions。

`app-v...` 和 `game-v...` 是分開的 release channel。一般翻譯包更新只需要更新翻譯包 release，不需要重新 build app 或重新 patch 遊戲 APK。

## 下載與安裝限制

App 和 patched game 都是透過 GitHub Releases 下載。下載 APK 後會交給 Android 系統安裝器處理。

Android 覆蓋安裝要求「package name 相同」且「簽名相同」：

- App 從 `app-v3.2.3` 起使用固定 Actions signing secrets 簽名。若你手機上已安裝舊的 debug 或不同簽名版本，第一次升級可能需要先解除安裝；之後同一簽名的新版應可覆蓋安裝。
- Patched game APK 是 LSPatch 重新簽名後的 APK，不能覆蓋官方 Google Play / APKPure 原版遊戲。它只能覆蓋同 package 且同簽名的舊 patched game。
- 如果安裝時出現簽名衝突，先解除安裝舊版再裝新版。

## 自行編譯 App

你也可以 fork 或 clone 本倉庫後用 GitHub Actions 編譯 app。`Android CI` 會產出 APK artifact；推送 `app-v...` tag 時會建立 app release，並附上 `gkms-app-update.json` 供 app 內更新檢查使用。

建議在 repo secrets 設定固定簽名資料，否則 release 會退回 debug APK，未來較容易遇到覆蓋安裝簽名衝突：

- `KEYSTOREB64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

## Game Patch Release

`Game Patch Release` workflow 會在 GitHub Actions 雲端建立 patched game APK。

預設來源是 APKPure：

- `source`: `apkpure`
- 不需要手動貼 XAPK URL。
- workflow 會先用 APKPure latest XAPK endpoint 偵測版本；若版本已經存在對應 `game-v...` tag 且沒有 `force`，會直接跳過。
- 若版本預查被擋，workflow 會下載 XAPK、讀 manifest 取得實際版本，再用 tag 判斷是否需要繼續。

可用的手動來源：

- `manual_xapk`：手動提供 `.xapk` 直鏈與 `game_version`。
- `google_play`：保留作為 Google Play 來源，需要設定 `PLAY_EMAIL` 與 `AAS_TOKEN` secrets。

patch 流程會下載 XAPK 或 split APKs，使用 `APKEditor` 合併，再用本 repo 的 `app/libs/lspatch.jar` 產出 embedded-mode patched APK，最後建立 `game-v...` release 和 `gkms-game-patch.json`。

## Actions 觸發範圍

- `Android CI` 只在 app 原始碼、Gradle 設定、或 app build workflow 變動時自動執行；README、翻譯包更新、game patch workflow 變動不會觸發 app build。
- `Game Patch Release` 可手動執行，也可排程檢查遊戲新版本。沒有新遊戲版本時應跳過，不重新發同版本 release，除非手動設定 `force=true`。

## Credits

- [LSPosed / LSPatch](https://github.com/LSPosed/LSPatch)
- [EFForg/apkeep](https://github.com/EFForg/apkeep)
- [REAndroid/APKEditor](https://github.com/REAndroid/APKEditor)
- [GakumasTranslationData](https://github.com/fullpie/GakumasTranslationData)
