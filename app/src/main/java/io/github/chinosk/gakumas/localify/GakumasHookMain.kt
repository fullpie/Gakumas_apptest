package io.github.chinosk.gakumas.localify

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.bytedance.shadowhook.ShadowHook
import com.bytedance.shadowhook.ShadowHook.ConfigBuilder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.chinosk.gakumas.localify.hookUtils.FilesChecker
import io.github.chinosk.gakumas.localify.models.GakumasConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis
import io.github.chinosk.gakumas.localify.hookUtils.FileHotUpdater
import io.github.chinosk.gakumas.localify.hookUtils.FilesChecker.localizationFilesDir
import io.github.chinosk.gakumas.localify.mainUtils.TextureResourceUpdater
import io.github.chinosk.gakumas.localify.mainUtils.json
import io.github.chinosk.gakumas.localify.models.NativeInitProgress
import io.github.chinosk.gakumas.localify.models.ProgramConfig
import io.github.chinosk.gakumas.localify.ui.game_attach.InitProgressUI

val TAG = "GakumasLocalify"

class GakumasHookMain : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var modulePath: String
    @Volatile
    private var nativeLibLoadSuccess = false
    private var nativeLibLoadError: String? = null
    @Volatile
    private var loopStarted = false
    private var alreadyInitialized = false
    private val targetPackageName = "com.bandainamcoent.idolmaster_gakuen"
    private val nativeLibName = "MarryKotone"

    private var gkmsDataInited = false
    private var pendingGkmsData: String? = null

    private var getConfigError: Exception? = null
    private var externalFilesChecked: Boolean = false
    private var textureFilesChecked: Boolean = false
    private var gameActivity: Activity? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
