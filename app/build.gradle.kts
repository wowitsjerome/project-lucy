plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lucy.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.lucy.phone"
        minSdk = 30
        targetSdk = 35
        versionCode = 12
        versionName = "0.3.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { buildConfigField("boolean", "ALLOW_DEBUGGING", "true") }
        release {
            buildConfigField("boolean", "ALLOW_DEBUGGING", "false")
            isMinifyEnabled = false
            val keyPath = System.getenv("LUCY_KEYSTORE")
            if (keyPath != null) {
                signingConfig = signingConfigs.create("production") {
                    storeFile = file(keyPath)
                    storePassword = System.getenv("LUCY_STORE_PASSWORD")
                    keyAlias = System.getenv("LUCY_KEY_ALIAS")
                    keyPassword = System.getenv("LUCY_KEY_PASSWORD")
                }
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
