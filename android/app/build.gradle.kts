import com.android.build.api.dsl.VariantDimension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Build-time configuration comes from local.properties or the environment, so
// neither a server address nor a client id is ever committed.
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun setting(key: String, env: String, fallback: String): String =
    local.getProperty(key) ?: System.getenv(env) ?: fallback

/**
 * BotFather issues an app id of its own to every native app it registers, and only
 * that host serves an assetlinks.json naming the package. The debug build ships
 * under its own applicationId, so it is a separate registration with its own id.
 */
fun VariantDimension.telegramApp(appId: String) {
    val host = "app$appId-login.tg.dev"
    manifestPlaceholders["telegramHost"] = host
    buildConfigField("String", "TELEGRAM_REDIRECT_URI", "\"https://$host/tglogin\"")
}

android {
    namespace = "io.github.urionsisdi.nfcintime"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.urionsisdi.nfcintime"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "TELEGRAM_CLIENT_ID",
            "\"${setting("nfcit.telegram.clientId", "NFCIT_TELEGRAM_CLIENT_ID", "")}\"",
        )
    }

    signingConfigs {
        // Present only when the release keystore is available: a developer points
        // local.properties at it, CI decodes it from a secret, and a build without
        // one still assembles a debug APK.
        val storePath = setting("nfcit.keystore", "NFCIT_KEYSTORE", "")
        if (storePath.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = setting("nfcit.keystore.password", "NFCIT_KEYSTORE_PASSWORD", "")
                keyAlias = setting("nfcit.key.alias", "NFCIT_KEY_ALIAS", "")
                keyPassword = setting("nfcit.key.password", "NFCIT_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // 10.0.2.2 is the host loopback as seen from an emulator; a phone on the
            // same network needs the machine's address in local.properties.
            buildConfigField(
                "String",
                "BASE_URL",
                "\"${setting("nfcit.baseUrl.debug", "NFCIT_BASE_URL_DEBUG", "http://10.0.2.2:8080")}\"",
            )
            buildConfigField(
                "boolean",
                "DEV_AUTH",
                setting("nfcit.devAuth", "NFCIT_DEV_AUTH", "true"),
            )
            telegramApp(setting("nfcit.telegram.appId.debug", "NFCIT_TELEGRAM_APP_ID_DEBUG", "0"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField(
                "String",
                "BASE_URL",
                "\"${setting("nfcit.baseUrl", "NFCIT_BASE_URL", "https://in-time-nfc.ru")}\"",
            )
            buildConfigField("boolean", "DEV_AUTH", "false")
            telegramApp(setting("nfcit.telegram.appId", "NFCIT_TELEGRAM_APP_ID", "0"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.telegram.login)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
