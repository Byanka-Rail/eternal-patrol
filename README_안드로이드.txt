ETERNAL PATROL v6.24.6 — ANDROID + OFFICIAL-ORIGIN SELF-HOSTED SUPERTONIC 3 VOICE
================================================================================

구성
----
- app/src/main/assets/index.html = ETERNAL PATROL v6.24.6 완성 게임 HTML
- v6.24.4 MENU A안 누적
- v6.24.3 음성 무반응 진단/44.1kHz float PCM 재생 누적
- v6.24.6부터 음성팩 계보를 Supertone 공식 원본 → ETERNAL PATROL 직접 FP16 변환 → 우리 Release로 확정

Android
-------
- applicationId: com.silentseas.game
- versionName: 6.24.6
- versionCode: 62406
- minSdk: 24
- target/compileSdk: 36
- ABI: arm64-v8a
- ONNX Runtime Android: 1.29.0

음성팩 구조
-----------
1. 플레이어 APK에는 모델 가중치를 내장하지 않음.
2. .github/workflows/publish-voicepack.yml을 수동 실행할 때만:
   - Supertone/supertonic-3 공식 revision
     724fb5abbf5502583fb520898d45929e62f02c0b
     에서 공식 FP32 ONNX 4개, 설정, M1~M5 스타일, LICENSE를 GitHub Actions가 받음.
   - Actions가 FP32 ONNX를 직접 FP16으로 변환.
   - ONNX checker와 ONNX Runtime CPU 로드검사를 수행.
   - voicepack-v2 Release에 ZIP/manifest/SHA-256을 게시.
3. 실제 게임 사용자는 Byanka-Rail/eternal-patrol Release voicepack-v2에서만 다운로드.
4. APK는 manifest의 SHA-256, byte size, PACK_INFO.json provenance를 검증.
5. 합성은 설치 이후 기기 내부에서 오프라인으로 수행.

중요
----
- 제3자 FP16 변환 바이너리를 내려받거나 재배포하지 않음.
- 원 모델의 저작권과 BigScience OpenRAIL-M 조건은 그대로 유지.
- 게임 UI와 팩 내부에 AI 합성음성/라이선스/변환 사실을 고지.
- 음성 기능이 없거나 음성팩이 없으면 기존 텍스트 게임으로 정상 폴백.

배포 순서
---------
A. 최초 1회 음성팩 생성
   GitHub → Actions → Publish Official Supertonic Voice Pack → Run workflow
   완료 후 Releases → voicepack-v2 확인

B. APK 빌드
   GitHub → Actions → Build Android APK → Run workflow
   artifact:
   ETERNAL_PATROL_v6.24.6_OFFICIAL_SELFHOST_VOICE_debug_apk

C. 기기 시험
   옵션 → 승조원 음성 → 음성팩 받기
   설치 완료 후 음성 시험
