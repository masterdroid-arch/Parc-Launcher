plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.brightblu.parc.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // T66: an above-33 call must fail the build, not warn. Fallbacks are unit-tested per SDK level.
    lint {
        abortOnError = true
        error += "NewApi"
    }
}
// Room writes the v1 schema here; LayoutSchemaTest reads it back (migration gate, T05).
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // T34: the shared contract fakes (FakeLayoutRepository) for the usage-layer tests, same as
    // feature/settings and feature/search already do.
    testImplementation(testFixtures(project(":core:model")))
}
