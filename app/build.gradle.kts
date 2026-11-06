import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// TomTom anahtarı APK'ya gömülmez; kullanıcı uygulamada bir kez girer (bkz. ApiKeys.kt).
// İmza bilgileri önce ortam değişkeninden (GitHub Actions), yoksa local.properties'ten okunur.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }

val keystorePath = secret("KEYSTORE_PATH")
val keystorePassword = secret("KEYSTORE_PASSWORD")
val hasKeystore = keystorePath != null && keystorePassword != null && file(keystorePath).exists()

// GitHub Actions her derlemede artan bir numara verir; yerelde 1 kalır.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ozguryilmaz.navinavi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ozguryilmaz.navinavi"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        buildConfigField("String", "GITHUB_REPO", "\"ozguryilmazz/navinavi\"")
    }

    signingConfigs {
        if (hasKeystore) {
            create("sabit") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "navinavi"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("sabit")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (!hasKeystore) {
    logger.warn("UYARI: Sabit imza anahtarı bulunamadı, varsayılan debug imzası kullanılıyor.")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.maplibre.android)
}
