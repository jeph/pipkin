import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

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
    val out = outputDir.get().asFile
    // A bundle that was renamed or removed would otherwise stay behind and ship in the APK.
    out.deleteRecursively()
    environment("OUT", out.absolutePath)
    super.exec()
  }
}

// The Termux-side host is a TypeScript package; its bundle ships as an APK asset.
val hostInstall = tasks.register<Exec>("hostInstall") {
  description = "Installs the host package's pinned toolchain."
  workingDir = hostDir.asFile
  inputs.files(hostDir.file("package.json"), hostDir.file("pnpm-lock.yaml"), hostDir.file("pnpm-workspace.yaml"))
  outputs.file(hostDir.file("node_modules/.modules.yaml"))
  commandLine("pnpm", "install", "--frozen-lockfile")
}

val hostBuild = tasks.register<HostBundle>("hostBuild") {
  description = "Bundles the Termux host into the pipkin-host.mjs APK asset, and the Pi extensions Pipkin ships."
  dependsOn(hostInstall)
  workingDir = hostDir.asFile
  inputs.dir(hostDir.dir("src"))
  inputs.files(
    hostDir.file("package.json"),
    hostDir.file("pnpm-lock.yaml"),
    hostDir.file("tsconfig.json"),
    hostDir.file("build.ts"),
    // build.ts leaves the runtime's packages out of the extensions it bundles.
    layout.projectDirectory.file("src/main/assets/pi/package.json"),
  )
  commandLine("pnpm", "run", "build")
}

val hostCheck = tasks.register<Exec>("hostCheck") {
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
    hostDir.file("build.ts"),
  )
  outputs.upToDateWhen { false }
  commandLine("pnpm", "run", "check")
}

/**
 * Downloads each of [urls] into [sourcesDir]/<name>, checked against [sha256s]; GitHub's tag and commit
 * archives wrap their files in one directory, which is dropped. Each grammar's highlight query, its
 * [queries] files joined in order, and every source's licence go to [assetsDir] as APK assets, with the
 * [notices] of third-party code inside a source that its own licence doesn't cover.
 */
abstract class SyntaxSources : DefaultTask() {
  @get:Input
  abstract val urls: MapProperty<String, String>

  @get:Input
  abstract val sha256s: MapProperty<String, String>

  @get:Input
  abstract val queries: MapProperty<String, List<String>>

  /** A licence asset name to the file under [sourcesDir] holding it; a C header gives its leading comment. */
  @get:Input
  abstract val notices: MapProperty<String, String>

  @get:OutputDirectory
  abstract val sourcesDir: DirectoryProperty

  @get:OutputDirectory
  abstract val assetsDir: DirectoryProperty

  @get:Inject
  abstract val archives: ArchiveOperations

  @get:Inject
  abstract val files: FileSystemOperations

  @TaskAction
  fun fetch() {
    files.delete { delete(sourcesDir, assetsDir) }
    val licenses = assetsDir.dir("syntax/licenses").get().asFile.apply { mkdirs() }
    for ((name, url) in urls.get()) {
      val archive = temporaryDir.resolve("$name.tar.gz")
      URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
      val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
      val sha256 = digest.joinToString("") { "%02x".format(it) }
      val pinned = sha256s.get().getValue(name)
      check(sha256 == pinned) { "$url has SHA-256 $sha256, not the pinned $pinned" }
      files.copy {
        from(archives.tarTree(archives.gzip(archive)))
        into(sourcesDir.dir(name))
        includeEmptyDirs = false
        // Some release tarballs list a file twice; as with tar itself, the later entry wins.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        if ("/archive/" in url) {
          eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        }
      }
      archive.delete()
      // Lua's licence is LICENSE.md.
      val licenseFiles = listOf("LICENSE", "LICENSE.md").map { sourcesDir.file("$name/$it").get().asFile }
      licenseFiles.first { it.exists() }.copyTo(licenses.resolve("$name.txt"))
    }
    val sources = sourcesDir.get().asFile
    for ((name, path) in notices.get()) {
      val text = sources.resolve(path).readText()
      val notice = if (path.endsWith(".h")) text.substringBefore("*/") + "*/\n" else text
      licenses.resolve("$name.txt").writeText(notice)
    }
    for ((grammar, paths) in queries.get()) {
      val query = paths.joinToString("\n") { sources.resolve(it).readText() }
      assetsDir.file("syntax/$grammar.scm").get().asFile.writeText(query)
    }
  }
}

/** A pinned source archive of tree-sitter or one of its grammars. */
class SyntaxSource(val name: String, val url: String, val sha256: String)

