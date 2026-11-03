plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.brightblu.parc.core.model"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testFixtures { enable = true }
    sourceSets.getByName("testFixtures").java.srcDir("src/testFixtures/kotlin")
    // T65: API 33 support proof. NewApi is an error, not a warning.
    lint {
        abortOnError = true
        error += "NewApi"
    }
}
dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    // T166: ParcPage, ParcCardContent, ParcSettingsRow and ParcSectionHeader lay out with foundation.
    api(libs.compose.foundation)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:model")))
}
// Host-side assertions avoid adding a test framework outside the approved library list.
val contractTest by tasks.registering(JavaExec::class) {
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.brightblu.parc.model.ContractTestKt")
    doFirst {
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    }
}
tasks.matching { it.name == "testDebugUnitTest" || it.name == "check" }.configureEach {
    dependsOn(contractTest)
}
