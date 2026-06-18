package io.github.chinosk.gakumas.localify.mainUtils


import android.content.IntentSender
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper


// From https://github.com/LSPosed/LSPatch/blob/master/manager/src/main/java/org/lsposed/lspatch/util/ShizukuApi.kt
object ShizukuApi {
    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private var initialized = false

    private val iPackageManager: IPackageManager by lazy {
        IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package").wrap())
    }

    var isBinderAvailable = false
    var isPermissionGranted by mutableStateOf(false)

    fun init() {
        if (initialized) return
        initialized = true
        HiddenApiBypass.addHiddenApiExemptions("")
        HiddenApiBypass.addHiddenApiExemptions("Landroid/content", "Landroid/os")
        Shizuku.addBinderReceivedListenerSticky {
            isBinderAvailable = true
            isPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
        Shizuku.addBinderDeadListener {
            isBinderAvailable = false
            isPermissionGranted = false
        }
    }

    fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        val userId = Process.myUserHandle().hashCode()
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA.toLong(), userId)
        } else {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
        }
        return (app != null) && (app.metaData?.containsKey("lspatch") != true)
    }

    fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        // packageInstaller.uninstall(packageName, intentSender)
    }

    fun performDexOptMode(packageName: String): Boolean {
        return iPackageManager.performDexOptMode(
            packageName,
            SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false),
            "verify", true, true, null
        )
    }

}
