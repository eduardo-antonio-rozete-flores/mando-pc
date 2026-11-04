plugins {
    id("com.android.application") version "8.3.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.mandopc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mandopc.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].kotlin.srcDirs(
        "src/main/kotlin",
        "../contracts/src/main/kotlin",
        "../ui-core/src/main/kotlin",
        "../session/src/main/kotlin",
        "../transport/src/main/kotlin",
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
