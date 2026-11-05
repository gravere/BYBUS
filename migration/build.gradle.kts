import java.util.Properties

plugins { alias(libs.plugins.android.application) }
val signingFile = rootProject.file("signing/keystore.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
val appSource = rootProject.file("app/build.gradle.kts").readText()
val brand = tasks.register<Copy>("prepareBrand") {
    from(rootProject.file("app/src/main/res/drawable-nodpi/app_icon.png"))
    into(layout.buildDirectory.dir("generated/brand/drawable"))
}
android {
    namespace = "io.github.gravere.bybus.migration"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.limbuszhcn"
        minSdk = 26
        targetSdk = 35
        versionCode = Regex("versionCode = (\\d+)").find(appSource)!!.groupValues[1].toInt()
        versionName = Regex("versionName = \"([^\"]+)\"").find(appSource)!!.groupValues[1]
    }
    signingConfigs {
        if (signingFile.isFile) create("local") {
            storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("local")?.let { signingConfig = it } }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("local")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/brand"))
}
tasks.named("preBuild").configure { dependsOn(brand) }
dependencies { implementation(project(":migration-core")) }
