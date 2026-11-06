import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Gizli değerler önce ortam değişkeninden (GitHub Actions), yoksa local.properties'ten okunur.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }

val tomtomKey = secret("TOMTOM_API_KEY") ?: ""
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

        buildConfigField("String", "TOMTOM_API_KEY", "\"$tomtomKey\"")
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
if (tomtomKey.isEmpty()) {
    logger.warn("UYARI: TOMTOM_API_KEY tanımlı değil.")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
