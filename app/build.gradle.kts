plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jlleitschuh.gradle.ktlint")
  id("dev.detekt")
}

val daemonDir = rootProject.layout.projectDirectory.dir("daemon")

/** esbuild bundle of the daemon; AGP picks the output directory and wires the dependency. */
abstract class DaemonBundle : Exec() {
  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  override fun exec() {
    args("--outfile=${outputDir.get().asFile.resolve("pipkin-daemon.mjs")}")
    super.exec()
  }
}

// The Termux supervisor is a TypeScript package; its bundle ships as an APK asset.
val daemonInstall by tasks.registering(Exec::class) {
  description = "Installs the daemon's pinned toolchain."
  workingDir = daemonDir.asFile
  inputs.files(daemonDir.file("package.json"), daemonDir.file("package-lock.json"))
  outputs.file(daemonDir.file("node_modules/.package-lock.json"))
  commandLine("npm", "ci", "--no-audit", "--no-fund", "--loglevel=error")
}

val daemonBuild by tasks.registering(DaemonBundle::class) {
  description = "Bundles the Termux supervisor daemon into an APK asset."
  dependsOn(daemonInstall)
  workingDir = daemonDir.asFile
  inputs.dir(daemonDir.dir("src"))
  inputs.files(daemonDir.file("package.json"), daemonDir.file("package-lock.json"), daemonDir.file("tsconfig.json"))
  commandLine("npm", "run", "build", "--")
}

val daemonCheck by tasks.registering(Exec::class) {
  description = "Typechecks, lints and tests the Termux supervisor daemon."
  dependsOn(daemonInstall)
  workingDir = daemonDir.asFile
  inputs.dir(daemonDir.dir("src"))
  inputs.dir(daemonDir.dir("test"))
  inputs.files(
    daemonDir.file("package.json"),
    daemonDir.file("package-lock.json"),
    daemonDir.file("tsconfig.json"),
    daemonDir.file("eslint.config.js"),
    daemonDir.file("vitest.config.ts"),
  )
  outputs.upToDateWhen { false }
  commandLine("npm", "run", "check")
}

android {
  namespace = "io.jeph.pipkin"
  compileSdk = 37

  defaultConfig {
    applicationId = "io.jeph.pipkin"
    minSdk = 29
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(daemonBuild, DaemonBundle::outputDir)
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(26)
  }
}

detekt {
  buildUponDefaultConfig.set(true)
  config.setFrom(rootProject.file("config/detekt.yml"))
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
  jvmTarget.set("21")
}

tasks.named("check") {
  dependsOn("detektMain", "detektTest", daemonCheck)
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.core:core-ktx:1.19.0")
  implementation("androidx.lifecycle:lifecycle-process:2.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  implementation("com.squareup.okhttp3:okhttp:5.5.0")
  implementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20260814")
}