// Each project's latest release (Dart has none), plus the older JavaScript and C releases whose
// highlight queries the TypeScript and C++ queries extend.
val syntaxSourcePins = listOf(
  SyntaxSource(
    "tree-sitter",
    "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v0.27.1.tar.gz",
    "982cd3d4d9eb7be18c243240a622423fd2e4ecb4bec2cd98832c2f3fa1f0f333",
  ),
  SyntaxSource(
    "bash",
    "https://github.com/tree-sitter/tree-sitter-bash/releases/download/v0.25.1/tree-sitter-bash.tar.gz",
    "bd0d72814c6512de190439ce6562635c69ce27fa8bc59eaf381d44263579df36",
  ),
  SyntaxSource(
    "c",
    "https://github.com/tree-sitter/tree-sitter-c/releases/download/v0.24.2/tree-sitter-c.tar.gz",
    "f3a2cdfbca39c79f60baf2ef62b42084c609782c76485de457ba36ff65d51baf",
  ),
  SyntaxSource(
    "c-0.23",
    "https://github.com/tree-sitter/tree-sitter-c/releases/download/v0.23.6/tree-sitter-c.tar.gz",
    "590a05b500236d6695ad47741364cae1fce5a4fa89388361aab78b0d5b46c267",
  ),
  SyntaxSource(
    "c-sharp",
    "https://github.com/tree-sitter/tree-sitter-c-sharp/releases/download/v0.23.5/tree-sitter-c-sharp.tar.gz",
    "d7e9db699ad8ebd234cb6922d285efe128a408db3737d4fa0bab6c668e0fc5e6",
  ),
  SyntaxSource(
    "cpp",
    "https://github.com/tree-sitter/tree-sitter-cpp/archive/refs/tags/v0.23.4.tar.gz",
    "7a2c55afe3028f4105f25762ea58cc16537d1f5a1dcd9cca90410b3cd5d46051",
  ),
  SyntaxSource(
    "css",
    "https://github.com/tree-sitter/tree-sitter-css/releases/download/v0.25.0/tree-sitter-css.tar.gz",
    "6e34c5a7f7a829a2f2077b782653bf3233db91b71f78f297797ec4ad20a0a494",
  ),
  SyntaxSource(
    "dart",
    "https://github.com/UserNobody14/tree-sitter-dart/archive/be07cf7118d3dba06236a3f19541685a68209934.tar.gz",
    "b126efb8dfeeba8b71f0c6ae1f0cd09cf8b205560786b31d44cc16b283e782f2",
  ),
  SyntaxSource(
    "dockerfile",
    "https://github.com/camdencheek/tree-sitter-dockerfile/archive/refs/tags/v0.2.0.tar.gz",
    "8cbdf50838cc55e841aa9585a1a09e5b8c41454ae30ce0a93a7bd71adb140818",
  ),
  SyntaxSource(
    "go",
    "https://github.com/tree-sitter/tree-sitter-go/releases/download/v0.25.0/tree-sitter-go.tar.gz",
    "ac412018d59f7cd5bb72fbde557e9ebf9fdfac12c5853f2bb03669f980a953bb",
  ),
  SyntaxSource(
    "ini",
    "https://github.com/justinmk/tree-sitter-ini/archive/refs/tags/v1.4.0.tar.gz",
    "300adc8b5db7604dff2b156a3962c1fbc955d89dc8be6da90e6dfe2adc950d9b",
  ),
  SyntaxSource(
    "java",
    "https://github.com/tree-sitter/tree-sitter-java/archive/refs/tags/v0.23.5.tar.gz",
    "cb199e0faae4b2c08425f88cbb51c1a9319612e7b96315a174a624db9bf3d9f0",
  ),
  SyntaxSource(
    "javascript",
    "https://github.com/tree-sitter/tree-sitter-javascript/releases/download/v0.25.0/tree-sitter-javascript.tar.gz",
    "3385229430f686c6ff6e756168d7e05a01da4c45acb7708239edc9fa9ee41514",
  ),
  SyntaxSource(
    "javascript-0.23",
    "https://github.com/tree-sitter/tree-sitter-javascript/archive/refs/tags/v0.23.1.tar.gz",
    "fc5b8f5a491a6db33ca4854b044b89363ff7615f4291977467f52c1b92a0c032",
  ),
  SyntaxSource(
    "json",
    "https://github.com/tree-sitter/tree-sitter-json/archive/refs/tags/v0.24.8.tar.gz",
    "acf6e8362457e819ed8b613f2ad9a0e1b621a77556c296f3abea58f7880a9213",
  ),
  SyntaxSource(
    "kotlin",
    "https://github.com/fwcd/tree-sitter-kotlin/archive/refs/tags/0.3.8.tar.gz",
    "7dd60975786bf9cb4be6a5176f5ccb5fed505f9929a012da30762505b1015669",
  ),
  SyntaxSource(
    "lua",
    "https://github.com/tree-sitter-grammars/tree-sitter-lua/releases/download/v0.5.0/tree-sitter-lua.tar.gz",
    "dd6d995634ea7dc2eac687f4890f995f03c421e0c42c34b3cfcfc81c47e48d7e",
  ),
  SyntaxSource(
    "make",
    "https://github.com/tree-sitter-grammars/tree-sitter-make/archive/refs/tags/v1.1.1.tar.gz",
    "31affca06041623cfa5f6a728cb3f0f2a8eb7fd01607e1a2681ea1f318367211",
  ),
  SyntaxSource(
    "perl",
    "https://github.com/tree-sitter-perl/tree-sitter-perl/releases/download/v2.0.0/tree-sitter-perl.tar.gz",
    "f73ba0711b4ac612ff3565ad4de92ee3afac0043a5176898b73a0998bb6aaf5e",
  ),
  SyntaxSource(
    "php",
    "https://github.com/tree-sitter/tree-sitter-php/releases/download/v0.25.1/tree-sitter-php.tar.gz",
    "27857429849698a958824e6e5899cb2871f978f57e0523c7015ec454d5fbd5a7",
  ),
  SyntaxSource(
    "python",
    "https://github.com/tree-sitter/tree-sitter-python/releases/download/v0.25.0/tree-sitter-python.tar.gz",
    "7bce887eb2f33e94bf74a69645cf5138d4096720e54fd3269a6124c06b93c584",
  ),
  SyntaxSource(
    "ruby",
    "https://github.com/tree-sitter/tree-sitter-ruby/archive/refs/tags/v0.23.1.tar.gz",
    "e7e49577ddc1f2de8e42d42353b477e338c15bbb95b2558e123ddc13d88789f0",
  ),
  SyntaxSource(
    "rust",
    "https://github.com/tree-sitter/tree-sitter-rust/releases/download/v0.24.2/tree-sitter-rust.tar.gz",
    "953d7a7122cc004d3ad516ae193dde408e633c2da59356ba67e713f3e52d73fc",
  ),
  SyntaxSource(
    "swift",
    "https://github.com/alex-pinkus/tree-sitter-swift/releases/download/0.7.3/tree-sitter-swift.tar.gz",
    "c595b41459b0816f246ec27f60e08392ff453135e12ef47a1852ed37fe6705fe",
  ),
  SyntaxSource(
    "toml",
    "https://github.com/tree-sitter-grammars/tree-sitter-toml/archive/refs/tags/v0.7.0.tar.gz",
    "7d52a7d4884f307aabc872867c69084d94456d8afcdc63b0a73031a8b29036dc",
  ),
  SyntaxSource(
    "typescript",
    "https://github.com/tree-sitter/tree-sitter-typescript/archive/refs/tags/v0.23.2.tar.gz",
    "2c4ce711ae8d1218a3b2f899189298159d672870b5b34dff5d937bed2f3e8983",
  ),
  SyntaxSource(
    "xml",
    "https://github.com/tree-sitter-grammars/tree-sitter-xml/archive/refs/tags/v0.7.0.tar.gz",
    "4330a6b3685c2f66d108e1df0448eb40c468518c3a66f2c1607a924c262a3eb9",
  ),
  SyntaxSource(
    "yaml",
    "https://github.com/tree-sitter-grammars/tree-sitter-yaml/releases/download/v0.7.2/tree-sitter-yaml.tar.gz",
    "2707d9ff0556ee6c36a77299b4c08e5b2dcbd0d35f0b9688bb2ac8e276025f72",
  ),
)

