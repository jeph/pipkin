package io.jeph.pipkin.termux

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import java.security.MessageDigest

/** Detects which Termux build is installed and opens the screens that fix an unsupported one. */
internal object TermuxInstall {
  enum class Status {
    NotInstalled,

    /** Google Play's build: its signature differs and it cannot share RUN_COMMAND with F-Droid's. */
    PlayStore,
    UnsupportedBuild,
    TooOld,
    Supported,
  }

  const val PACKAGE_NAME = "com.termux"
  const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

  private const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
  private const val PLAY_STORE_INSTALLER = "com.android.vending"
  private const val MINOR_VERSION_MIN = 118

  // Termux's F-Droid signing certificate, from upstream TermuxConstants:
  // https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
  // Only F-Droid holds this key, so a match proves an official, unmodified build. Termux's GitHub builds are left out
  // on purpose: they are signed with app/testkey_untrusted.jks, whose password is public, so anyone can sign with it.
  private const val FDROID_CERTIFICATE = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"

  /** The `major.minor` a `versionName` starts with, so prereleases such as `0.119.0-beta.3` parse too. */
  private val versionPattern = Regex("""^(\d+)\.(\d+)""")

  fun status(context: Context): Status {
    val manager = context.packageManager
    val info = try {
      manager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
      return Status.NotInstalled
    }
    val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()?.let {
      MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString(HexFormat.UpperCase)
    }
    return classify(certificate, info.versionName, installer(manager))
  }

  /**
   * Pure decision over the certificate's SHA-256 (upper-case hex), `versionName` and installer package. Any F-Droid
   * build from 0.118 on is supported, betas included; one whose version doesn't parse is trusted on its signature.
   */
  fun classify(certificate: String?, versionName: String?, installer: String?): Status {
    if (certificate != FDROID_CERTIFICATE) {
      return if (installer == PLAY_STORE_INSTALLER) Status.PlayStore else Status.UnsupportedBuild
    }
    val version = versionName?.let(versionPattern::find) ?: return Status.Supported
    val major = version.groupValues[1].toInt()
    val minor = version.groupValues[2].toInt()
    return if (major > 0 || minor >= MINOR_VERSION_MIN) Status.Supported else Status.TooOld
  }

  /** Installed `versionName`, or null when Termux is absent. */
  fun version(context: Context): String? = try {
    context.packageManager.getPackageInfo(PACKAGE_NAME, 0).versionName
  } catch (_: PackageManager.NameNotFoundException) {
    null
  }

  fun hasRunCommandPermission(context: Context): Boolean =
    context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

  /** Where the user re-enables RUN_COMMAND after Android stops showing the permission prompt. */
  fun openRunCommandPermissionSettings(context: Context) = context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
  )

  fun openTermux(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
      ?: throw ActivityNotFoundException("Termux has no launchable activity")
    context.startActivity(intent)
  }

  fun openFDroidPage(context: Context) = context.startActivity(Intent(Intent.ACTION_VIEW, FDROID_URL.toUri()))

  /** Asks Android to uninstall Termux; required before installing a build with another signature. */
  fun requestUninstall(context: Context) =
    context.startActivity(Intent(Intent.ACTION_DELETE, "package:$PACKAGE_NAME".toUri()))

  private fun installer(manager: PackageManager): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      manager.getInstallSourceInfo(PACKAGE_NAME).installingPackageName
    } else {
      @Suppress("DEPRECATION") // getInstallSourceInfo needs API 30; minSdk is 29.
      manager.getInstallerPackageName(PACKAGE_NAME)
    }
  } catch (_: PackageManager.NameNotFoundException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}
