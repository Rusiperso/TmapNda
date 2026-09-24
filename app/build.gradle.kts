plugins {
  id("com.android.application")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  id("com.tmap.nda.audiofocus")
  id("com.google.gms.google-services")
}

android {
    namespace = "com.tmap.nda"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tmap.nda"
        minSdk = 26
        targetSdk = 36
        versionCode = 528
        versionName = "19.4.18"

        // v: 재억 요청(2026-09-22) - 디스코드 웹훅 주소가 코드에 그대로 박혀 공개 저장소에
        // 커밋되면 스팸봇이 깃허브를 긁어서 찾아내 도배 → 디스코드가 웹훅을 자동 삭제하는
        // 일이 반복됨(9/17, 9/22 두 번). 저장소엔 빈 문자열만 남기고, 실제 값은 GitHub
        // Actions 시크릿(USAGE_WEBHOOK_URL/CRASH_WEBHOOK_URL)에서 빌드 시점에만 주입함.
        // 로컬 빌드(환경변수 없음)는 빈 값이 들어가 DiscordReporter가 조용히 전송을 건너뜀. #문제시 원복
        buildConfigField("String", "USAGE_WEBHOOK_URL", "\"${System.getenv("USAGE_WEBHOOK_URL") ?: ""}\"")
        buildConfigField("String", "CRASH_WEBHOOK_URL", "\"${System.getenv("CRASH_WEBHOOK_URL") ?: ""}\"")
    }

    signingConfigs {
        // 빌드(GitHub Actions)마다 새 VM이라 기본 debug.keystore가 매번 무작위로
        // 새로 생성되어, 버전 간 서명이 달라져 업데이트 설치시 "패키지 충돌" 오류가
        // 발생했음. 고정된 keystore를 레포에 포함시켜 항상 동일한 키로 서명되도록 함.
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      // v: 재억 제보(2026-09-03) - nMirror가 열어둔 길안내 창구에 붙으려면 그쪽과 같은
      // 규격 파일(ITbtService.aidl)이 필요한데, AGP 8부터는 이 기능이 기본으로 꺼져 있어
      // 규격 파일이 있어도 컴파일에서 무시됐다. #문제시 원복: false로 되돌리면 됨
      aidl = true
      buildConfig = true
      shaders = false
      viewBinding = true
      dataBinding = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  // v: 재억 요청(2026-09-22) - 디스코드 웹훅이 서버 관리 권한 탈취로 계속 삭제되는 문제 때문에,
  // "지금 켜져 있는 사람"만이라도 파이어베이스로 옮김. Realtime Database의 onDisconnect() 기능을
  // 쓰면 연결이 끊기는 순간 서버가 알아서 오프라인 처리를 해줘서, 예전처럼 "5분마다 신호 보내고
  // 15분 안이면 켜진 걸로 침" 하는 추측이 필요 없어짐. #문제시 원복
  implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
  implementation("com.google.firebase:firebase-database")
  implementation("com.google.firebase:firebase-auth")

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // AppCompat and ConstraintLayout for XML based Tmap
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("com.google.android.material:material:1.11.0")

  // TMapUISDK
  // v19.3.44: 재억 요청 - Tmap 공식 배포 zip(1.77) 샘플이 참조하는 최신 버전으로 업데이트.
  // 카카오내비 SDK는 카카오모빌리티 릴리즈 페이지 확인 결과 1.12.8-hotfix02(2025.11.18)가
  // 이미 최신이라 그대로 유지. #문제시 원복
  implementation("com.tmapmobility.tmap:tmap-ui-sdk:1.0.0.0158")
  // 카카오내비 SDK - 검색/경로계산/실시간안내를 카카오로 우회 (Tmap 화면 위에 오버레이로 얹음)
  implementation("com.kakaomobility.knsdk:knsdk_ui:1.12.8-hotfix02")

  // v2.0: Android Auto 차량 클러스터/HUD로 회전·거리·ETA Trip 정보 전송
  implementation("androidx.car.app:app:1.7.0")
  implementation("androidx.car.app:app-projected:1.7.0")

  // for vsm sdk
  implementation("com.google.flatbuffers:flatbuffers-java:1.11.0")

  // Dependency for Navi SDK.
  implementation("com.squareup.retrofit2:retrofit:2.9.0")
  implementation("com.squareup.retrofit2:converter-gson:2.9.0") {
      exclude(group = "com.google.code.gson", module = "gson")
  }
  implementation("com.squareup.retrofit2:adapter-rxjava2:2.9.0")

  val okhttpVersion = "4.11.0"
  implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
  implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")

  val exoplayerVersion = "2.19.1"
  implementation("com.google.android.exoplayer:exoplayer:$exoplayerVersion")
  implementation("com.google.android.exoplayer:exoplayer-core:$exoplayerVersion") {
      exclude(group = "com.google.guava", module = "guava")
  }
  implementation("com.google.guava:guava:33.5.0-jre")
  implementation("com.google.android.exoplayer:exoplayer-ui:$exoplayerVersion")

  implementation("com.github.bumptech.glide:glide:4.13.2")
  implementation("com.google.android.gms:play-services-location:21.3.0")
  implementation("com.airbnb.android:lottie:3.0.7")
  implementation("com.squareup.okio:okio:1.17.6")
  implementation("com.google.code.gson:gson:2.10.1")
}
