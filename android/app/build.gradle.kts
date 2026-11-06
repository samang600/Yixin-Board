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
        versionCode = 3
        versionName = "1.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Rapfi runs as an executable (librapfi.so), so native libraries must be extracted on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    // The network weights are already LZ4-compressed; storing them uncompressed speeds up unpacking.
    androidResources {
        noCompress += listOf("lz4", "bin", "toml")
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/rapfiAssets"))

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
            System.getenv("SLOWRENJU_LIB_DIR")?.let {
                test.systemProperty("java.library.path", it)
                // A JNI library binds to one class loader; Robolectric's would block the plain tests.
                test.forkEvery = 1
            }
            System.getenv("ENGINE_MATCH_GAMES")?.let { test.systemProperty("engineMatchGames", it) }
            // Optional: a host build of Rapfi (pbrain-rapfi) for RapfiEngineTest.
            System.getenv("RAPFI_BIN")?.let { test.systemProperty("rapfiBin", it) }
            test.systemProperty("rapfiConfig", file("src/main/rapfi/config.toml").path)
            test.systemProperty("rapfiNetworks", rootProject.file("third_party/rapfi-networks").path)
            test.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Rapfi config and weights (from the third_party/rapfi-networks submodule) -> assets/rapfi.
val copyRapfiAssets by tasks.registering(Copy::class) {
    val networks = rootProject.file("third_party/rapfi-networks")
    from("src/main/rapfi/config.toml")
    from(File(networks, "classical/model210901.bin"))
    from(File(networks, "mix9svq")) { include("*.bin.lz4") }
    into(layout.buildDirectory.dir("generated/rapfiAssets/rapfi"))
    doFirst {
        check(File(networks, "mix9svq").isDirectory) {
            "Rapfi weights missing: run git submodule update --init android/third_party/rapfi-networks"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyRapfiAssets) }
