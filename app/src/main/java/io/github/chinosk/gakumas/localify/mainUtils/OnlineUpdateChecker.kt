package io.github.chinosk.gakumas.localify.mainUtils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.util.zip.ZipFile


@Serializable
data class ReleaseAssetInfo(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0,
    val content_type: String? = null
)


@Serializable
data class ReleaseInfo(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val published_at: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAssetInfo> = emptyList()
)


@Serializable
data class OnlinePatchAsset(
    val name: String,
    @SerialName("browserDownloadUrl")
    val browserDownloadUrl: String,
    val sha256: String? = null,
    val size: Long = 0,
    val contentType: String? = null
)


@Serializable
data class GamePatchMetadata(
    val schemaVersion: Int = 1,
    val kind: String = "gakumas-game-patch",
    val gamePackageName: String,
    val gameVersion: String,
    val releaseTag: String,
    val patchMode: String = "lspatch-manager",
    val languagePackMode: String = "user-selectable",
    val appPackageName: String = "io.github.chinosk.gakumas.localify",
    val minimumAppVersionCode: Long = 0,
    val assets: List<OnlinePatchAsset> = emptyList()
)


data class AppUpdate(
    val release: ReleaseInfo,
    val asset: ReleaseAssetInfo,
    val currentVersionName: String,
    val latestVersionName: String
)


data class GamePatchUpdate(
    val release: ReleaseInfo,
    val metadata: GamePatchMetadata,
    val asset: OnlinePatchAsset,
    val installedVersionName: String?,
    val installedIsPatched: Boolean,
    val installedPatchMode: String?,
    val installedSha256: String?
)


data class OnlineUpdateResult(
    val appUpdate: AppUpdate?,
    val gamePatchUpdate: GamePatchUpdate?
)


