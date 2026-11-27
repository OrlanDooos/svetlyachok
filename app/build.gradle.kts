import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ua.svetlo.monitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.svetlo.monitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.4"
        // Ключ Visicom Data API (адрес дома по нажатию). Не в репозитории: свойство svetlo.visicomKey,
        // переменная SVETLO_VISICOM_KEY или строка visicomKey в keystore.properties. Без ключа функция выключена.
        val visicomKey = (findProperty("svetlo.visicomKey") as String?) ?: System.getenv("SVETLO_VISICOM_KEY")
            ?: ((findProperty("svetlo.keystore") as String?) ?: System.getenv("SVETLO_KEYSTORE"))?.let { f ->
                Properties().apply { file(f).inputStream().use { load(it) } }.getProperty("visicomKey")
            } ?: ""
        // Куда отправлять отметки домов от жителей (сервер сбора). Пусто — только локально.
        buildConfigField("String", "PINS_URL", "\"${(findProperty("svetlo.pinsUrl") as String?) ?: System.getenv("SVETLO_PINS_URL") ?: "https://svetlyachok-pins.svetlyachok.workers.dev"}\"")
        buildConfigField("String", "VISICOM_KEY", "\"${visicomKey.replace("\"", "")}\"")
    }

    // Ключ подписи хранится вне репозитория. Путь к keystore.properties: свойство svetlo.keystore
    // (~/.gradle/gradle.properties) или переменная SVETLO_KEYSTORE. Без него релиз подписывается отладочным ключом.
    val ksFile = (findProperty("svetlo.keystore") as String?) ?: System.getenv("SVETLO_KEYSTORE")
    val ks = ksFile?.let { f -> Properties().apply { file(f).inputStream().use { load(it) } } }
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi", "-opt-in=androidx.compose.ui.text.ExperimentalTextApi")
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                it.maxHeapSize = "3g"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
}
