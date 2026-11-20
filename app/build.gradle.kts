plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.constanvel.hytracl"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.constanvel.hytracl"
        // 28: android:lineHeight, which the CSS line-heights are ported to.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // The Laravel API; 10.0.2.2 is the host machine from the emulator. Override with -PapiUrl=….
        // Invitation links are built on the same host, so the deep link follows it.
        val apiUrl = providers.gradleProperty("apiUrl").getOrElse("http://10.0.2.2:8000/api/")
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        manifestPlaceholders["inviteHost"] = apiUrl.substringAfter("://").substringBefore("/").substringBefore(":")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}