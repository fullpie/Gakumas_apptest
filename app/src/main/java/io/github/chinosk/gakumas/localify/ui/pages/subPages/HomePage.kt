package io.github.chinosk.gakumas.localify.ui.pages.subPages

import io.github.chinosk.gakumas.localify.ui.components.GakuGroupBox
import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.chinosk.gakumas.localify.MainActivity
import io.github.chinosk.gakumas.localify.R
import io.github.chinosk.gakumas.localify.TAG
import io.github.chinosk.gakumas.localify.getConfigState
import io.github.chinosk.gakumas.localify.getProgramConfigState
import io.github.chinosk.gakumas.localify.getProgramDownloadAbleState
import io.github.chinosk.gakumas.localify.getProgramDownloadErrorStringState
import io.github.chinosk.gakumas.localify.getProgramDownloadState
import io.github.chinosk.gakumas.localify.getProgramLocalTextureResourceVersionState
import io.github.chinosk.gakumas.localify.getProgramLocalResourceVersionState
import io.github.chinosk.gakumas.localify.getProgramLocalAPIResourceVersionState
import io.github.chinosk.gakumas.localify.getProgramTextureDownloadAbleState
import io.github.chinosk.gakumas.localify.getProgramTextureDownloadErrorStringState
import io.github.chinosk.gakumas.localify.getProgramTextureDownloadState
import io.github.chinosk.gakumas.localify.hookUtils.FileHotUpdater
import io.github.chinosk.gakumas.localify.mainUtils.ApkInstallUtils
import io.github.chinosk.gakumas.localify.mainUtils.FileDownloader
import io.github.chinosk.gakumas.localify.mainUtils.GamePatchUpdate
import io.github.chinosk.gakumas.localify.mainUtils.AppUpdate
import io.github.chinosk.gakumas.localify.mainUtils.OnlineUpdateChecker
import io.github.chinosk.gakumas.localify.mainUtils.OnlineUpdateResult
import io.github.chinosk.gakumas.localify.mainUtils.RemoteAPIFilesChecker
import io.github.chinosk.gakumas.localify.mainUtils.TextureResourceUpdater
import io.github.chinosk.gakumas.localify.mainUtils.TimeUtils
import io.github.chinosk.gakumas.localify.models.GakumasConfig
import io.github.chinosk.gakumas.localify.models.ResourceCollapsibleBoxViewModel
import io.github.chinosk.gakumas.localify.models.ResourceCollapsibleBoxViewModelFactory
import io.github.chinosk.gakumas.localify.ui.components.base.CollapsibleBox
import io.github.chinosk.gakumas.localify.ui.components.GakuButton
import io.github.chinosk.gakumas.localify.ui.components.GakuProgressBar
import io.github.chinosk.gakumas.localify.ui.components.GakuRadio
import io.github.chinosk.gakumas.localify.ui.components.GakuSwitch
import io.github.chinosk.gakumas.localify.ui.components.GakuTextInput
import java.io.File


