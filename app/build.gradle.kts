plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.taehyeon.giuncha"
    compileSdk = 34
    defaultConfig {
        applicationId = "ai.taehyeon.giuncha"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 제출용: 따로 만든 서명 키 없이, 안드로이드 기본 시험용 키로 서명해 바로 설치되게 한다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
