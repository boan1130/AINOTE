import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// 全域排除舊的 IntelliJ annotations，避免 Duplicate class
configurations.all {
    exclude(group = "com.intellij", module = "annotations")
}

android {
    namespace = "com.ld.ainote"
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.ld.ainote"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        //testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        //buildConfigField("String", "OPENAI_API_KEY", "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 讀取 local.properties 的 OPENAI_API_KEY
        val props = Properties()
        val lp = rootProject.file("local.properties")
        if (lp.exists()) props.load(lp.inputStream())
        val openaiKey = props.getProperty("OPENAI_API_KEY") ?: ""

        // 第三個參數必須是可直接進入 Java 原始碼的字面量：用雙引號包起來
        buildConfigField("String", "OPENAI_API_KEY", "\"$openaiKey\"")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.auth)
    implementation(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.google.android.material:material:1.9.0")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.material:material:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
}