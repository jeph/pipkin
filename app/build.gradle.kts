plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jlleitschuh.gradle.ktlint")
  id("dev.detekt")
}

val hostDir = rootProject.layout.projectDirectory.dir("host")

/** esbuild bundle of the Termux host; AGP picks the output directory and wires the dependency. */
abstract class HostBundle : Exec() {
  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  override fun exec() {
    environment("OUT", outputDir.get().asFile.absolutePath)
    super.exec()
  }
}

// The Termux-side host is a TypeScript package; its bundle ships as an APK asset.
val hostInstall by tasks.registering(Exec::class) {
  description = "Installs the host package's pinned toolchain."
  workingDir = hostDir.asFile
  inputs.files(hostDir.file("package.json"), hostDir.file("pnpm-lock.yaml"), hostDir.file("pnpm-workspace.yaml"))
  outputs.file(hostDir.file("node_modules/.modules.yaml"))
  commandLine("pnpm", "install", "--frozen-lockfile")
}

val hostBuild by tasks.registering(HostBundle::class) {
  description = "Bundles the Termux host into the pipkin-host.mjs APK asset."
  dependsOn(hostInstall)
  workingDir = hostDir.asFile
  inputs.dir(hostDir.dir("src"))
  inputs.files(hostDir.file("package.json"), hostDir.file("pnpm-lock.yaml"), hostDir.file("tsconfig.json"))
  commandLine("pnpm", "run", "build")
}

val hostCheck by tasks.registering(Exec::class) {
  description = "Typechecks, lints and tests the Termux host."
  dependsOn(hostInstall)
  workingDir = hostDir.asFile
  inputs.dir(hostDir.dir("src"))
  inputs.dir(hostDir.dir("test"))
  inputs.files(
    hostDir.file("package.json"),
    hostDir.file("pnpm-lock.yaml"),
    hostDir.file("tsconfig.json"),
    hostDir.file(".oxlintrc.json"),
    hostDir.file("vitest.config.ts"),
  )
  outputs.upToDateWhen { false }
  commandLine("pnpm", "run", "check")
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
    variant.sources.assets?.addGeneratedSourceDirectory(hostBuild, HostBundle::outputDir)
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
  dependsOn("detektMain", "detektTest", hostCheck)
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.core:core-ktx:1.19.1")
  implementation("androidx.lifecycle:lifecycle-process:2.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20260814")
}
