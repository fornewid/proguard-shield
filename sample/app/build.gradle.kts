plugins {
    alias(libs.plugins.android.application)
    id("io.github.fornewid.proguard-shield")
}

android {
    namespace = "io.github.fornewid.proguard.shield.sample.app"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        targetSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":sample:module1"))
    implementation(libs.androidx.activity)
}

proguardShield {
    configuration("release")
}
