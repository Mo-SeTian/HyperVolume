plugins {
    id("com.android.application")
}

android {
    namespace = "com.codex.multivolume"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codex.multivolume"
        minSdk = 29
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