val syntaxSourcesDir = layout.buildDirectory.dir("syntax/sources")

val syntaxSources = tasks.register<SyntaxSources>("syntaxSources") {
  description = "Fetches tree-sitter and its grammars for the native build, and their queries as assets."
  urls.set(syntaxSourcePins.associate { it.name to it.url })
  sha256s.set(syntaxSourcePins.associate { it.name to it.sha256 })
  // Each grammar's highlights in its tree-sitter.json order, since the later pattern wins a node. TSX
  // puts the JSX queries after the JavaScript ones, as the JavaScript grammar itself does, so that their
  // `(identifier) @variable` doesn't override JSX tags and attributes.
  queries.set(
    mapOf(
      "bash" to listOf("bash/queries/highlights.scm"),
      "c" to listOf("c/queries/highlights.scm"),
      "c_sharp" to listOf("c-sharp/queries/highlights.scm"),
      "cpp" to listOf("c-0.23/queries/highlights.scm", "cpp/queries/highlights.scm"),
      "css" to listOf("css/queries/highlights.scm"),
      "dart" to listOf("dart/queries/highlights.scm"),
      "dockerfile" to listOf("dockerfile/queries/highlights.scm"),
      "go" to listOf("go/queries/highlights.scm"),
      "ini" to listOf("ini/queries/highlights.scm"),
      "java" to listOf("java/queries/highlights.scm"),
      "javascript" to
        listOf(
          "javascript/queries/highlights.scm",
          "javascript/queries/highlights-jsx.scm",
          "javascript/queries/highlights-params.scm",
        ),
      "json" to listOf("json/queries/highlights.scm"),
      "kotlin" to listOf("kotlin/queries/highlights.scm"),
      "lua" to listOf("lua/queries/highlights.scm"),
      "make" to listOf("make/queries/highlights.scm"),
      "perl" to listOf("perl/queries/highlights.scm"),
      "php" to listOf("php/queries/highlights.scm"),
      "python" to listOf("python/queries/highlights.scm"),
      "ruby" to listOf("ruby/queries/highlights.scm"),
      "rust" to listOf("rust/queries/highlights.scm"),
      "swift" to listOf("swift/queries/highlights.scm"),
      "toml" to listOf("toml/queries/highlights.scm"),
      "tsx" to
        listOf(
          "typescript/queries/highlights.scm",
          "javascript-0.23/queries/highlights.scm",
          "javascript-0.23/queries/highlights-jsx.scm",
        ),
      "typescript" to listOf("typescript/queries/highlights.scm", "javascript-0.23/queries/highlights.scm"),
      "xml" to listOf("xml/queries/xml/highlights.scm"),
      "yaml" to listOf("yaml/queries/highlights.scm"),
    ),
  )
  // tree-sitter compiles ICU's UTF-8 and UTF-16 macros in, and the Perl scanner a BSD-licensed bsearch from libiberty.
  notices.set(mapOf("tree-sitter-icu" to "tree-sitter/lib/src/unicode/LICENSE", "perl-bsearch" to "perl/src/bsearch.h"))
  sourcesDir.set(syntaxSourcesDir)
}

