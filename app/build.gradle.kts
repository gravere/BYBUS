import java.util.Properties
import java.security.MessageDigest

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.tukaani:xz:1.10") }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localSigningPropertiesFile = rootProject.file("signing/keystore.properties")
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use { load(it) }
    }
}

// Preserve the full font; only its APK storage encoding changes. Generated once per source change.
val compactAssets = layout.buildDirectory.dir("generated/compactAssets")
val prepareCompactAssets = tasks.register("prepareCompactAssets") {
    val source = layout.projectDirectory.file("src/main/assets/runtime/ChineseFont.ttf")
    inputs.file(source)
    outputs.dir(compactAssets)
    doLast {
        val output = compactAssets.get().file("runtime/ChineseFont.ttf.xz").asFile
        output.parentFile.mkdirs()
        source.asFile.inputStream().buffered().use { input ->
            org.tukaani.xz.XZOutputStream(output.outputStream().buffered(), org.tukaani.xz.LZMA2Options(6)).use {
                input.copyTo(it, 64 * 1024)
            }
        }
    }
}

android {
    namespace = "com.example.limbuszhcn"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.example.limbuszhcn"
        // 与当前 Limbus Company 本体保持一致，覆盖 Android 8.0 及以上仍在使用的常见机型。
        // VirtualApp 库自身最低支持 API 23，但应用使用了 java.time，因此安全下限设为 API 26。
        minSdk = 26
        targetSdk = 35
        // 显示版本使用 BYBUS 命名；内部版本号保持递增，确保旧版可直接覆盖升级。
        versionCode = 41
        versionName = "v1.0"
        // 版本页随资源更新，避免 Kotlin 增量编译保留旧的 BuildConfig 内联常量。
        resValue("string", "app_version_name", requireNotNull(versionName))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (localSigningPropertiesFile.isFile) {
            create("localDebug") {
                storeFile = localSigningPropertiesFile.parentFile.resolve(
                    localSigningProperties.getProperty("storeFile")
                )
                storePassword = localSigningProperties.getProperty("storePassword")
                keyAlias = localSigningProperties.getProperty("keyAlias")
                keyPassword = localSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // AppSealing 会根据 DEBUGGABLE/JDWP 状态切换保护路径；调试变体保留日志和
            // ADB 入口即可，不能让平台把容器进程当作可调试应用，否则真机行为会偏离生产环境。
            isDebuggable = false
            signingConfigs.findByName("localDebug")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("localDebug")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(compactAssets)
    androidResources {
        ignoreAssetsPatterns.add("ChineseFont.ttf")
        noCompress.add("xz")
    }
}

val verifyBundledGoogleAssets = tasks.register("verifyBundledGoogleAssets") {
    val assets = listOf("com.google.android.gms-252432034.apk.part00",
        "com.google.android.gms-252432034.apk.part01", "com.android.vending-84022634.apk")
        .map { layout.projectDirectory.file("src/main/assets/microg/$it").asFile }
    inputs.files(assets).optional()
    val marker = layout.buildDirectory.file("verified-microg.txt")
    outputs.file(marker)
    doLast {
        fun digest(files: List<java.io.File>): String {
            check(files.all { it.isFile }) { "Run python scripts/fetch-build-assets.py before building." }
            val sha = MessageDigest.getInstance("SHA-256")
            files.forEach { file -> file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
            } }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        check(digest(assets.take(2)) == "e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35") { "microG Services changed; run fetch-build-assets.py" }
        check(digest(assets.takeLast(1)) == "9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012") { "microG Companion changed; run fetch-build-assets.py" }
        marker.get().asFile.writeText("Verified pinned microG assets\n")
    }
}
tasks.named("preBuild").configure { dependsOn(prepareCompactAssets, verifyBundledGoogleAssets) }

dependencies {

    implementation(project(":virtualapp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation("com.android.tools.build:apksig:8.8.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("com.google.android.gms:play-services-tasks:18.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
