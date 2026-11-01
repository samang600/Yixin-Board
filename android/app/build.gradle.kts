plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.yixinboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yixinboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the release APK installs out of the box.
            // Replace with your own signing config before publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Optional: a Maven mirror for Robolectric's runtime jars, and where UI screenshots go.
            System.getenv("ROBOLECTRIC_REPO_URL")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            test.systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.path)
            // Optional: a host build of libslowrenju for the native engine tests, and match length.
            System.getenv("SLOWRENJU_LIB_DIR")?.let { test.systemProperty("java.library.path", it) }
            System.getenv("ENGINE_MATCH_GAMES")?.let { test.systemProperty("engineMatchGames", it) }
            test.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
