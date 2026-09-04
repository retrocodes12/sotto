plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Published builds have always been signed with this laptop's Android debug key, and Android
 * only installs an update over a build carrying the identical signature. Changing the key would
 * strand every phone already running Sotto, so the key stays exactly as it is; what changes is
 * that shipping builds are now release builds, which are not debuggable.
 */
val shippingKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

android {
    namespace = "com.sotto"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.sotto"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.25"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (shippingKeystore.exists()) {
            create("shipping") {
                storeFile = shippingKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // findByName returns null when the keystore is not on this machine, and a null
            // signingConfig does not fail the build -- it produces an unsigned APK that no
            // phone will install. Better to stop here and say why.
            val shipping = signingConfigs.findByName("shipping")
                ?: throw GradleException(
                    "No signing key at $shippingKeystore. Release builds are signed with the " +
                        "Android debug key that published builds have always used; without it " +
                        "an update would not install over the top. Use assembleDebug instead.",
                )
            // Left off deliberately. R8 would rename com.sotto.Modem, whose native methods are
            // bound by name from jni_bridge.cpp, and the ViewModel that the lifecycle library
            // constructs reflectively. Both fail at run time, not at build time, so this waits
            // for a build that has actually been run on a phone.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = shipping
        }
        debug {
            isDebuggable = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        textReport = true
        htmlReport = true
        xmlReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation("junit:junit:4.13.2")
}
