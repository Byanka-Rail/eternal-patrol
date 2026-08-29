ETERNAL PATROL v6.24.5 — ANDROID + SELF-HOSTED OPTIONAL SUPERTONIC 3 VOICE
============================================================================

기준 게임
- app/src/main/assets/index.html = ETERNAL PATROL v6.24.5
- v6.24.4 MENU A안 + v6.24.3 음성 무반응 진단/float PCM 재생 + 기존 누적 시스템
- 게임 시뮬레이션/저장 코어는 음성 배포 변경 때문에 수정하지 않음

앱 설정
- applicationId: com.silentseas.game
- versionName: 6.24.5
- versionCode: 62405
- minSdk 24 / targetSdk 36 / compileSdk 36
- Java 17 / AGP 8.13.2
- Android APK는 arm64-v8a 전용
- 세로 화면 고정, 전체화면 WebView, 화면 꺼짐 방지

승조원 음성
- 네이티브 JavaScript 브리지: window.EternalVoice
- ONNX Runtime Android 1.29.0
- 음성팩 미설치/브라우저판: 기존 텍스트 전용으로 자동 폴백
- 옵션: 끔 / 중요 보고(P0·P1) / 모든 전술 보고
- 역할별 M1~M5 음색과 말하기 속도는 HTML에서 관리

선택 음성팩 — 런타임 출처를 우리 쪽으로 이전
- 게임 사용자가 접속하는 배포처:
  Byanka-Rail/eternal-patrol GitHub Release / tag voicepack-v1
- 사용자는 Kyumdroid/Hugging Face에 직접 연결하지 않음
- APK는 Release의 작은 JSON manifest를 먼저 받음
- manifest가 지정한 우리 Release ZIP만 허용
- ZIP byte size + SHA-256 검증 후 안전 압축해제
- 최종 설치 크기 약 200MB
- 다운로드/설치 중 약 360MB 이상의 여유공간 권장
- 설치 후 합성은 오프라인
- HTML 업데이트와 음성팩은 서로 독립

음성팩의 모델 출처/라이선스
- 원 모델: Supertone/supertonic-3, BigScience OpenRAIL-M
- FP16 파생: Kyumdroid/supertonic-3-quant
- voicepack-v1은 upstream revision
  d9bfd0a9384b42a76f9256eafdd3d8a5cc4706ba
  를 고정해 재패키징함
- ETERNAL PATROL은 모델 가중치를 수정하거나 자체 모델이라고 주장하지 않음
- 음성팩 안에 LICENSE, THIRD_PARTY_NOTICES.txt, PACK_INFO.json 포함

중요: 음성팩 Release를 먼저 한 번 만들어야 함
------------------------------------------------
1. 이 프로젝트 전체를 Byanka-Rail/eternal-patrol 저장소에 업로드
2. GitHub Actions → "Publish Supertonic Voice Pack" → Run workflow
3. workflow가 고정된 upstream FP16 파일을 받아 voicepack-v1 Release를 생성
4. Release에는 아래 3개 파일이 생김
   - ETERNAL_PATROL_SUPERTONIC3_FP16_V1.zip
   - ETERNAL_PATROL_SUPERTONIC3_FP16_V1.json
   - ETERNAL_PATROL_SUPERTONIC3_FP16_V1_SHA256.txt
5. 이 작업은 음성팩 버전을 바꾸지 않는 한 매 APK 빌드마다 반복할 필요 없음

APK 빌드
--------
1. Actions → "Build Android APK" → Run workflow
2. 완료 후 artifact
   ETERNAL_PATROL_v6.24.5_SELFHOST_VOICE_debug_apk
   다운로드
3. 내부 app-debug.apk 설치

실기기 첫 시험
1. 앱 실행 → 옵션 → 승조원 음성
2. 음성팩 받기
3. 표시가 다운로드 → 검증 → 설치 완료로 진행되는지 확인
4. 음성 시험
5. 정상 재생 후 중요 보고 또는 모든 전술 보고 선택

게임 HTML 업데이트
- 기존 update.json 방식 유지
- manifest: https://raw.githubusercontent.com/Byanka-Rail/eternal-patrol/main/update.json
- HTML만 교체하므로 설치된 음성팩은 다시 받지 않음

저장
- https://silentseas.local 고정 origin으로 localStorage 유지
- JSON 백업/복원 유지
- 음성팩은 게임 세이브와 별도 앱 전용 디렉터리

검증 경계
- 이 작업 환경에는 Android SDK가 없어 assembleDebug 실제 컴파일은 GitHub Actions가 최종 수행함
- 로컬에서는 HTML 전체 JS 구문, DOM ID, Java 소스 구문/정적 정합성, workflow YAML, 패키지 경로를 검증함
