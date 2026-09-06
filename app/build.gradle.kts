plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.hag.al_quran"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    defaultConfig {
        applicationId = "com.hag.al_quran"
        minSdk = 24
        targetSdk = 36
        versionCode = 72
        versionName = "1.0.72"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("your-keystore-file.jks")
            storePassword = "YOUR_STORE_PASSWORD"
            keyAlias = "YOUR_KEY_ALIAS"
            keyPassword = "YOUR_KEY_PASSWORD"
        }
    }

    buildTypes {
        release {
            // تفعيل R8
            isMinifyEnabled = true

            // إزالة الموارد غير المستخدمة
            isShrinkResources = true

            // قواعد R8 / ProGuard
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // توقيع نسخة Release
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // لا تقسيم للغات في App Bundle
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {

    // Lottie
    implementation("com.airbnb.android:lottie:6.3.0")

    // Google Play In-App Updates
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AndroidX
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Material
    implementation("com.google.android.material:material:1.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.13.1")

    // PhotoView
    implementation("io.github.chrisbanes:photoview:2.3.0")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation(libs.androidx.lifecycle.process)
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // Glide + OkHttp
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0") {
        exclude(group = "glide-parent")
    }

    // QR
    implementation("com.github.kenglxn.QRGen:android:2.6.0")

    // ExoPlayer / Media3
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Firebase BOM
    implementation(platform(libs.firebase.bom))

    // Firebase Remote Config
    implementation(libs.firebase.config.ktx)

    // Firebase Analytics
    implementation(libs.firebase.analytics.ktx) {

        // منع إعلانات Google Ads
        exclude(
            group = "com.google.android.gms",
            module = "play-services-ads"
        )

        // منع Android Privacy Sandbox Ads
        exclude(
            group = "androidx.privacysandbox.ads",
            module = "ads-adservices"
        )

        exclude(
            group = "androidx.ads",
            module = "ads-adservices"
        )

        exclude(
            group = "com.google.android.adservices",
            module = "adservices"
        )
    }

    // Palette
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Testing
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Compose Debug
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

kapt {
    correctErrorTypes = true
}

// استبعادات عامة
configurations.all {

    // Google Ads
    exclude(
        group = "com.google.android.gms",
        module = "play-services-ads"
    )

    // Android Privacy Sandbox Ads
    exclude(
        group = "androidx.privacysandbox.ads",
        module = "ads-adservices"
    )

    exclude(
        group = "androidx.ads",
        module = "ads-adservices"
    )

    exclude(
        group = "com.google.android.adservices",
        module = "adservices"
    )
}
