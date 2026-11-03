plugins {
    `java-library`
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Binary AndroidManifest editing (Apache-2.0)
    api("io.github.reandroid:ARSCLib:1.4.0")
    // APK signing, Google's library used by apksigner (Apache-2.0)
    api("com.android.tools.build:apksig:9.5.0")
}

// Desktop harness for testing the patcher without the Android app.
application {
    mainClass.set("com.raineywrapper.patcher.DesktopTest")
}