// The native build reads the fetched sources; AGP names its CMake tasks per variant and ABI.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
  dependsOn(syntaxSources)
}

android {
  namespace = "io.jeph.pipkin"
  compileSdk = 37

  defaultConfig {
    applicationId = "io.jeph.pipkin"
    minSdk = 29
    targetSdk = 37
    // Release builds get both from .github/workflows/release.yml; local builds keep these placeholders.
    versionCode = providers.gradleProperty("pipkin.versionCode").map(String::toInt).getOrElse(1)
    versionName = providers.gradleProperty("pipkin.versionName").getOrElse("0.0.0-dev")
    // 64-bit phones and the emulator; every ABI adds the grammars' whole native library.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    externalNativeBuild {
      cmake { arguments += "-DTREE_SITTER_SOURCES=${syntaxSourcesDir.get().asFile}" }
    }
  }

  ndkVersion = "30.0.16248370"

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }

  // The grammars' parse tables compress about tenfold, so the APK stores them compressed.
  packaging { jniLibs { useLegacyPackaging = true } }

  buildFeatures {
    aidl = true
    compose = true
  }

  // Android 13 and later list Pipkin under Settings › Apps › Language with every locale it has strings for, read from
  // the values-* folders; src/main/res/resources.properties names the locale of the unqualified values folder.
  androidResources { generateLocaleConfig = true }

  // Debug builds carry the en-XA and ar-XB pseudolocales: switched to one, any text that skips the string resources
  // stays plain English, and layouts show how they take longer or right-to-left text.
  //
  // Debug builds are Pipkin Dev, installable beside the release: their own package here, and in src/debug their own
  // name and folders in Termux and helper ports (TermuxBuild, with the release's in src/release), so both can share one
  // Termux without touching each other's state.
  buildTypes {
    debug {
      isPseudoLocalesEnabled = true
      applicationIdSuffix = ".debug"
    }
    // Google Play requires at least 25% shrinking, optimization and obfuscation of apps with over 10 MB of DEX code.
    // Only the release is minified, so test that build on an emulator before cutting a release (AGENTS.md).
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }

  // Production classes log through android.util.Log, whose JVM stub otherwise throws in unit tests.
  testOptions { unitTests.isReturnDefaultValues = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(hostBuild, HostBundle::outputDir)
    variant.sources.assets?.addGeneratedSourceDirectory(syntaxSources, SyntaxSources::assetsDir)
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
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.46.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  // The snapshot of main at c3480d9, which aligns IME input with Termux; 0.1.0-alpha01 predates it.
  implementation("io.github.sagernet:libghostty-android:0.1.0-20260909.143535-10")
  implementation("io.github.sagernet:libghostty-android-compose:0.1.0-20260909.143535-10")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20260814")
}
