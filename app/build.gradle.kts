import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.navigation.safeargs)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.protobuf)
}

/*
'keystore.properties' should look like the following:

storeFile=my.keystore
storePassword=my_store_password
keyAlias=my_key_alias
keyPassword=my_key_password
 */

val keystoreProperties = Properties()
// YT: without keystore.properties, CI signs with the personal key from the repository secrets
// (YT_KEYSTORE_PATH and YT_KEYSTORE_PASSWORD), so every build updates the installed app.
val ytKeystorePath: String? = System.getenv("YT_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val keystoreFileExists = when {
    rootProject.file("keystore.properties").exists() -> {
        keystoreProperties.load(rootProject.file("keystore.properties").inputStream())
        true
    }
    ytKeystorePath != null -> {
        val password = System.getenv("YT_KEYSTORE_PASSWORD").orEmpty()
        keystoreProperties["storeFile"] = ytKeystorePath
        keystoreProperties["storePassword"] = password
        keystoreProperties["keyAlias"] = System.getenv("YT_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "yt"
        keystoreProperties["keyPassword"] = System.getenv("YT_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: password
        true
    }
    else -> false
}

// YT: CI passes its run number so that every build has a higher version code than the last.
val ytBuildNumber = (System.getenv("YT_BUILD_NUMBER") ?: "0").toInt()

android {
    compileSdk = 36

    defaultConfig {
        // YT: own package name, so YT installs next to YouTube and LibreTube.
        applicationId = "app.tra1c0.yt"
        minSdk = 26
        targetSdk = 36
        // Upstream LibreTube version code 72, times 100000, plus the CI run number.
        versionCode = 72 * 100000 + ytBuildNumber
        versionName = "32.1-yt.$ytBuildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "YT")
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("exportSchema", "true")
    }

    viewBinding {
        enable = true
    }

    signingConfigs {
        if (keystoreFileExists) {
            create("release") {
                storeFile = keystoreProperties["storeFile"]?.let { file(it as String) }
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // YT: without a personal key, fall back to the debug key so the APK still installs.
            // Builds signed that way cannot update a build signed with the personal key.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "YT Debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            javaParameters = true
        }
    }

    packaging {
        jniLibs.excludes.add("lib/armeabi-v7a/*_neon.so")
    }

    tasks.register("testClasses")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true // YT: design screens
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    // language preference for Android 13 and above
    androidResources {
        generateLocaleConfig = true
    }

    namespace = "com.github.libretube"
}

dependencies {
    /* YT: Jetpack Compose for the design screens */
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime.livedata)
    implementation(libs.coil.compose)

    /* Android Core */
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.media)
    implementation(libs.androidx.swiperefreshlayout)

    /* Android Lifecycle */
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.service)

    /* Design */
    implementation(libs.material)

    /* ExoPlayer */
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)

    /* Retrofit and Kotlinx Serialization */
    implementation(libs.square.retrofit)
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.datetime)
    implementation(libs.converter.kotlinx.serialization)
    implementation(libs.google.protobuf.javalite)
    implementation(libs.google.protobuf.kotlin.lite)

    /* NewPipe Extractor */
    implementation(libs.newpipeextractor)

    /* YT: official API SDK for the opt-in news summaries (off until the user enters a key) */
    implementation(libs.anthropic.java)


    /* Coil */
    coreLibraryDesugaring(libs.desugaring)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)

    /* Room */
    ksp(libs.room.compiler)
    implementation(libs.room)

    /* Baseline profile generation */
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    /* AndroidX Paging */
    implementation(libs.androidx.paging)

    /* Testing */
    testImplementation(libs.junit)
}

//TODO: exclude from release protobuf
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                //TODO: only generate kotlin code
                id("java") {
                    option("lite")
                }
//                id("kotlin") {
//                    option("lite")
//                }
            }
        }
    }
}
