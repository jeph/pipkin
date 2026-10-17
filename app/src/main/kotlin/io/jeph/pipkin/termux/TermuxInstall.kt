package io.jeph.pipkin.termux

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import java.security.MessageDigest

/** Detects which Termux build is installed and opens the screens that install, update or replace it. */
internal object TermuxInstall {
  enum class Status {
    NotInstalled,

    /** Signed by neither F-Droid nor Google Play, e.g. Termux's GitHub builds; Pipkin runs nothing in it. */
    UnsupportedBuild,
    FDroidTooOld,
    PlayTooOld,

    /** F-Droid's build: Pipkin starts its hosts with Termux's RUN_COMMAND service. */
    FDroid,

    /** Google Play's build: it has no RUN_COMMAND service, so Pipkin starts its hosts through its helper in Termux. */
    Play,
    ;

    /** Pipkin runs in this build. A too-old build is still tried; its update is only asked for on the setup page. */
    val isTrusted: Boolean get() = this != NotInstalled && this != UnsupportedBuild

    val isPlay: Boolean get() = this == Play || this == PlayTooOld
  }

  const val PACKAGE_NAME = "com.termux"
  const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

  private const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
  private const val PLAY_URL = "https://play.google.com/store/apps/details?id=com.termux"
  private const val MINOR_VERSION_MIN = 118

  /** The first Play build Pipkin was tested with. Play keeps the app current, so an older one only asks for an update. */
  private const val PLAY_VERSION_MIN = "2026.10.09"

  // Both signing certificates come from upstream TermuxConstants:
  // https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
  // Only F-Droid and Google Play hold these keys, so a match proves an official, unmodified build. Termux's GitHub
  // builds are left out on purpose: they are signed with app/testkey_untrusted.jks, whose password is public, so anyone
  // can sign with it.
  private const val FDROID_CERTIFICATE = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
  private const val PLAY_CERTIFICATE = "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9"

  /** The `major.minor` a `versionName` starts with, so prereleases such as `0.119.0-beta.3` parse too. */
  private val versionPattern = Regex("""^(\d+)\.(\d+)""")

  /** Play builds are named by release date, such as `googleplay.2026.10.09`; older Play builds used `0.1xx`. */
  private val playVersionPattern = Regex("""^googleplay\.(\d{4}\.\d{2}\.\d{2})""")

  fun status(context: Context): Status = packageInfo(context)?.let(::status) ?: Status.NotInstalled

  /**
   * Identifies one installation of Termux: its build and when it was installed. Reinstalling Termux, or switching
   * between the F-Droid and Play builds, wipes Termux's home folder, and with it Pi and everything Pipkin set up there.
   */
  fun installation(context: Context): String? = packageInfo(context)?.let { info ->
    val build = if (status(info).isPlay) "play" else "fdroid"
    "$build:${info.firstInstallTime}"
  }

  /**
   * Pure decision over the certificate's SHA-256 (upper-case hex) and `versionName`. Any F-Droid build from 0.118 on
   * is supported, betas included, and one whose version doesn't parse is trusted on its signature. A Play build needs
   * a dated `googleplay.` version from [PLAY_VERSION_MIN] on.
   */
  fun classify(certificate: String?, versionName: String?): Status = when (certificate) {
    FDROID_CERTIFICATE -> classifyFDroid(versionName)
    PLAY_CERTIFICATE -> classifyPlay(versionName)
    else -> Status.UnsupportedBuild
  }

  /** Installed `versionName`, or null when Termux is absent. */
  fun version(context: Context): String? = packageInfo(context)?.versionName

  fun hasRunCommandPermission(context: Context): Boolean =
    context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

  /** Where the user re-enables RUN_COMMAND after Android stops showing the permission prompt. */
  fun openRunCommandPermissionSettings(context: Context) = context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
  )

  fun openTermux(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
      ?: throw ActivityNotFoundException("Termux has no launchable activity")
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  /**
   * Opens Termux in a new terminal session, whose login shell runs the hook setup wrote to start Pipkin's helper.
   * Termux adds a session for [Intent.ACTION_RUN] (its launcher's "New session" shortcut) only while creating its
   * activity, so the task is cleared first; the sessions themselves live on in Termux's service.
   */
  fun openTermuxNewSession(context: Context) = context.startActivity(
    Intent(Intent.ACTION_RUN)
      .setClassName(PACKAGE_NAME, "com.termux.app.TermuxActivity")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
  )

  fun openFDroidPage(context: Context) = context.startActivity(Intent(Intent.ACTION_VIEW, FDROID_URL.toUri()))

  /** Termux's Play Store listing; the Play Store app opens the web link when it's installed. */
  fun openPlayPage(context: Context) = context.startActivity(Intent(Intent.ACTION_VIEW, PLAY_URL.toUri()))

  /** Asks Android to uninstall Termux; required before installing a build with another signature. */
  fun requestUninstall(context: Context) =
    context.startActivity(Intent(Intent.ACTION_DELETE, "package:$PACKAGE_NAME".toUri()))

  /** Termux's app info, where the user lets it run in the background without battery restrictions. */
  fun openTermuxAppSettings(context: Context) = context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$PACKAGE_NAME".toUri()),
  )

  /** Android 11 added the All files access page. */
  val allFilesAccessAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  /**
   * Termux's All files access toggle, which both builds declare; the Play build has no storage prompt. Some phones only
   * open the list of apps, where the user picks Termux.
   */
  @SuppressLint("InlinedApi") // Callers check allFilesAccessAvailable; the Play build requires Android 11.
  fun openTermuxFilesAccess(context: Context) {
    try {
      context.startActivity(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$PACKAGE_NAME".toUri()),
      )
    } catch (_: ActivityNotFoundException) {
      context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
  }

  /** Termux is exempt from battery optimization; false when it is absent. */
  fun termuxBackgroundAllowed(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(PACKAGE_NAME)

  /** The Play build requires Android 11. */
  val playBuildAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  private fun packageInfo(context: Context): PackageInfo? = try {
    context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
  } catch (_: PackageManager.NameNotFoundException) {
    null
  }

  private fun status(info: PackageInfo): Status {
    val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()?.let {
      MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString(HexFormat.UpperCase)
    }
    return classify(certificate, info.versionName)
  }

  private fun classifyFDroid(versionName: String?): Status {
    val version = versionName?.let(versionPattern::find) ?: return Status.FDroid
    val major = version.groupValues[1].toInt()
    val minor = version.groupValues[2].toInt()
    return if (major > 0 || minor >= MINOR_VERSION_MIN) Status.FDroid else Status.FDroidTooOld
  }

  private fun classifyPlay(versionName: String?): Status {
    // Zero-padded dates compare correctly as strings.
    val date = versionName?.let(playVersionPattern::find)?.groupValues?.get(1) ?: return Status.PlayTooOld
    return if (date >= PLAY_VERSION_MIN) Status.Play else Status.PlayTooOld
  }
}
