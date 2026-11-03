plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val aiEndpoint = providers.gradleProperty("RUKN_AI_BASE_URL")
    .orElse("").get().trim().trimEnd('/')

android {
    namespace = "com.rukn.expiry"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rukn.expiry.rebuilt"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0-preview.1"
        buildConfigField("String", "AI_BASE_URL", "\"${aiEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE*") } }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
