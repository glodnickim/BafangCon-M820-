plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.test.bafangcon"
    compileSdk = 35

    defaultConfig {
        applicationId = rootProject.extra["defaultApplicationId"] as String
        minSdk = 24
        targetSdk = 35

        val propsFile = rootProject.file("version.properties")
        val vCode: Int
        val vName: String
        if (propsFile.exists()) {
            val lines = propsFile.readLines()
            vCode = lines.find { it.startsWith("versionCode=") }
                ?.substringAfter("=")?.trim()?.toIntOrNull() ?: 1
            vName = lines.find { it.startsWith("versionName=") }
                ?.substringAfter("=")?.trim() ?: "0.001"
        } else {
            vCode = 1; vName = "0.001"
        }
        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps: Map<String, String>? = if (keystorePropsFile.exists()) {
        try {
            keystorePropsFile.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
            }.toMap()
        } catch (_: Exception) { null }
    } else null

    signingConfigs {
        create("release") {
            if (keystoreProps != null) {
                storeFile = rootProject.file(keystoreProps["storeFile"] ?: "bafangcon.keystore")
                storePassword = keystoreProps["storePassword"] ?: "bafangcon"
                keyAlias = keystoreProps["keyAlias"] ?: "bafangcon"
                keyPassword = keystoreProps["keyPassword"] ?: "bafangcon"
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
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
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // UI Components (Material Design, ConstraintLayout, RecyclerView)
    implementation(libs.material) // Use latest stable
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // Activity & Fragment KTX (for viewModels, ActivityResultLauncher etc.)
    implementation(libs.androidx.activity.ktx) // Use latest stable
    implementation(libs.androidx.fragment.ktx) // Use latest stable

    // Lifecycle KTX (ViewModel, LifecycleScope, StateFlow/LiveData observation)
    implementation(libs.androidx.lifecycle.viewmodel.ktx) // Use latest stable
    implementation(libs.androidx.lifecycle.runtime.ktx) // For repeatOnLifecycle
    implementation(libs.androidx.lifecycle.livedata.ktx) // Optional, if using LiveData alongside Flow

    // Coroutines
    implementation(libs.kotlinx.coroutines.core) // Use latest stable aligned with Android version
    implementation(libs.kotlinx.coroutines.android)

    // Gson - JSON serialization for presets
    implementation(libs.gson)

    // MPAndroidChart - assist curves
    implementation(libs.mpandroidchart)

}