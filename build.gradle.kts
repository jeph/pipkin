buildscript {
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
  }
}

plugins {
  base
  id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
  id("dev.detekt") version "2.0.0-alpha.6" apply false
  id("com.android.application") version "9.4.0" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

allprojects {
  pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
      version.set("1.8.0")
    }
  }
}

tasks.named("check") {
  dependsOn(":app:check", ":app:lintRelease")
}
