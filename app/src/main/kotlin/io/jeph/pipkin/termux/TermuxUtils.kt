package io.jeph.pipkin.termux

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri
import java.security.MessageDigest

internal enum class TermuxInstallationStatus {
    NOT_INSTALLED,
    UNSUPPORTED,
    SUPPORTED,
}

internal object TermuxUtils {
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val PACKAGE_NAME = "com.termux"
    private const val INSTALL_URL = "https://f-droid.org/packages/com.termux/"
    private const val MINOR_VERSION_MIN = 118

    // Upstream TermuxConstants signing for detecting supported and unsupported Termux distributions.
    // https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
    private const val FDROID_CERTIFICATE = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
    private const val GITHUB_CERTIFICATE = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"
    private val versionPattern = Regex("""(\d+)\.(\d+)\.(\d+)""")

    fun getTermuxInstallationStatus(context: Context): TermuxInstallationStatus {
        val info = try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return TermuxInstallationStatus.NOT_INSTALLED
        }

        val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString(HexFormat.UpperCase)
        }
        if (certificate != FDROID_CERTIFICATE && certificate != GITHUB_CERTIFICATE) {
            return TermuxInstallationStatus.UNSUPPORTED
        }

        val version = info.versionName?.let(versionPattern::matchEntire)
        val major = version?.groupValues?.get(1)?.toIntOrNull()
        val minor = version?.groupValues?.get(2)?.toIntOrNull()
        return if (major != null && minor != null && (major > 0 || minor >= MINOR_VERSION_MIN)) {
            TermuxInstallationStatus.SUPPORTED
        } else {
            TermuxInstallationStatus.UNSUPPORTED
        }
    }

    fun hasRunCommandPermission(context: Context): Boolean =
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun openRunCommandPermissionSettings(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))

    fun openTermux(context: Context): Boolean =
        launch(context, context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME))

    fun openTermuxInstallationPage(context: Context): Boolean =
        launch(context, Intent(Intent.ACTION_VIEW, INSTALL_URL.toUri()))

    private fun launch(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
