ETERNAL PATROL v6.25.5 — SAFE ANDROID MERGE

목적
- 지금까지의 Living Sea 게임 작업(v6.25.5)을 잃지 않는다.
- Android 음성 기준본 v6.24.8의 FP32 / mood / license / voicepack 기능을 잃지 않는다.
- 두 기준본을 역할별로 분리해 병합한다.

절대 기준
1) 게임 HTML 기준본
   ETERNAL_PATROL_v6.25.5_WORLD_AUDIO_CHART_FILL.html
   SHA-256: 6d3cfc113a1c46d8ac89d935a2fba66fb60228ce8e3d5cc157dd9a9a2a17539f

2) Android 기준본
   ETERNAL_PATROL_v6.24.8_ANDROID_FP32_MOOD_LICENSE_PACKAGE(1).zip
   SHA-256: fe6f079302d3e68752fe5d78780628c241bfc14e46e299d672f59edeb2eb00ab

병합 방법
- v6.24.8 Android 프로젝트 전체를 복사했다.
- v6.25.5 게임 HTML을 아래 두 위치에 동일 바이트로 넣었다.
  * /ETERNAL_PATROL.html
  * /app/src/main/assets/index.html
- Android 버전 메타데이터만 6.25.5 / 62505로 올렸다.
- FP32 음성 Java 3개는 v6.24.8에서 바이트 단위로 그대로 보존했다.
- ONNX Runtime Android 1.29.0 의존성도 그대로 보존했다.

보존된 음성 소스 SHA-256
- EternalVoiceBridge.java: b9262acdf45f13a65e790cc9139352018efa9e60348c95b38b573e9c80a03af3
- SupertonicEngine.java: 17a8dd2dc77402a824512e764ff64bff3a128d26f1af5b51998e906d9e8bb2d2
- VoicePackManager.java: e601f255a7a5eee01edcd39ff4d0dd51ebcfabbf7fbf4ff671d68b3572305c7d

GitHub 저장소 복구/업로드
- 이 ZIP의 내용을 저장소 루트에 전체 업로드하여 같은 경로 파일을 덮어쓴다.
- 예전에 잘못 만든 경량 프로젝트의 .github/workflows/build-android.yml 경로도
  이번 패키지가 안전한 FP32 빌드 workflow로 덮어쓴다.
- 정상 workflow는 .github/workflows/android-build.yml 이다.
- build-android.yml은 과거 경로를 잘못 눌러도 경량 APK가 나오지 않게 만든 안전 별칭이다.

빌드
Actions에서
  Build Android APK - FP32 Voice Safe
을 실행한다.

정상 Artifact 이름
  ETERNAL_PATROL_v6.25.5_FP32_MOOD_LIVING_SEA_debug_apk

빌드 전 workflow가 다음을 자동 검사한다.
- ETERNAL_PATROL.html == app/src/main/assets/index.html
- Living Sea v6.25.5 표식 존재
- versionCode 62505 / versionName 6.25.5
- onnxruntime-android:1.29.0 존재
- EternalVoiceBridge / SupertonicEngine / VoicePackManager 존재

이전 경량 패키지 잔여물
빌드 작업공간에서 아래 파일은 자동 제거한다.
- app/src/main/assets/ETERNAL_PATROL.html
- app/src/main/res/values/styles.xml
따라서 저장소에 잔여 파일이 남아도 이번 APK에는 끼어들지 않는다.

주의
- 이전에 만든 약 2.5MB 경량 APK 프로젝트는 기준본으로 사용하지 않는다.
- 이 패키지의 Android 계보는 v6.24.8 FP32 MOOD LICENSE이다.
- 실제 voicepack 모델은 기존 voicepack-v3 배포 구조를 그대로 사용한다.
