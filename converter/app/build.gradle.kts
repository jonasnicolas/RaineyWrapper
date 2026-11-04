plugins {
    id("com.android.application")
}

// Signing and version come from -P properties passed by `./raineywrapper converter`.
fun prop(name: String, default: String) = (findProperty(name) as String?) ?: default

val raineywrapperVersionCode = prop("raineywrapperVersionCode", "1").toInt()
val raineywrapperKeystore = findProperty("raineywrapperKeystore") as String?

android {
    namespace = "com.raineywrapper.converter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.raineywrapper.converter"
        minSdk = 26
        targetSdk = 37
        versionCode = raineywrapperVersionCode
        versionName = "1.4"
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
        checkReleaseBuilds = false
    }

    androidResources {
        // The player template is read with ZipFile after being copied out; no need to compress it twice.
        noCompress += "apk"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
                "META-INF/BCKEY.*",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(project(":patcher"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Creating and reading the signing key (MIT)
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
