import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.indigodev.karooep8"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.indigodev.karooep8"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    val keystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val requestedAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "karoo-ep8"
    val keyAliasPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword
    val hasReleaseSigning = keystoreFile != null &&
        keystoreFile.exists() &&
        keystorePassword != null &&
        keyAliasPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            val resolvedAlias = runCatching {
                val ks = KeyStore.getInstance(keystoreFile!!, keystorePassword!!.toCharArray())
                if (ks.containsAlias(requestedAlias) && ks.isKeyEntry(requestedAlias)) {
                    requestedAlias
                } else {
                    java.util.Collections.list(ks.aliases()).firstOrNull { ks.isKeyEntry(it) } ?: requestedAlias
                }
            }.getOrDefault(requestedAlias)

            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = resolvedAlias
                keyPassword = keyAliasPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
