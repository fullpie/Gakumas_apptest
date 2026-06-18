# Gakumas Localify Android

這是學園偶像大師 Android 版的 Localify / LSPatch 管理工具。

## 使用方式

1. 到本倉庫的 Release 下載 `app-v...` 版本的 `GakumasLocalify-*.apk`。
2. 安裝後打開 app，首頁會自動檢查：
   - app 本體是否有新版；
   - 是否有新的雲端 patched game APK。
3. 語言包仍可在 app 內自行選擇：
   - 內建語言包；
   - GitHub Release API 語言包；
   - 自訂 ZIP 語言包。

如果安裝時顯示簽名衝突，先解除安裝舊版 app 或舊版 patched game，再重新安裝。GitHub Actions 產出的 APK 和你本機重新打包的 APK 不一定會使用同一個簽名。

## Release Channel

- `app-v...`：Gakumas Localify app 本體更新。
- `game-v...`：雲端 patched game APK。此模式只 patch 遊戲本體，不內嵌語言包，所以使用者仍可在 app 內切換語言包來源。

app 端會抓 GitHub Releases 清單並依照 tag 前綴分流，不使用 `/releases/latest`，避免 app 與 game 兩種 release 互相蓋掉。

## 自行編譯 App

Fork 或 clone 後可以直接跑 GitHub Actions 的 `Android CI`。若要產出可穩定升級的 signed APK，請在 repo secrets 設定：

- `KEYSTOREB64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

推送 `app-v3.2.0` 這類 tag 時，Actions 會建立 app release，並附上 `gkms-app-update.json` 供 app 內更新檢查使用。若沒有設定簽名 secrets，Actions 會退回上傳 debug APK；這種 APK 可下載使用，但未必能覆蓋安裝舊版。

## 雲端 Patch Game

`Game Patch Release` workflow 支援兩種來源：

### manual_xapk

這是最容易跑通的方式。到 APKPure 或其他來源取得真正的 `.xapk` 下載直連後，在 Actions 手動執行：

- `source`: `manual_xapk`
- `xapk_url`: `.xapk` 直連，不是 APKPure 網頁 URL
- `game_version`: 例如 `3.1.1`
- `force`: 如果要覆蓋同版本 release，設為 `true`

workflow 會下載 XAPK、檢查 manifest 的 package/version、用 `APKEditor` 合併 split / asset pack，再用本 repo 的 `app/libs/lspatch.jar` 產出 manager-mode patched APK，最後建立 `game-v...` release 和 `gkms-game-patch.json`。

### google_play

這條路線會偵測 Google Play 上的遊戲版本，使用 `apkeep` 下載官方 split APK，再合併與 patch。

需要在 repo secrets 設定：

- `PLAY_EMAIL`
- `AAS_TOKEN`

如果沒有設定這兩個 secrets，排程觸發的 Google Play patch 會自動跳過；手動選 `google_play` 時則會報錯提醒。

如果 repo 是 private，app 端無法不帶 token 讀取 GitHub Releases；要讓一般使用者直接在 app 內檢查/下載更新，請把 release 所在 repo 設為 public，或把 metadata / APK 發佈到另一個 public repo。

請確認你有權限散布 workflow 產出的檔案。若不想公開完整 patched game APK，可以只保留 private artifact，讓使用者 fork 後用自己的 Google Play 憑證執行 workflow。

## Credits

- [LSPosed / LSPatch](https://github.com/LSPosed/LSPatch)
- [EFForg/apkeep](https://github.com/EFForg/apkeep)
- [REAndroid/APKEditor](https://github.com/REAndroid/APKEditor)
- [GakumasTranslationData](https://github.com/fullpie/GakumasTranslationData)
