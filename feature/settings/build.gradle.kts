plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.brightblu.parc.feature.settings"
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
    // T16: the settings screen reads/writes ParcPrefs and the validated skin catalog.
    implementation(project(":core:data"))
    implementation(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.coroutines.core)
    // T17: the backup screen owns the SAF pickers, so activity-compose is needed in main, not debug.
    implementation(libs.activity.compose)
    // T17: the debug review host builds the real ring geometry to restore a layout against it.
    debugImplementation(project(":feature:ring"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core:model")))
    // T16: proves the layout engine is skin-independent (geometry identical between skins).
    testImplementation(project(":feature:ring"))
}
