plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.brightblu.parc.feature.search"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // T66: an above-33 call must fail the build, not warn.
    lint {
        abortOnError = true
        error += "NewApi"
    }
}
dependencies {
    implementation(project(":core:model"))
    // T67: SearchPrefs (recents, recents switch, web engine) and WebSearchEngine.
    implementation(project(":core:data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation.core)
    implementation(libs.coroutines.core)
    // T67: the "Search contacts" row asks for READ_CONTACTS at runtime.
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core:model")))
}
