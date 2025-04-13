plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "page.smirnov.hodl"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "page.smirnov.hodl"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "ESPLORA_API_HTTPS", "true")
        buildConfigField("String", "ESPLORA_API_HOST", "\"mempool.space\"")
        buildConfigField("int", "ESPLORA_API_PORT", "443")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        packaging {
            resources.excludes.add("META-INF/*")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.kermit)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.resources)

    implementation(libs.bitcoinj.core)

    implementation(libs.hilt.android)
//    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)

    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.mockk.agent)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.ktor.client.mock)
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "dagger.hilt.internal.aggregatedroot.codegen.*",
                    "hilt_aggregated_deps.*",
                    "page.smirnov.hodl.domain.debug.IsDebug*",
                    "page.smirnov.hodl.data.model.*",
                    "**.*_Factory*",
                    "page.smirnov.hodl.App",
                    "page.smirnov.hodl.BuildConfig",
                    "page.smirnov.hodl.ComposableSingletons*",
                    "page.smirnov.hodl.Hilt_*",
                    "page.smirnov.hodl.MainActivity",
                    "page.smirnov.hodl.di.module.*",

                    "*\$Companion",
                    "*\$\$inlined\$*",

                    // These are tested by instrumented tests which are not supported by Kover
                    "page.smirnov.hodl.data.repository.encryption.DataEncryptor*",
                    "page.smirnov.hodl.data.repository.encryption.EncryptedPreferences*",
                )

                packages(
                    "page.smirnov.hodl.util.logging",
                    "page.smirnov.hodl.ui.icon",
                    "page.smirnov.hodl.ui.theme",
                )

                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
                annotatedBy("javax.annotation.processing.Generated")

                androidGeneratedClasses()
            }
        }
    }
}