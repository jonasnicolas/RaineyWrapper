plugins {
    id("com.android.application")
}

// Per-game values are injected by the builder via -P properties (the app label
// comes from res/values/strings.xml, which the builder rewrites).
// The defaults let the template build and run on its own for development.
fun prop(name: String, default: String) = (findProperty(name) as String?) ?: default

val raineywrapperAppId = prop("raineywrapperAppId", "com.raineywrapper.dev")
val raineywrapperVersionCode = prop("raineywrapperVersionCode", "1").toInt()
val raineywrapperVersionName = prop("raineywrapperVersionName", "1.0")
val raineywrapperKeystore = findProperty("raineywrapperKeystore") as String?

android {
    namespace = "com.raineywrapper.player"
    compileSdk = 37

    defaultConfig {
        applicationId = raineywrapperAppId
        minSdk = 26
        targetSdk = 37
        versionCode = raineywrapperVersionCode
        versionName = raineywrapperVersionName
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (raineywrapperKeystore != null) {
            create("raineywrapper") {
                storeFile = file(raineywrapperKeystore)
                storePassword = prop("raineywrapperStorePassword", "")
                keyAlias = prop("raineywrapperKeyAlias", "raineywrapper")
                keyPassword = prop("raineywrapperKeyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (raineywrapperKeystore != null) signingConfig = signingConfigs.getByName("raineywrapper")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Release lint adds minutes per game and the template code doesn't change between games.
        checkReleaseBuilds = false
    }

    packaging {
        // libretro cores are loaded with dlopen by file name, so keep them extracted.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // RaineyWrapper's fork of LibretroDroid (../../libretrodroid), built by `./raineywrapper libretrodroid`.
    implementation(files("libs/libretrodroid.aar"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