internal fun compareVersionNames(left: String, right: String): Int {
    val leftParts = Regex("\\d+").findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
    val rightParts = Regex("\\d+").findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
    val size = maxOf(leftParts.size, rightParts.size)
    for (i in 0 until size) {
        val l = leftParts.getOrElse(i) { 0 }
        val r = rightParts.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}


internal fun findLatestRelease(
    releases: List<ReleaseInfo>,
    tagPrefix: String,
    hasRequiredAsset: (ReleaseAssetInfo) -> Boolean
): ReleaseInfo? {
    return releases
        .asSequence()
        .filter { it.tag_name.startsWith(tagPrefix) && it.assets.any(hasRequiredAsset) }
        .maxWithOrNull(Comparator { left, right ->
            val versionOrder = compareVersionNames(
                left.tag_name.removePrefix(tagPrefix),
                right.tag_name.removePrefix(tagPrefix)
            )
            if (versionOrder != 0) versionOrder else left.published_at.compareTo(right.published_at)
        })
}


internal fun shouldUpdateGamePatch(
    installedVersion: String?,
    installedIsPatched: Boolean,
    installedPatchMode: String?,
    targetVersion: String,
    targetPatchMode: String
): Boolean {
    if (installedVersion == null) return true
    return when (compareVersionNames(targetVersion, installedVersion)) {
        1 -> true
        -1 -> false
        else -> !installedIsPatched || installedPatchMode != targetPatchMode
    }
}


object OnlineUpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/fullpie/Gakumas_apptest/releases?per_page=30"
    const val GAME_PACKAGE_NAME = "com.bandainamcoent.idolmaster_gakuen"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkUpdates(
        context: Context,
        onResult: (OnlineUpdateResult) -> Unit,
        onFailed: (String) -> Unit
    ) {
        thread(name = "online-update-checker") {
            runCatching {
                val releases = fetchReleases()
                    .filter { !it.draft }

                val appUpdate = findAppUpdate(context, releases)
                val gameUpdate = findGamePatchUpdate(context, releases)
                postMain {
                    onResult(OnlineUpdateResult(appUpdate, gameUpdate))
                }
            }.onFailure { e ->
                postMain {
                    onFailed(e.message ?: e.toString())
                }
            }
        }
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun fetchReleases(): List<ReleaseInfo> {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub releases request failed: ${response.code}")
            val body = response.body?.string() ?: throw IOException("GitHub releases response is empty")
            return json.decodeFromString(body)
        }
    }

    private fun fetchGameMetadata(asset: ReleaseAssetInfo): GamePatchMetadata {
        val request = Request.Builder()
            .url(asset.browser_download_url)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Game metadata request failed: ${response.code}")
            val body = response.body?.string() ?: throw IOException("Game metadata response is empty")
            return json.decodeFromString(body)
        }
    }

    private fun findAppUpdate(context: Context, releases: List<ReleaseInfo>): AppUpdate? {
        val currentVersion = getOwnVersionName(context)
        val release = findLatestRelease(releases, "app-v") {
            it.name.endsWith(".apk", ignoreCase = true)
        } ?: return null
        val latestVersion = release.tag_name.removePrefix("app-")
        if (compareVersionNames(latestVersion, currentVersion) <= 0) return null
        val asset = release.assets.first { it.name.endsWith(".apk", ignoreCase = true) }
        return AppUpdate(release, asset, currentVersion, latestVersion)
    }

    private fun findGamePatchUpdate(context: Context, releases: List<ReleaseInfo>): GamePatchUpdate? {
        val release = findLatestRelease(releases, "game-v") {
            it.name == "gkms-game-patch.json"
        } ?: return null
        val metadataAsset = release.assets.first { it.name == "gkms-game-patch.json" }
        val metadata = fetchGameMetadata(metadataAsset)
        val patchedApk = metadata.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true)
        } ?: return null
        val installedVersion = getPackageVersionName(context, metadata.gamePackageName)
        val installedIsPatched = isPackagePatched(context, metadata.gamePackageName)
        val installedPatchMode = getInstalledPatchMode(context, metadata.gamePackageName)
        if (!shouldUpdateGamePatch(
                installedVersion,
                installedIsPatched,
                installedPatchMode,
                metadata.gameVersion,
                metadata.patchMode
            )
        ) return null

        // A same-version patched APK can be rebuilt with a different hash; that alone must not force a game update.
        val installedSha256 = if (installedIsPatched) {
            getInstalledPackageSha256(context, metadata.gamePackageName)
        } else {
            null
        }
        return GamePatchUpdate(
            release,
            metadata,
            patchedApk,
            installedVersion,
            installedIsPatched,
            installedPatchMode,
            installedSha256
        )
    }

    private fun getOwnVersionName(context: Context): String {
        return getPackageVersionName(context, context.packageName) ?: "0"
    }

    private fun getPackageVersionName(context: Context, packageName: String): String? {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
    }

    private fun getApplicationInfo(context: Context, packageName: String): ApplicationInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }
    }

    private fun isPackagePatched(context: Context, packageName: String): Boolean {
        return runCatching {
            hasLspatchMetadata(getApplicationInfo(context, packageName))
        }.getOrDefault(false)
    }

    private fun hasLspatchMetadata(appInfo: ApplicationInfo): Boolean {
        return appInfo.metaData?.containsKey("lspatch") == true
    }

    private fun getInstalledPatchMode(context: Context, packageName: String): String? {
        return runCatching {
            val appInfo = getApplicationInfo(context, packageName)
            ZipFile(appInfo.sourceDir).use { apk ->
                val entry = apk.getEntry("assets/lspatch/config.json") ?: return@runCatching null
                val text = apk.getInputStream(entry).bufferedReader().use { it.readText() }
                val config = json.parseToJsonElement(text).jsonObject
                val useManager = config["useManager"]?.jsonPrimitive?.booleanOrNull ?: false
                if (useManager) "lspatch-manager" else "lspatch-embedded"
            }
        }.getOrNull()
    }

    private fun getInstalledPackageSha256(context: Context, packageName: String): String? {
        return runCatching {
            val appInfo = getApplicationInfo(context, packageName)
            val source = File(appInfo.sourceDir)
            if (!source.isFile) return@runCatching null

            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val cacheKey = "${packageName}:${packageInfo.lastUpdateTime}:${source.length()}:${source.lastModified()}"
            val prefs = context.getSharedPreferences("online_update_checker", Context.MODE_PRIVATE)
            val keyName = "sha256_key_$packageName"
            val valueName = "sha256_value_$packageName"
            if (prefs.getString(keyName, null) == cacheKey) {
                return@runCatching prefs.getString(valueName, null)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            prefs.edit()
                .putString(keyName, cacheKey)
                .putString(valueName, sha256)
                .apply()
            sha256
        }.getOrNull()
    }

}
