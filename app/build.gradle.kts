import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional release signing. Create keystore.properties (git-ignored) in the project
// root with storeFile / storePassword / keyAlias / keyPassword to sign release builds.
// Without it, release builds fall back to the debug key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

// Personal server details live in server.properties (git-ignored), not in the repo.
// See server.properties.example. Without it the app starts with no server set
// (enter it in Settings) and allows HTTPS only.
val serverPropsFile = rootProject.file("server.properties")
val serverProps = Properties().apply {
    if (serverPropsFile.exists()) serverPropsFile.inputStream().use { load(it) }
}
val defaultServer = serverProps.getProperty("crux.server", "").trim()
val cleartextHost = serverProps.getProperty("crux.cleartextHost", "").trim()

// HTTPS everywhere, except an optional Tailscale address, which is already
// encrypted end to end by WireGuard.
val generatedRes = layout.buildDirectory.dir("generated/crux-res").get().asFile
File(generatedRes, "xml").mkdirs()
File(generatedRes, "xml/network_security_config.xml").writeText(buildString {
    appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
    appendLine("<network-security-config>")
    appendLine("""    <base-config cleartextTrafficPermitted="false" />""")
    if (cleartextHost.isNotEmpty()) {
        appendLine("""    <domain-config cleartextTrafficPermitted="true">""")
        appendLine("""        <domain includeSubdomains="false">$cleartextHost</domain>""")
        appendLine("    </domain-config>")
    }
    appendLine("</network-security-config>")
})

android {
    namespace = "com.rokjuric.crux"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokjuric.crux"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
        resValue("string", "app_name", "Crux")
        // The home server behind Tailscale Funnel; changeable in Settings.
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }

    sourceSets["main"].res.srcDir(generatedRes)

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Crux · debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
