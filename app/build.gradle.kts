plugins {
    id("com.android.application") version "8.10.1" // 8.10.1 → 安定版に合わせるなら 8.1.0 推奨
    id("org.jetbrains.kotlin.android") version "1.9.25"
}

android {
    namespace = "com.example.readpdf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.readpdf"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable.add("CoroutineCreationDuringComposition")
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
    implementation("androidx.compose.material:material-icons-core:1.6.7")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}
