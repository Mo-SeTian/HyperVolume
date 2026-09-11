import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) {
        FileInputStream(releaseSigningFile).use(::load)
    }
}
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigning = releaseSigningKeys.all {
    !releaseSigning.getProperty(it).isNullOrBlank()
}
if (releaseSigningFile.isFile && !hasReleaseSigning) {
    error("keystore.properties exists but release signing values are incomplete")
}

android {
    namespace = "com.codex.multivolume"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codex.multivolume"
        minSdk = 29
        targetSdk = 35
        versionCode = 19
        versionName = "1.0.18"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
