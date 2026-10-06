plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.autoreply.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoreply.ai"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(emptyList<File>())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(".")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    setSource(fileTree(rootDir) {
        include("*.kt")
    })
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
