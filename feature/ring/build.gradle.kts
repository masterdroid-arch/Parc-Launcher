plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.brightblu.parc.feature.ring"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // T65: API 33 support proof. NewApi is an error, not a warning.
    lint {
        abortOnError = true
        error += "NewApi"
    }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation.core)
    testImplementation(libs.junit)

    implementation(project(":core:model"))
    testImplementation(testFixtures(project(":core:model")))
}
