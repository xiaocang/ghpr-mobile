import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val githubClientId = (
    localProperties.getProperty("github.clientId")
        ?: providers.gradleProperty("github.clientId").orNull
).orEmpty()
    .replace("\"", "\\\"")

val githubClientSecret = (
    localProperties.getProperty("github.clientSecret")
        ?: providers.gradleProperty("github.clientSecret").orNull
).orEmpty()
    .replace("\"", "\\\"")

val configuredServerUrl = (
    localProperties.getProperty("ghpr.serverUrl")
        ?: providers.gradleProperty("ghpr.serverUrl").orNull
).orEmpty().trim()
val defaultServerUrl = "https://ghpr-server.xiaocang.workers.dev"
val ghprServerUrl = (configuredServerUrl.ifBlank { defaultServerUrl })
    .replace("\"", "\\\"")

gradle.taskGraph.whenReady {
    val buildingAppDebug = allTasks.any { task ->
        task.path.startsWith(":app:") && task.path.contains("Debug", ignoreCase = true)
    }
    if (buildingAppDebug && configuredServerUrl.isBlank()) {
        throw GradleException(
            "Missing ghpr.serverUrl. Set it in android/local.properties or pass -Pghpr.serverUrl=... for debug builds."
        )
    }
}

android {
    namespace = "com.ghpr.app"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.ghpr.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        buildConfigField("String", "GHPR_SERVER_URL", "\"$ghprServerUrl\"")
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("String", "GITHUB_CLIENT_SECRET", "\"$githubClientSecret\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            val ks = System.getenv("KEYSTORE_FILE")
            signingConfig = if (ks != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    implementation(project(":core-domain"))

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
}