//        if (lpparam.packageName == "io.github.chinosk.gakumas.localify") {
//            XposedHelpers.findAndHookMethod(
//                "io.github.chinosk.gakumas.localify.MainActivity",
//                lpparam.classLoader,
//                "showToast",
//                String::class.java,
//                object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam) {
//                        Log.d(TAG, "beforeHookedMethod hooked: ${param.args}")
//                    }
//                }
//            )
//        }

        if (lpparam.packageName != targetPackageName) {
            return
        }

        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "dispatchKeyEvent",
            KeyEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val keyEvent = param.args[0] as KeyEvent
                    val keyCode = keyEvent.keyCode
                    val action = keyEvent.action
                    // Log.d(TAG, "Key event: keyCode=$keyCode, action=$action")
                    if (nativeLibLoadSuccess) {
                        keyboardEvent(keyCode, action)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "dispatchGenericMotionEvent",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val motionEvent = param.args[0] as MotionEvent
                    val action = motionEvent.action

                    // 左摇杆的X和Y轴
                    val leftStickX = motionEvent.getAxisValue(MotionEvent.AXIS_X)
                    val leftStickY = motionEvent.getAxisValue(MotionEvent.AXIS_Y)

                    // 右摇杆的X和Y轴
                    val rightStickX = motionEvent.getAxisValue(MotionEvent.AXIS_Z)
                    val rightStickY = motionEvent.getAxisValue(MotionEvent.AXIS_RZ)

                    // 左扳机
                    val leftTrigger = motionEvent.getAxisValue(MotionEvent.AXIS_LTRIGGER)

                    // 右扳机
                    val rightTrigger = motionEvent.getAxisValue(MotionEvent.AXIS_RTRIGGER)

                    // 十字键
                    val hatX = motionEvent.getAxisValue(MotionEvent.AXIS_HAT_X)
                    val hatY = motionEvent.getAxisValue(MotionEvent.AXIS_HAT_Y)

                    if (nativeLibLoadSuccess) {
                        // Handle joystick and trigger events.
                        joystickEvent(
                            action,
                            leftStickX,
                            leftStickY,
                            rightStickX,
                            rightStickY,
                            leftTrigger,
                            rightTrigger,
                            hatX,
                            hatY
                        )
                    }
                }
            }
        )

        val appActivityClass = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader)
        fun handleActivityIntent(activity: Activity, intent: Intent? = activity.intent) {
            gameActivity = activity
            if (intent != null && activity.intent !== intent) {
                activity.intent = intent
            }
            if (getConfigError != null) {
                showGetConfigFailed(activity)
            }
            else {
                initGkmsConfig(activity)
            }
        }

        XposedBridge.hookAllMethods(appActivityClass, "onStart", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                super.beforeHookedMethod(param)
                Log.d(TAG, "onStart")
                handleActivityIntent(param.thisObject as Activity)
            }
        })

        XposedBridge.hookAllMethods(appActivityClass, "onResume", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                Log.d(TAG, "onResume")
                handleActivityIntent(param.thisObject as Activity)
            }
        })

        val onNewIntentHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val currActivity = param.thisObject as Activity
                val newIntent = param.args[0] as? Intent ?: return
                Log.d(TAG, "onNewIntent: ${currActivity.javaClass.name}")
                handleActivityIntent(currActivity, newIntent)
            }
        }

        val newIntentHookClasses = listOf(
            "android.app.Activity",
            "com.google.firebase.MessagingUnityPlayerActivity",
            "com.unity3d.player.UnityPlayerActivity"
        )
        for (className in newIntentHookClasses) {
            try {
                val activityClass = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(activityClass, "onNewIntent", onNewIntentHook)
                Log.i(TAG, "Hooked onNewIntent for $className")
            }
            catch (e: Throwable) {
                if (className != "android.app.Activity") {
                    Log.i(TAG, "Skip onNewIntent hook for $className: ${e.message}")
                }
            }
        }

        val cls = lpparam.classLoader.loadClass("com.unity3d.player.UnityPlayer")
        XposedHelpers.findAndHookMethod(
            cls,
            "loadNative",
            String::class.java,
            object : XC_MethodHook() {
                @SuppressLint("UnsafeDynamicallyLoadedCode")
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)

                    Log.i(TAG, "UnityPlayer.loadNative")

                    if (alreadyInitialized) {
                        return
                    }

                    val app = AndroidAppHelper.currentApplication()
                    if (app == null) {
                        Log.e(TAG, "UnityPlayer.loadNative: application is null")
                        return
                    }

                    if (ensureNativeLibraryLoaded(app.applicationContext)) {
                        showToast("lib$nativeLibName.so loaded.")
                    }
                    else {
                        Log.e(TAG, "Load native library lib$nativeLibName.so failed: $nativeLibLoadError")
                        showToast("Load native library lib$nativeLibName.so failed.")
                        return
                    }

                    if (!gkmsDataInited) {
                        requestConfig(app.applicationContext)
                    }
                    applyPendingConfig()

                    FilesChecker.initDir(app.filesDir, modulePath)
                    try {
                        initHook(
                            "${app.applicationInfo.nativeLibraryDir}/libil2cpp.so",
                            File(
                                app.filesDir.absolutePath,
                                FilesChecker.localizationFilesDir
                            ).absolutePath
                        )
                    }
                    catch (e: Throwable) {
                        Log.e(TAG, "initHook failed", e)
                        showToast("Init native hook failed.")
                        return
                    }

                    alreadyInitialized = true
                    startLoopIfNeeded()
                }
            })
    }

    private fun ensureNativeLibraryLoaded(context: Context): Boolean {
        if (nativeLibLoadSuccess) {
            return true
        }

        synchronized(this) {
            if (nativeLibLoadSuccess) {
                return true
            }

            nativeLibLoadError = null
            if (trySystemLoadLibrary(nativeLibName)) {
                nativeLibLoadSuccess = true
                nativeLibLoadError = null
                return true
            }

            if (tryLoadExtractedNativeLibraries(context)) {
                nativeLibLoadSuccess = true
                nativeLibLoadError = null
                return true
            }

            return false
        }
    }

    private fun trySystemLoadLibrary(libName: String): Boolean {
        return try {
            System.loadLibrary(libName)
            Log.i(TAG, "Loaded native library with System.loadLibrary($libName)")
            true
        }
        catch (e: UnsatisfiedLinkError) {
            nativeLibLoadError = "System.loadLibrary($libName): ${e.message}"
            Log.w(TAG, "System.loadLibrary($libName) failed", e)
            false
        }
        catch (e: Throwable) {
            nativeLibLoadError = "System.loadLibrary($libName): ${e.message}"
            Log.e(TAG, "System.loadLibrary($libName) failed", e)
            false
        }
    }

    private fun tryLoadExtractedNativeLibraries(context: Context): Boolean {
        var loadedMainLibrary = false
        for (libName in listOf("xdl", "shadowhook", nativeLibName)) {
            val libFile = findAndExtractNativeLibrary(context, libName, libName == nativeLibName)
            if (libFile == null) {
                if (libName == nativeLibName) {
                    return false
                }
                continue
            }

            val loaded = tryLoadNativeFile(libName, libFile)
            if (libName == nativeLibName) {
                loadedMainLibrary = loaded
                if (!loaded) {
                    return false
                }
            }
        }
        return loadedMainLibrary
    }

    private fun tryLoadNativeFile(libName: String, libFile: File): Boolean {
        return try {
            System.load(libFile.absolutePath)
            Log.i(TAG, "Loaded native library $libName from ${libFile.absolutePath}")
            true
        }
        catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already loaded", ignoreCase = true) == true) {
                Log.i(TAG, "Native library $libName is already loaded")
                true
            }
            else {
                nativeLibLoadError = "System.load(${libFile.name}): ${e.message}"
                Log.e(TAG, "System.load(${libFile.absolutePath}) failed", e)
                false
            }
        }
        catch (e: Throwable) {
            nativeLibLoadError = "System.load(${libFile.name}): ${e.message}"
            Log.e(TAG, "System.load(${libFile.absolutePath}) failed", e)
            false
        }
    }

    private fun findAndExtractNativeLibrary(
        context: Context,
        libName: String,
        required: Boolean
    ): File? {
        val libFileName = "lib$libName.so"
        val sources = nativeSourceCandidates(context)

        for (source in sources) {
            extractNativeLibraryFromApk(context, source, libFileName)?.let {
                return it
            }
        }

        if (required) {
            nativeLibLoadError = "$libFileName not found in: ${sources.joinToString { it.absolutePath }}"
        }
        return null
    }

    private fun nativeSourceCandidates(context: Context): List<File> {
        val paths = mutableListOf<String>()
        if (::modulePath.isInitialized) {
            paths += modulePath.substringBefore("!")
        }
        paths += context.applicationInfo.sourceDir
        paths += context.packageCodePath

        return paths
            .filter { it.isNotBlank() }
            .distinct()
            .map { File(it) }
            .filter { it.isFile }
    }

    private fun extractNativeLibraryFromApk(context: Context, apkFile: File, libFileName: String): File? {
        return try {
            ZipFile(apkFile).use { zip ->
                extractNativeLibraryFromZip(
                    context,
                    zip,
                    "${apkFile.absolutePath}:${apkFile.length()}:${apkFile.lastModified()}",
                    libFileName
                )?.let {
                    return it
                }

                val embeddedApks = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()

                for (embeddedEntry in embeddedApks) {
                    val embeddedApk = extractEmbeddedApk(context, zip, embeddedEntry) ?: continue
                    ZipFile(embeddedApk).use { embeddedZip ->
                        extractNativeLibraryFromZip(
                            context,
                            embeddedZip,
                            "${apkFile.absolutePath}!${embeddedEntry.name}:${embeddedEntry.crc}:${embeddedEntry.size}",
                            libFileName
                        )?.let {
                            return it
                        }
                    }
                }
            }
            null
        }
        catch (e: Throwable) {
            Log.w(TAG, "Cannot inspect native libraries from ${apkFile.absolutePath}", e)
            null
        }
    }

    private fun extractNativeLibraryFromZip(
        context: Context,
        zip: ZipFile,
        sourceStamp: String,
        libFileName: String
    ): File? {
        for (abi in supportedAbis()) {
            val entry = zip.getEntry("lib/$abi/$libFileName") ?: continue
            return copyZipEntryToCache(context, zip, entry, abi, libFileName, sourceStamp)
        }
        return null
    }

    private fun extractEmbeddedApk(context: Context, zip: ZipFile, entry: java.util.zip.ZipEntry): File? {
        val outDir = File(context.codeCacheDir, "gkms-localify-native/embedded")
        val outFile = File(
            outDir,
            "module-${Integer.toHexString(entry.name.hashCode())}-${entry.crc}.apk"
        )
        val metaFile = File(outDir, "${outFile.name}.meta")
        val stamp = "${entry.name}:${entry.crc}:${entry.size}"

        return copyZipEntryToFile(zip, entry, outDir, outFile, metaFile, stamp)
    }

    private fun copyZipEntryToCache(
        context: Context,
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        abi: String,
        libFileName: String,
        sourceStamp: String
    ): File? {
        val outDir = File(context.codeCacheDir, "gkms-localify-native/$abi")
        val outFile = File(outDir, libFileName)
        val metaFile = File(outDir, "$libFileName.meta")
        val stamp = "$sourceStamp:${entry.name}:${entry.crc}:${entry.size}"

        return copyZipEntryToFile(zip, entry, outDir, outFile, metaFile, stamp)?.also {
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
    }

    private fun copyZipEntryToFile(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        outDir: File,
        outFile: File,
        metaFile: File,
        stamp: String
    ): File? {
        try {
            if (!outDir.exists() && !outDir.mkdirs()) {
                nativeLibLoadError = "Cannot create ${outDir.absolutePath}"
                return null
            }

            val currentStamp = if (metaFile.isFile) {
                runCatching { metaFile.readText() }.getOrNull()
            }
            else {
                null
            }

            if (outFile.isFile && currentStamp == stamp) {
                return outFile
            }

            val tempFile = File(outDir, "${outFile.name}.tmp")
            zip.getInputStream(entry).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "Cannot delete old file ${outFile.absolutePath}; overwriting")
            }

            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }

            metaFile.writeText(stamp)
            return outFile
        }
        catch (e: Throwable) {
            nativeLibLoadError = "Cannot extract ${entry.name}: ${e.message}"
            Log.e(TAG, "Cannot extract ${entry.name}", e)
            return null
        }
    }

    private fun supportedAbis(): List<String> {
        return (Build.SUPPORTED_ABIS.toList() + "arm64-v8a").distinct()
    }

    private fun applyPendingConfig() {
        val data = pendingGkmsData ?: return
        if (!nativeLibLoadSuccess) {
            return
        }

        try {
            loadConfig(data)
            pendingGkmsData = null
            Log.d(TAG, "gkmsData: $data")
        }
        catch (e: Throwable) {
            Log.e(TAG, "loadConfig failed", e)
        }
    }

    private fun startLoopIfNeeded() {
        if (loopStarted) {
            return
        }

        synchronized(this) {
            if (loopStarted) {
                return
            }
            loopStarted = true
            startLoop()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startLoop() {
        GlobalScope.launch {
            val interval = 1000L / 30
            var lastFrameStartInit = NativeInitProgress.startInit
            val initProgressUI = InitProgressUI()

            while (isActive) {
                val timeTaken = measureTimeMillis {
                    val returnValue = pluginCallbackLooper()  // plugin main thread loop
                    if (returnValue == 9) {
                        NativeInitProgress.startInit = true
                    }

                    if (NativeInitProgress.startInit) {  // if init, update data
                        NativeInitProgress.pluginInitProgressLooper(NativeInitProgress)
                        gameActivity?.let { initProgressUI.updateData(it) }
                    }

                    if ((gameActivity != null) && (lastFrameStartInit != NativeInitProgress.startInit)) {  // change status
                        if (NativeInitProgress.startInit) {
                            initProgressUI.createView(gameActivity!!)
                        }
                        else {
                            initProgressUI.finishLoad(gameActivity!!)
                        }
                    }
                    lastFrameStartInit = NativeInitProgress.startInit
                }
                delay(interval - timeTaken)
            }
        }
    }

    fun initGkmsConfig(activity: Activity) {
        val intent = activity.intent
        val gkmsData = intent.getStringExtra("gkmsData")
        val programData = intent.getStringExtra("localData")
        if (gkmsData != null) {
            val readVersion = intent.getStringExtra("lVerName")
            checkPluginVersion(activity, readVersion)

            gkmsDataInited = true
            val initConfig = try {
                json.decodeFromString<GakumasConfig>(gkmsData)
            }
            catch (e: Exception) {
                null
            }
            val programConfig = try {
                if (programData == null) {
                    ProgramConfig()
                } else {
                    json.decodeFromString<ProgramConfig>(programData)
                }
            }
            catch (e: Exception) {
                null
            }

            // 清理本地文件
            if (programConfig?.cleanLocalAssets == true) {
                FilesChecker.cleanAssets()
            }

            // 检查 files 版本和 assets 版本并更新
            if (programConfig?.checkBuiltInAssets == true) {
                FilesChecker.initAndCheck(activity.filesDir, modulePath)
            }

            // 强制导出 assets 文件
            if (initConfig?.forceExportResource == true) {
                FilesChecker.updateFiles()
            }

            // 使用热更新文件
            if ((programConfig?.useRemoteAssets == true) || (programConfig?.useAPIAssets == true)) {
                // val dataUri = intent.data
                val dataUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("resource_file", Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>("resource_file")
                }

                if (dataUri != null) {
                    if (!externalFilesChecked) {
                        externalFilesChecked = true
                        // Log.d(TAG, "dataUri: $dataUri")
                        FileHotUpdater.updateFilesFromZip(activity, dataUri, activity.filesDir,
                            programConfig.delRemoteAfterUpdate)
                    }
                }
                else if (programConfig.useAPIAssets) {
                    if (!File(activity.filesDir, localizationFilesDir).exists() &&
                        (initConfig?.forceExportResource == false)) {
                        // 使用 API 资源，不检查内置，API 资源无效，且游戏内没有插件数据时，释放内置数据
                        FilesChecker.initAndCheck(activity.filesDir, modulePath)
                    }
                }
            }

            if (initConfig?.replaceTexture == true && !textureFilesChecked) {
                val textureDataUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("texture_resource_file", Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>("texture_resource_file")
                }

                if (textureDataUri != null) {
                    Log.i(TAG, "Texture resource uri received: $textureDataUri")
                    textureFilesChecked = true
                    TextureResourceUpdater.updateTextureFilesFromZip(activity, textureDataUri,
                        activity.filesDir, programConfig?.delTextureRemoteAfterUpdate ?: true)
                }
                else {
                    Log.i(TAG, "Texture resource uri missing.")
                }
            }

            pendingGkmsData = gkmsData
            if (nativeLibLoadSuccess) {
                applyPendingConfig()
            }
            else {
                Log.d(TAG, "Deferring loadConfig until native library is loaded")
            }
        }
    }

    private fun checkPluginVersion(activity: Activity, readVersion: String?) {
        val buildVersionName = BuildConfig.MODULE_VERSION_NAME
        Log.i(TAG, "Checking Plugin Version: Build: $buildVersionName, Request: $readVersion")
        if (readVersion?.trim() == buildVersionName.trim()) {
            return
        }

        val builder = AlertDialog.Builder(activity)
        val infoBuilder = AlertDialog.Builder(activity)
        builder.setTitle("Warning")
        builder.setCancelable(false)
        builder.setMessage(when (getCurrentLanguage(activity)) {
            "zh" -> "检测到插件版本不一致\n内置版本: $buildVersionName\n请求版本: $readVersion\n\n这可能是使用了 LSPatch 的集成模式，仅更新了插件本体，未重新修补游戏导致的。请使用 $readVersion 版本的插件重新修补或使用本地模式。"
            else -> "Detected plugin version mismatch\nBuilt-in version: $buildVersionName\nRequested version: $readVersion\n\nThis may be caused by using the LSPatch integration mode, where only the plugin itself was updated without re-patching the game. Please re-patch the game using the $readVersion version of the plugin or use the local mode."
        })

        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }

        builder.setNegativeButton("Exit") { dialog, _ ->
            dialog.dismiss()
            activity.finishAffinity()
        }

        val dialog = builder.create()

        infoBuilder.setOnCancelListener {
            dialog.show()
        }

        dialog.show()
    }

    private fun showGetConfigFailedImpl(activity: Context, title: String, msg: String, infoButton: String, dlButton: String, okButton: String) {
        if (getConfigError == null) return
        val builder = AlertDialog.Builder(activity)
        val infoBuilder = AlertDialog.Builder(activity)
        val errConfigStr = getConfigError.toString()
        builder.setTitle("$title: $errConfigStr")
        getConfigError = null
        builder.setCancelable(false)
        builder.setMessage(msg)

        builder.setPositiveButton(okButton) { dialog, _ ->
            dialog.dismiss()
        }

        builder.setNegativeButton(dlButton) { dialog, _ ->
            dialog.dismiss()
            val webpage = Uri.parse("https://github.com/chinosk6/gakuen-imas-localify")
            val intent = Intent(Intent.ACTION_VIEW, webpage)
            activity.startActivity(intent)
        }

        builder.setNeutralButton(infoButton) { _, _ ->
            infoBuilder.setTitle("Error Info")
            infoBuilder.setMessage(errConfigStr)
            val infoDialog = infoBuilder.create()
            infoDialog.show()
        }

        val dialog = builder.create()

        infoBuilder.setOnCancelListener {
            dialog.show()
        }

        dialog.show()
    }

    fun showGetConfigFailed(activity: Context) {
        val langData = when (getCurrentLanguage(activity)) {
            "zh" -> {
                mapOf(
                    "title" to "无法读取设置",
                    "message" to "配置读取失败，将使用默认配置。\n" +
                            "可能是您使用了 LSPatch 等工具的集成模式，也有可能是您拒绝了拉起插件的权限。\n" +
                            "若您使用了 LSPatch 等工具的集成模式，且没有单独安装插件本体，请下载插件本体。\n" +
                            "若您安装了插件本体，却弹出这个错误，请允许本应用拉起其他应用。",
                    "infoButton" to "详情",
                    "dlButton" to "下载",
                    "okButton" to "确定"
                )
            }
            else -> {
                mapOf(
                    "title" to "Get Config Failed",
                    "message" to "Configuration loading failed, the default configuration will be used.\n" +
                            "This might be due to the use the integration mode of LSPatch, or possibly because you denied the permission to launch the plugin.\n" +
                            "If you used the integration mode of LSPatch and did not install the plugin itself separately, please download the plugin.\n" +
                            "If you have installed the plugin but still see this error, please allow this application to launch other applications.",
                    "infoButton" to "Info",
                    "dlButton" to "Download",
                    "okButton" to "OK"
                )
            }
        }
        showGetConfigFailedImpl(activity, langData["title"]!!, langData["message"]!!, langData["infoButton"]!!,
            langData["dlButton"]!!, langData["okButton"]!!)
    }

    private fun getCurrentLanguage(context: Context): String {
        val locale: Locale = context.resources.configuration.locales.get(0)
        return locale.language
    }

    fun requestConfig(activity: Context) {
        try {
            val intent = Intent().apply {
                setClassName("io.github.chinosk.gakumas.localify", "io.github.chinosk.gakumas.localify.TranslucentActivity")
                putExtra("gkmsData", "requestConfig")
                flags = FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        }
        catch (e: Exception) {
            getConfigError = e
            val fakeActivity = Activity().apply {
                intent = Intent().apply {
                    putExtra("gkmsData", "{}")
                }
            }
            initGkmsConfig(fakeActivity)
        }

    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    companion object {
        @JvmStatic
        external fun initHook(targetLibraryPath: String, localizationFilesDir: String)
        @JvmStatic
        external fun keyboardEvent(keyCode: Int, action: Int)
        @JvmStatic
        external fun joystickEvent(
            action: Int,
            leftStickX: Float,
            leftStickY: Float,
            rightStickX: Float,
            rightStickY: Float,
            leftTrigger: Float,
            rightTrigger: Float,
            hatX: Float,
            hatY: Float
        )
        @JvmStatic
        external fun loadConfig(configJsonStr: String)

        // Toast快速切换内容
        private var toast: Toast? = null

        @JvmStatic
        fun showToast(message: String) {
            val app = AndroidAppHelper.currentApplication()
            val context = app?.applicationContext
            if (context != null) {
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    // 取消之前的 Toast
                    toast?.cancel()
                    // 创建新的 Toast
                    toast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
                    // 展示新的 Toast
                    toast?.show()
                }
            }
            else {
                Log.e(TAG, "showToast: $message failed: applicationContext is null")
            }
        }

        @JvmStatic
        external fun pluginCallbackLooper(): Int
    }

    init {
        ShadowHook.init(
            ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .build()
        )

        nativeLibLoadSuccess = trySystemLoadLibrary(nativeLibName)
    }
}
