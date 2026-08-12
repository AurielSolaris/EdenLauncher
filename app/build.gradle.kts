import java.util.Properties

// AGP 9 ships built-in Kotlin support, so no separate kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// Temporary test-signing key, so a release build is installable for sideloading.
// Credentials live in keystore.properties (gitignored). Without it, release stays unsigned
// rather than failing the build, so a fresh clone still compiles.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val hasSigningKey = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "app.auriel.edenlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.auriel.edenlauncher"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"

        // Room schema export: keeps migration history reviewable in git.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("release")
            // Low-end targets: shrink code and resources aggressively.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/*.version",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Treat warnings seriously; the launcher hot paths must stay clean.
        allWarningsAsErrors.set(false)
        freeCompilerArgs.addAll(
            "-Xjvm-default=all",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // The vertical app drawer needs view recycling; a full grid of icons is the one place in the
    // launcher where recycling actually pays for the dependency.
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