@Composable
fun HomePage(modifier: Modifier = Modifier,
             context: MainActivity? = null,
             previewData: GakumasConfig? = null,
             bottomSpacerHeight: Dp = 120.dp,
             screenH: Dp = 1080.dp) {
    val config = getConfigState(context, previewData)
    val programConfig = getProgramConfigState(context)

    val downloadProgress by getProgramDownloadState(context)
    val downloadAble by getProgramDownloadAbleState(context)
    val localResourceVersion by getProgramLocalResourceVersionState(context)
    val localAPIResourceVersion by getProgramLocalAPIResourceVersionState(context)
    val downloadErrorString by getProgramDownloadErrorStringState(context)
    val textureDownloadProgress by getProgramTextureDownloadState(context)
    val textureDownloadAble by getProgramTextureDownloadAbleState(context)
    val localTextureResourceVersion by getProgramLocalTextureResourceVersionState(context)
    val textureDownloadErrorString by getProgramTextureDownloadErrorStringState(context)
    var isFirstTimeInThisPage by rememberSaveable { mutableStateOf(true) }

    // val scrollState = rememberScrollState()
    val keyboardOptionsNumber = remember {
        KeyboardOptions(keyboardType = KeyboardType.Number)
    }
    val keyBoardOptionsDecimal = remember {
        KeyboardOptions(keyboardType = KeyboardType.Decimal)
    }

    val resourceSettingsViewModel: ResourceCollapsibleBoxViewModel =
        viewModel(factory = ResourceCollapsibleBoxViewModelFactory(initiallyExpanded = false))

    var onlineChecking by rememberSaveable { mutableStateOf(false) }
    var onlineStatus by rememberSaveable { mutableStateOf("Not checked") }
    var onlineDownloadProgress by rememberSaveable { mutableStateOf(-1f) }
    var onlineErrorString by rememberSaveable { mutableStateOf("") }
    var onlineResult by remember { mutableStateOf<OnlineUpdateResult?>(null) }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "unknown size"
        val mib = size.toDouble() / 1024.0 / 1024.0
        return "%.1f MiB".format(mib)
    }

    fun openInstaller(file: File) {
        try {
            ApkInstallUtils.openSystemInstaller(context!!, file)
        }
        catch (e: Exception) {
            onlineErrorString = e.message ?: e.toString()
        }
    }

    fun downloadAndInstall(url: String, fileName: String, label: String, expectedSha256: String? = null) {
        if (context == null) return
        onlineChecking = true
        onlineErrorString = ""
        onlineDownloadProgress = 0f
        onlineStatus = "Starting resumable download for $label..."
        val downloadRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val outputFile = File(
            File(downloadRoot, "online-updates"),
            fileName
        )
        FileDownloader.downloadFileResumable(
            url = url,
            outputFile = outputFile,
            expectedSha256 = expectedSha256,
            onDownload = { progress, downloaded, size ->
                context.runOnUiThread {
                    onlineDownloadProgress = progress
                    onlineStatus = if (size > 0) {
                        "Downloading $label: ${formatFileSize(downloaded)} / ${formatFileSize(size)}"
                    }
                    else {
                        "Downloading $label: ${formatFileSize(downloaded)}"
                    }
                }
            },
            onSuccess = { file ->
                context.runOnUiThread {
                    onlineChecking = false
                    onlineDownloadProgress = -1f
                    onlineStatus = "$label downloaded. Opening installer..."
                    openInstaller(file)
                }
            },
            onFailed = { _, reason ->
                context.runOnUiThread {
                    onlineChecking = false
                    onlineDownloadProgress = -1f
                    onlineStatus = "$label download failed"
                    onlineErrorString = reason
                }
            }
        )
    }

    fun installAppUpdate(update: AppUpdate) {
        downloadAndInstall(update.asset.browser_download_url, update.asset.name, "app update")
    }

    fun installGamePatch(update: GamePatchUpdate) {
        downloadAndInstall(update.asset.browserDownloadUrl, update.asset.name, "game patch", update.asset.sha256)
    }

    fun describeOnlineResult(result: OnlineUpdateResult): String {
        val lines = mutableListOf<String>()
        val app = result.appUpdate
        val game = result.gamePatchUpdate
        if (app != null) {
            lines.add("App: ${app.currentVersionName} -> ${app.latestVersionName}")
        }
        else {
            lines.add("App: current")
        }
        if (game != null) {
            val installed = game.installedVersionName ?: "not installed"
            val patchState = if (game.installedIsPatched) {
                game.installedPatchMode ?: "patched"
            } else {
                "not patched"
            }
            val packageState = if (game.installedSha256 != null &&
                game.asset.sha256 != null &&
                !game.installedSha256.equals(game.asset.sha256, ignoreCase = true)
            ) {
                ", older package"
            } else {
                ""
            }
            lines.add("Game: $installed ($patchState$packageState) -> ${game.metadata.gameVersion} (${game.metadata.patchMode})")
        }
        else {
            lines.add("Game patch: current")
        }
        return lines.joinToString("\n")
    }

    fun showOnlineUpdateConfirm(result: OnlineUpdateResult) {
        if (context == null) return
        val game = result.gamePatchUpdate
        val app = result.appUpdate
        when {
            game != null -> {
                context.mainUIConfirmStatUpdate(
                    true,
                    "Game patch update",
                    "${describeOnlineResult(result)}\n\nDownload and install the patched game APK?",
                    onConfirm = { installGamePatch(game) }
                )
            }
            app != null -> {
                context.mainUIConfirmStatUpdate(
                    true,
                    "App update",
                    "${describeOnlineResult(result)}\n\nDownload and install the new app APK?",
                    onConfirm = { installAppUpdate(app) }
                )
            }
        }
    }

    fun checkOnlineUpdates(isAutoCheck: Boolean = false) {
        if (context == null || onlineChecking) return
        onlineChecking = true
        onlineErrorString = ""
        onlineStatus = "Checking online updates..."
        OnlineUpdateChecker.checkUpdates(context,
            onResult = { result ->
                onlineChecking = false
                onlineResult = result
                onlineStatus = describeOnlineResult(result)
                if (isAutoCheck && (result.gamePatchUpdate != null || result.appUpdate != null)) {
                    showOnlineUpdateConfirm(result)
                }
            },
            onFailed = { reason ->
                onlineChecking = false
                onlineStatus = "Update check failed"
                onlineErrorString = reason
            }
        )
    }


    fun zipResourceDownload() {
        val (_, newUrl) = FileDownloader.checkAndChangeDownloadURL(programConfig.value.transRemoteZipUrl)
        context?.onPTransRemoteZipUrlChanged(newUrl, 0, 0, 0)
        FileDownloader.downloadFile(
            newUrl,
            checkContentTypes = listOf("application/zip", "application/octet-stream"),
            onDownload = { progress, _, _ ->
                context?.mainPageAssetsViewDataUpdate(downloadProgressState = progress)
            },

            onSuccess = { byteArray ->
                context?.mainPageAssetsViewDataUpdate(
                    downloadAbleState = true,
                    errorString = "",
                    downloadProgressState = -1f
                )
                val file = File(context?.filesDir, "update_trans.zip")
                file.writeBytes(byteArray)
                val newFileVersion = FileHotUpdater.getZipResourceVersion(file.absolutePath)
                if (newFileVersion != null) {
                    context?.mainPageAssetsViewDataUpdate(
                        localResourceVersionState = newFileVersion
                    )
                }
                else {
                    context?.mainPageAssetsViewDataUpdate(
                        localResourceVersionState = context.getString(
                            R.string.invalid_zip_file
                        ),
                        errorString = context.getString(R.string.invalid_zip_file_warn)
                    )
                }
            },

            onFailed = { code, reason ->
                context?.mainPageAssetsViewDataUpdate(
                    downloadAbleState = true,
                    errorString = reason,
                )
            })
    }

    fun onClickDownload(isZipResource: Boolean, isHumanClick: Boolean = true,
                        onFinished: (() -> Unit)? = null) {
        context?.mainPageAssetsViewDataUpdate(
            downloadAbleState = false,
            errorString = "",
            downloadProgressState = -1f
        )
        if (isZipResource) {
            zipResourceDownload()
            onFinished?.invoke()
        }
        else {
            RemoteAPIFilesChecker.checkUpdateLocalAssets(context!!,
                programConfig.value.useAPIAssetsURL,
                onFailed = { _, reason ->
                    context.mainPageAssetsViewDataUpdate(
                        downloadAbleState = true,
                        errorString = "",
                        downloadProgressState = -1f
                    )
                    context.mainUIConfirmStatUpdate(true, "Error", reason)
                    onFinished?.invoke()
                },
                onResult = { data, localVersion ->
                    if (!isHumanClick) {
                        if (data.tag_name == localVersion) {
                            context.mainPageAssetsViewDataUpdate(
                                downloadAbleState = true,
                                errorString = "",
                                downloadProgressState = -1f
                            )
                            onFinished?.invoke()
                            return@checkUpdateLocalAssets
                        }
                    }
                    context.mainUIConfirmStatUpdate(true, context.getString(R.string.translation_resource_update),
                        "${data.name}\n$localVersion -> ${data.tag_name}\n${data.body}\n\n${TimeUtils.convertIsoToLocalTime(data.published_at)}",
                        onConfirm = {
                            resourceSettingsViewModel.expanded = true
                            RemoteAPIFilesChecker.updateLocalAssets(context, programConfig.value.useAPIAssetsURL,
                                onDownload = { progress, _, _ ->
                                    context.mainPageAssetsViewDataUpdate(downloadProgressState = progress)
                                },
                                onFailed = { _, reason ->
                                    context.mainPageAssetsViewDataUpdate(
                                        downloadAbleState = true,
                                        errorString = reason,
                                    )
                                    onFinished?.invoke()
                                },
                                onSuccess = { saveFile, releaseVersion ->
                                    context.mainPageAssetsViewDataUpdate(
                                        downloadAbleState = true,
                                        errorString = "",
                                        downloadProgressState = -1f
                                    )
                                    context.mainPageAssetsViewDataUpdate(
                                        localAPIResourceVersion = RemoteAPIFilesChecker.getLocalVersion(context)
                                    )
                                    context.saveProgramConfig()
                                    Log.d(TAG, "saved: $releaseVersion $saveFile")
                                    onFinished?.invoke()
                                })
                        },
                        onCancel = {
                            context.mainPageAssetsViewDataUpdate(
                                downloadAbleState = true,
                                errorString = "",
                                downloadProgressState = -1f
                            )
                            onFinished?.invoke()
                        }
                        )
                })
        }
    }

    fun startTextureResourceUpdate() {
        context?.mainPageTextureAssetsViewDataUpdate(
            downloadAbleState = false,
            errorString = "",
            downloadProgressState = -1f
        )
        TextureResourceUpdater.updateTextureAssets(context!!,
            programConfig.value.useAPITextureAssetsURL,
            programConfig.value.delTextureRemoteAfterUpdate,
            onDownload = { progress, _, _ ->
                context.mainPageTextureAssetsViewDataUpdate(downloadProgressState = progress)
            },
            onFailed = { _, reason ->
                context.mainPageTextureAssetsViewDataUpdate(
                    downloadAbleState = true,
                    errorString = reason,
                )
            },
            onSuccess = { releaseVersion, changed ->
                context.mainPageTextureAssetsViewDataUpdate(
                    downloadAbleState = true,
                    errorString = "",
                    downloadProgressState = -1f,
                    localTextureResourceVersion = TextureResourceUpdater.getLocalVersion(context)
                        ?: releaseVersion
                )
                context.saveProgramConfig()
                Log.d(TAG, "texture resource update finished: $releaseVersion changed=$changed")
            })
    }

    fun onClickTextureDownload(isHumanClick: Boolean = true) {
        context?.mainPageTextureAssetsViewDataUpdate(
            downloadAbleState = false,
            errorString = "",
            downloadProgressState = -1f
        )
        TextureResourceUpdater.checkUpdateTextureAssets(context!!,
            programConfig.value.useAPITextureAssetsURL,
            onFailed = { _, reason ->
                context.mainPageTextureAssetsViewDataUpdate(
                    downloadAbleState = true,
                    errorString = reason,
                    downloadProgressState = -1f
                )
                if (isHumanClick) {
                    context.mainUIConfirmStatUpdate(true, "Error", reason)
                }
            },
            onResult = { data, localVersion ->
                if (!isHumanClick) {
                    if (data.tag_name == localVersion) {
                        context.mainPageTextureAssetsViewDataUpdate(
                            downloadAbleState = true,
                            errorString = "",
                            downloadProgressState = -1f,
                            localTextureResourceVersion = localVersion
                        )
                        return@checkUpdateTextureAssets
                    }
                }

                context.mainUIConfirmStatUpdate(true, context.getString(R.string.texture_resource_update),
                    "${data.name}\n$localVersion -> ${data.tag_name}\n${data.body}\n\n${TimeUtils.convertIsoToLocalTime(data.published_at)}",
                    onConfirm = {
                        resourceSettingsViewModel.expanded = true
                        startTextureResourceUpdate()
                    },
                    onCancel = {
                        context.mainPageTextureAssetsViewDataUpdate(
                            downloadAbleState = true,
                            errorString = "",
                            downloadProgressState = -1f
                        )
                    }
                )
            })
    }

    LaunchedEffect(Unit) {
        try {
            if (context == null) return@LaunchedEffect
            val localAPIResVer = RemoteAPIFilesChecker.getLocalVersion(context)
            context.mainPageAssetsViewDataUpdate(
                localAPIResourceVersion = localAPIResVer
            )
            context.mainPageTextureAssetsViewDataUpdate(
                localTextureResourceVersion = TextureResourceUpdater.getLocalVersion(context)
            )
            if (isFirstTimeInThisPage) {
                val shouldCheckResource =
                    programConfig.value.useAPIAssets && programConfig.value.useAPIAssetsURL.isNotEmpty()
                val shouldCheckTexture = config.value.replaceTexture &&
                    programConfig.value.useAPITextureAssets &&
                    programConfig.value.useAPITextureAssetsURL.isNotEmpty()

                if (shouldCheckResource) {
                    onClickDownload(false, false) {
                        if (shouldCheckTexture) {
                            onClickTextureDownload(false)
                        }
                    }
                }
                else if (shouldCheckTexture) {
                    onClickTextureDownload(false)
                }
                checkOnlineUpdates(true)
            }
        }
        finally {
            isFirstTimeInThisPage = false
        }
    }

    LazyColumn(modifier = modifier
        .sizeIn(maxHeight = screenH)
        // .fillMaxHeight()
        // .verticalScroll(scrollState)
        // .width(IntrinsicSize.Max)
        .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            GakuGroupBox(modifier = modifier, stringResource(R.string.basic_settings)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GakuSwitch(modifier, stringResource(R.string.enable_plugin), checked = config.value.enabled) {
                            v -> context?.onEnabledChanged(v)
                    }

                    GakuSwitch(modifier, stringResource(R.string.lazy_init), checked = config.value.lazyInit) {
                            v -> context?.onLazyInitChanged(v)
                    }

                    GakuSwitch(modifier, stringResource(R.string.replace_font), checked = config.value.replaceFont) {
                            v -> context?.onReplaceFontChanged(v)
                    }

                    GakuSwitch(modifier, stringResource(R.string.replace_texture), checked = config.value.replaceTexture) {
                            v -> context?.onReplaceTextureChanged(v)
                    }

                }
            }
            Spacer(Modifier.height(6.dp))
        }

        item {
            GakuGroupBox(modifier = modifier, title = "Online Updates") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = onlineStatus, fontSize = 13.sp)

                    if (onlineDownloadProgress >= 0) {
                        GakuProgressBar(progress = onlineDownloadProgress, isError = onlineErrorString.isNotEmpty())
                    }

                    if (onlineErrorString.isNotEmpty()) {
                        Text(text = onlineErrorString, color = Color(0xFFE2041B), fontSize = 13.sp)
                    }

                    Row(modifier = modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val buttonModifier = remember {
                            modifier
                                .height(38.dp)
                                .weight(1f)
                        }
                        if (onlineChecking) {
                            GakuButton(modifier = buttonModifier,
                                text = stringResource(id = R.string.cancel), onClick = {
                                    FileDownloader.cancel()
                                    onlineChecking = false
                                    onlineDownloadProgress = -1f
                                    onlineStatus = "Canceled"
                                })
                        }
                        else {
                            GakuButton(modifier = buttonModifier,
                                text = stringResource(R.string.check_update),
                                onClick = { checkOnlineUpdates(false) })
                        }

                        val appUpdate = onlineResult?.appUpdate
                        GakuButton(modifier = buttonModifier,
                            text = "App APK",
                            enabled = appUpdate != null && !onlineChecking,
                            onClick = { appUpdate?.let { installAppUpdate(it) } })

                        val gameUpdate = onlineResult?.gamePatchUpdate
                        GakuButton(modifier = buttonModifier,
                            text = "Game APK",
                            enabled = gameUpdate != null && !onlineChecking,
                            onClick = { gameUpdate?.let { installGamePatch(it) } })
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }

        item {
            GakuGroupBox(modifier, stringResource(R.string.resource_settings),
                contentPadding = 0.dp,
                onHeadClick = {
                    resourceSettingsViewModel.expanded = !resourceSettingsViewModel.expanded
                }) {
                CollapsibleBox(modifier = modifier,
                    viewModel = resourceSettingsViewModel
                ) {
                    LazyColumn(modifier = modifier
                        // .padding(8.dp)
                        .sizeIn(maxHeight = screenH),
                        // verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            GakuSwitch(modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                                checked = programConfig.value.checkBuiltInAssets,
                                text = stringResource(id = R.string.check_built_in_resource)
                            ) { v -> context?.onPCheckBuiltInAssetsChanged(v) }
                        }
                        item {
                            GakuSwitch(modifier = modifier.padding(start = 8.dp, end = 8.dp),
                                checked = programConfig.value.cleanLocalAssets,
                                text = stringResource(id = R.string.delete_plugin_resource)
                            ) { v -> context?.onPCleanLocalAssetsChanged(v) }
                        }

                        item {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        }

                        item {
                            GakuSwitch(modifier = modifier.padding(start = 8.dp, end = 8.dp),
                                checked = programConfig.value.useAPIAssets,
                                text = stringResource(R.string.check_resource_from_api)
                            ) { v -> context?.onPUseAPIAssetsChanged(v) }

                            CollapsibleBox(modifier = modifier.graphicsLayer(clip = false),
                                expandState = programConfig.value.useAPIAssets,
                                collapsedHeight = 0.dp,
                                innerPaddingLeftRight = 8.dp,
                                showExpand = false
                            ) {
                                GakuSwitch(modifier = modifier,
                                    checked = programConfig.value.delRemoteAfterUpdate,
                                    text = stringResource(id = R.string.del_remote_after_update)
                                ) { v -> context?.onPDelRemoteAfterUpdateChanged(v) }

                                LazyColumn(modifier = modifier
                                    // .padding(8.dp)
                                    .sizeIn(maxHeight = screenH),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    item {
                                        Row(modifier = modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically) {

                                            GakuTextInput(modifier = modifier
                                                .height(45.dp)
                                                .padding(end = 8.dp)
                                                .fillMaxWidth()
                                                .weight(1f),
                                                fontSize = 14f,
                                                value = programConfig.value.useAPIAssetsURL,
                                                onValueChange = { c -> context?.onPUseAPIAssetsURLChanged(c, 0, 0, 0)},
                                                label = { Text(stringResource(R.string.api_addr)) }
                                            )

                                            if (downloadAble) {
                                                GakuButton(modifier = modifier
                                                    .height(40.dp)
                                                    .sizeIn(minWidth = 80.dp),
                                                    text = stringResource(R.string.check_update),
                                                    onClick = { onClickDownload(false) })
                                            }
                                            else {
                                                GakuButton(modifier = modifier
                                                    .height(40.dp)
                                                    .sizeIn(minWidth = 80.dp),
                                                    text = stringResource(id = R.string.cancel), onClick = {
                                                        FileDownloader.cancel()
                                                    })
                                            }

                                        }
                                    }

                                    if (downloadProgress >= 0) {
                                        item {
                                            GakuProgressBar(progress = downloadProgress, isError = downloadErrorString.isNotEmpty())
                                        }
                                    }

                                    if (downloadErrorString.isNotEmpty()) {
                                        item {
                                            Text(text = downloadErrorString, color = Color(0xFFE2041B))
                                        }
                                    }

                                    item {
                                        Text(modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                context?.mainPageAssetsViewDataUpdate(
                                                    localAPIResourceVersion = RemoteAPIFilesChecker.getLocalVersion(
                                                        context
                                                    )
                                                )
                                            }, text = "${stringResource(R.string.downloaded_resource_version)}: $localAPIResourceVersion")
                                    }

                                    item {
                                        Spacer(Modifier.height(0.dp))
                                    }

                                }

                            }
                        }

                        item {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        }

                        item {
                            GakuSwitch(modifier = modifier.padding(start = 8.dp, end = 8.dp),
                                checked = programConfig.value.useRemoteAssets,
                                text = stringResource(id = R.string.use_remote_zip_resource)
                            ) { v -> context?.onPUseRemoteAssetsChanged(v) }

                            CollapsibleBox(modifier = modifier.graphicsLayer(clip = false),
                                expandState = programConfig.value.useRemoteAssets,
                                collapsedHeight = 0.dp,
                                innerPaddingLeftRight = 8.dp,
                                showExpand = false
                            ) {
                                GakuSwitch(modifier = modifier,
                                    checked = programConfig.value.delRemoteAfterUpdate,
                                    text = stringResource(id = R.string.del_remote_after_update)
                                ) { v -> context?.onPDelRemoteAfterUpdateChanged(v) }

                                LazyColumn(modifier = modifier
                                    // .padding(8.dp)
                                    .sizeIn(maxHeight = screenH),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    item {
                                        Row(modifier = modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically) {

                                            GakuTextInput(modifier = modifier
                                                .height(45.dp)
                                                .padding(end = 8.dp)
                                                .fillMaxWidth()
                                                .weight(1f),
                                                fontSize = 14f,
                                                value = programConfig.value.transRemoteZipUrl,
                                                onValueChange = { c -> context?.onPTransRemoteZipUrlChanged(c, 0, 0, 0)},
                                                label = { Text(stringResource(id = R.string.resource_url)) }
                                            )

                                            if (downloadAble) {
                                                GakuButton(modifier = modifier
                                                    .height(40.dp)
                                                    .sizeIn(minWidth = 80.dp),
                                                    text = stringResource(id = R.string.download),
                                                    onClick = { onClickDownload(true) })
                                            }
                                            else {
                                                GakuButton(modifier = modifier
                                                    .height(40.dp)
                                                    .sizeIn(minWidth = 80.dp),
                                                    text = stringResource(id = R.string.cancel), onClick = {
                                                        FileDownloader.cancel()
                                                    })
                                            }

                                        }
                                    }

                                    if (downloadProgress >= 0) {
                                        item {
                                            GakuProgressBar(progress = downloadProgress, isError = downloadErrorString.isNotEmpty())
                                        }
                                    }

                                    if (downloadErrorString.isNotEmpty()) {
                                        item {
                                            Text(text = downloadErrorString, color = Color(0xFFE2041B))
                                        }
                                    }

                                    item {
                                        Text(modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val file =
                                                    File(context?.filesDir, "update_trans.zip")
                                                context?.mainPageAssetsViewDataUpdate(
                                                    localResourceVersionState = FileHotUpdater
                                                        .getZipResourceVersion(file.absolutePath)
                                                        .toString()
                                                )
                                            }, text = "${stringResource(R.string.downloaded_resource_version)}: $localResourceVersion")
                                    }

                                    item {
                                        Spacer(Modifier.height(0.dp))
                                    }

                                }

                            }
                        }

                        if (config.value.replaceTexture) {
                            item {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )
                            }

                            item {
                                GakuSwitch(modifier = modifier.padding(start = 8.dp, end = 8.dp),
                                    checked = programConfig.value.useAPITextureAssets,
                                    text = stringResource(R.string.check_texture_resource_from_api)
                                ) { v -> context?.onPUseAPITextureAssetsChanged(v) }

                                CollapsibleBox(modifier = modifier.graphicsLayer(clip = false),
                                    expandState = programConfig.value.useAPITextureAssets,
                                    collapsedHeight = 0.dp,
                                    innerPaddingLeftRight = 8.dp,
                                    showExpand = false
                                ) {
                                    GakuSwitch(modifier = modifier,
                                        checked = programConfig.value.delTextureRemoteAfterUpdate,
                                        text = stringResource(id = R.string.del_remote_after_update)
                                    ) { v -> context?.onPDelTextureRemoteAfterUpdateChanged(v) }

                                    LazyColumn(modifier = modifier
                                        .sizeIn(maxHeight = screenH),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        item {
                                            Row(modifier = modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                verticalAlignment = Alignment.CenterVertically) {

                                                GakuTextInput(modifier = modifier
                                                    .height(45.dp)
                                                    .padding(end = 8.dp)
                                                    .fillMaxWidth()
                                                    .weight(1f),
                                                    fontSize = 14f,
                                                    value = programConfig.value.useAPITextureAssetsURL,
                                                    onValueChange = { c -> context?.onPUseAPITextureAssetsURLChanged(c, 0, 0, 0)},
                                                    label = { Text(stringResource(R.string.texture_api_addr)) }
                                                )

                                                if (textureDownloadAble) {
                                                    GakuButton(modifier = modifier
                                                        .height(40.dp)
                                                        .sizeIn(minWidth = 80.dp),
                                                        text = stringResource(R.string.check_update),
                                                        onClick = { onClickTextureDownload(true) })
                                                }
                                                else {
                                                    GakuButton(modifier = modifier
                                                        .height(40.dp)
                                                        .sizeIn(minWidth = 80.dp),
                                                        text = stringResource(id = R.string.cancel), onClick = {
                                                            FileDownloader.cancel()
                                                        })
                                                }

                                            }
                                        }

                                        if (textureDownloadProgress >= 0) {
                                            item {
                                                GakuProgressBar(progress = textureDownloadProgress,
                                                    isError = textureDownloadErrorString.isNotEmpty())
                                            }
                                        }

                                        if (textureDownloadErrorString.isNotEmpty()) {
                                            item {
                                                Text(text = textureDownloadErrorString, color = Color(0xFFE2041B))
                                            }
                                        }

                                        item {
                                            Text(modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    context?.let {
                                                        it.mainPageTextureAssetsViewDataUpdate(
                                                            localTextureResourceVersion = TextureResourceUpdater
                                                                .getLocalVersion(it)
                                                        )
                                                    }
                                                }, text = "${stringResource(R.string.downloaded_texture_resource_version)}: $localTextureResourceVersion")
                                        }

                                        item {
                                            Spacer(Modifier.height(0.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }

        item {
            GakuGroupBox(modifier = modifier, contentPadding = 0.dp, title = stringResource(R.string.graphic_settings)) {
                LazyColumn(modifier = Modifier
                    .sizeIn(maxHeight = screenH),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        GakuTextInput(modifier = modifier
                            .padding(start = 4.dp, end = 4.dp)
                            .height(45.dp)
                            .fillMaxWidth(),
                            fontSize = 14f,
                            value = config.value.targetFrameRate.toString(),
                            onValueChange = { c -> context?.onTargetFpsChanged(c, 0, 0, 0)},
                            label = { Text(stringResource(R.string.setFpsTitle)) },
                            keyboardOptions = keyboardOptionsNumber)
                    }

                    item {
                        Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.orientation_lock))
                            Row(modifier = modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val radioModifier = remember {
                                    modifier
                                        .height(40.dp)
                                        .weight(1f)
                                }

                                GakuRadio(modifier = radioModifier,
                                    text = stringResource(R.string.orientation_orig), selected = config.value.gameOrientation == 0,
                                    onClick = { context?.onGameOrientationChanged(0) })

                                GakuRadio(modifier = radioModifier,
                                    text = stringResource(R.string.orientation_portrait), selected = config.value.gameOrientation == 1,
                                    onClick = { context?.onGameOrientationChanged(1) })

                                GakuRadio(modifier = radioModifier,
                                    text = stringResource(R.string.orientation_landscape), selected = config.value.gameOrientation == 2,
                                    onClick = { context?.onGameOrientationChanged(2) })
                            }
                        }
                    }

                    item {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    }

                    item {
                        GakuSwitch(modifier.padding(start = 8.dp, end = 8.dp),
                            stringResource(R.string.useCustomeGraphicSettings),
                            checked = config.value.useCustomeGraphicSettings) {
                                v -> context?.onUseCustomeGraphicSettingsChanged(v)
                        }

                        CollapsibleBox(modifier = modifier,
                            expandState = config.value.useCustomeGraphicSettings,
                            collapsedHeight = 0.dp,
                            showExpand = false
                        ) {
                            LazyColumn(modifier = modifier
                                .padding(8.dp)
                                .sizeIn(maxHeight = screenH)
                                .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item {
                                    Row(modifier = modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val buttonModifier = remember {
                                            modifier
                                                .height(40.dp)
                                                .weight(1f)
                                        }

                                        GakuButton(modifier = buttonModifier,
                                            text = stringResource(R.string.max_high), onClick = { context?.onChangePresetQuality(4) })

                                        GakuButton(modifier = buttonModifier,
                                            text = stringResource(R.string.very_high), onClick = { context?.onChangePresetQuality(3) })

                                        GakuButton(modifier = buttonModifier,
                                            text = stringResource(R.string.hign), onClick = { context?.onChangePresetQuality(2) })

                                        GakuButton(modifier = buttonModifier,
                                            text = stringResource(R.string.middle), onClick = { context?.onChangePresetQuality(1) })

                                        GakuButton(modifier = buttonModifier,
                                            text = stringResource(R.string.low), onClick = { context?.onChangePresetQuality(0) })
                                    }
                                }

                                item {
                                    Row(modifier = modifier,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val textInputModifier = remember {
                                            modifier
                                                .height(45.dp)
                                                .weight(1f)
                                        }

                                        GakuTextInput(modifier = textInputModifier,
                                            fontSize = 14f,
                                            value = config.value.renderScale.toString(),
                                            onValueChange = { c -> context?.onRenderScaleChanged(c, 0, 0, 0)},
                                            label = { Text(stringResource(R.string.renderscale)) },
                                            keyboardOptions = keyBoardOptionsDecimal)

                                        GakuTextInput(modifier = textInputModifier,
                                            fontSize = 14f,
                                            value = config.value.qualitySettingsLevel.toString(),
                                            onValueChange = { c -> context?.onQualitySettingsLevelChanged(c, 0, 0, 0)},
                                            label = { Text("QualityLevel (1/1/2/3/5)") },
                                            keyboardOptions = keyboardOptionsNumber)
                                    }
                                }

                                item {
                                    Row(modifier = modifier,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val textInputModifier = remember {
                                            modifier
                                                .height(45.dp)
                                                .weight(1f)
                                        }

                                        GakuTextInput(modifier = textInputModifier,
                                            fontSize = 14f,
                                            value = config.value.volumeIndex.toString(),
                                            onValueChange = { c -> context?.onVolumeIndexChanged(c, 0, 0, 0)},
                                            label = { Text("VolumeIndex (0/1/2/3/4)") },
                                            keyboardOptions = keyboardOptionsNumber)

                                        GakuTextInput(modifier = textInputModifier,
                                            fontSize = 14f,
                                            value = config.value.maxBufferPixel.toString(),
                                            onValueChange = { c -> context?.onMaxBufferPixelChanged(c, 0, 0, 0)},
                                            label = { Text("MaxBufferPixel (1024/1440/2538/3384/8190)", fontSize = 10.sp) },
                                            keyboardOptions = keyboardOptionsNumber)
                                    }
                                }

                                item {
                                    Row(modifier = modifier,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val textInputModifier = remember {
                                            modifier
                                                .height(45.dp)
                                                .weight(1f)
                                        }

                                        GakuTextInput(modifier = textInputModifier,
                                            fontSize = 14f,
                                            value = config.value.reflectionQualityLevel.toString(),
                                            onValueChange = { c -> context?.onReflectionQualityLevelChanged(c, 0, 0, 0)},
                                            label = { Text( text = "ReflectionLevel (0~5)") },
                                            keyboardOptions = keyboardOptionsNumber)

                                        GakuTextInput(modifier = textInputModifier,
                                            fontSize = 14f,
                                            value = config.value.lodQualityLevel.toString(),
                                            onValueChange = { c -> context?.onLodQualityLevelChanged(c, 0, 0, 0)},
                                            label = { Text("LOD Level (0~5)") },
                                            keyboardOptions = keyboardOptionsNumber)
                                    }
                                }
                            }
                        }

                    }

                }

            }
        }

        item {
            Spacer(modifier = modifier.height(bottomSpacerHeight))
        }
    }
}


@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_NO)
@Composable
fun HomePagePreview(modifier: Modifier = Modifier, data: GakumasConfig = GakumasConfig()) {
    HomePage(modifier, previewData = data)
}
