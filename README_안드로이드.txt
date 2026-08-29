ETERNAL PATROL v6.24.2 — ANDROID + OPTIONAL SUPERTONIC 3 VOICE
================================================================

기준 게임
- app/src/main/assets/index.html = ETERNAL PATROL v6.24.2
- v6.24.0 전투 MENU/잠망경 UX/전술 기억 + v6.24.1 선택형 음성 UI 누적
- 전투 계산·탐지·AI·역사전·Campaign/War 저장 코어는 음성 기능 때문에 변경하지 않음

앱 설정
- applicationId: com.silentseas.game
- versionName: 6.24.2
- versionCode: 62402
- minSdk 24 / targetSdk 36 / compileSdk 36
- Java 17 / AGP 8.13.2
- Android APK는 arm64-v8a 전용
- 세로 화면 고정, 전체화면 WebView, 화면 꺼짐 방지

승조원 음성
- 네이티브 JavaScript 브리지 이름: window.EternalVoice
- ONNX Runtime Android 1.29.0 사용
- 음성팩 미설치/브라우저판: 기존 텍스트 전용으로 자동 폴백
- 옵션: 끔 / 중요 보고(P0·P1) / 모든 전술 보고
- 역할별 M1~M5 음색과 말하기 속도는 HTML에서 관리하므로 이후 HTML 업데이트만으로 조정 가능

선택 음성팩
- Supertonic 3 FP16 ONNX 파생팩
- 출처: Kyumdroid/supertonic-3-quant (원 모델 Supertone/supertonic-3)
- 최종 설치 크기: 약 200MB
- 다운로드/설치 중 안전 여유공간: 약 320MB 이상 권장
- 최초 1회 사용자가 옵션에서 명시적으로 다운로드
- 설치 후 합성 자체는 오프라인
- 삭제 버튼으로 앱 데이터에서 음성팩만 제거 가능
- 다운로드는 중간 파일을 보존하고 가능한 경우 HTTP Range로 이어받음

왜 INT8 100MB가 아닌가
- 초기 검토한 dynamic INT8 파생은 ConvInteger 연산 때문에 일부 ONNX Runtime CPU/mobile 빌드에서 실행 실패 가능성이 확인됨.
- 첫 안정판은 호환성을 우선해 FP16 약 191MiB/200MB 계열을 사용.
- 더 작은 QDQ/LiteRT 변형은 별도 실기기 시험 후 후속 후보.

저장
- https://silentseas.local 고정 origin을 사용해 localStorage 저장영역 유지
- Android 뒤로가기 메뉴에서 세이브 JSON 백업/복원
- 앱 백그라운드 진입 시 Campaign.save / War.save 호출, WebView 타이머 정지, 재생 중 음성 정지
- 음성팩은 게임 세이브와 별도 앱 전용 디렉터리

게임 HTML 업데이트
- 기존 native GitHub update.json 방식 그대로 유지
- 최대 6시간 간격 확인
- HTML만 교체하므로 설치된 음성팩은 다시 다운로드하지 않음
- 허용 경로: raw.githubusercontent.com/Byanka-Rail/eternal-patrol/

빌드 — GitHub Actions
1. 이 프로젝트 폴더 전체를 GitHub 저장소에 올림
2. Actions > Build Android APK > Run workflow
3. 완료 후 artifact 'ETERNAL_PATROL_v6.24.2_SUPERTONIC_debug_apk' 다운로드
4. 내부 app-debug.apk 설치

실기기 첫 시험
1. 앱 실행 → 옵션 → 승조원 음성
2. '음성팩 받기' → 약 200MB 다운로드
3. 다운로드 완료 후 '음성 시험'
4. 정상 재생 확인 후 '중요 보고' 선택
5. 실제 플레이에서 음성 생성 지연·발열·메모리를 확인

라이선스
- 프로젝트 루트 THIRD_PARTY_NOTICES.txt 참고
- 시작 화면과 옵션에도 AI 합성음성/모델 라이선스 고지를 표시
- 음성팩 다운로드 시 모델 저장소의 LICENSE 파일도 함께 저장

현재 제작 환경 제한
- 이 작업 환경에는 Android SDK/Gradle 실행기가 없어 실제 APK 바이너리를 로컬 컴파일하지 못함.
- HTML 전체 JavaScript 구문검사와 DOM ID 중복검사, Java 소스 파싱 검사를 수행함.
- 실제 Android 의존성/네이티브 ABI 링크 여부는 포함된 GitHub Actions 빌드로 최종 검증해야 함.
