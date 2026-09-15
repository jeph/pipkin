plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jlleitschuh.gradle.ktlint")
  id("dev.detekt")
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
  dependsOn("detektMain", "detektTest")
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
