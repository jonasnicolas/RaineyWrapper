// RaineyWrapper's fork of LibretroDroid 0.14.0 (GPL-3.0, https://github.com/Swordfish90/LibretroDroid).
// Changes are marked "RaineyWrapper:" in the C++ sources. Built into player-template/app/libs/ by
// `./raineywrapper libretrodroid`.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
