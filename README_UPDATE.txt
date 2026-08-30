ETERNAL PATROL v6.25.5 Android GitHub Updater
=============================================

이번 APK의 목적
- APK 자체에 항상 보이는 [업데이트 확인] 버튼을 둡니다.
- 앱 시작 시 최대 6시간 간격으로 update.json을 자동 확인합니다.
- GitHub의 ETERNAL_PATROL.html만 내려받아 교체합니다.
- SHA-256이 update.json과 맞지 않으면 새 HTML을 설치하지 않습니다.
- 실패하면 기존 게임 파일을 유지합니다.

GitHub 저장소에 올릴 핵심 파일
1. ETERNAL_PATROL.html
2. update.json
3. Android 프로젝트(app/, build.gradle, settings.gradle, gradle.properties)
4. .github/workflows/build-android.yml

최초 APK 빌드
1. 이 ZIP 내용을 Byanka-Rail/eternal-patrol 저장소 루트에 업로드/덮어쓰기
2. GitHub Actions > Build Android APK > Run workflow
3. 완료 후 Artifacts에서 ETERNAL_PATROL_v6.25.5_GITHUB_UPDATER_debug_apk 다운로드
4. APK 설치

향후 게임 HTML 업데이트
1. 새 완성본 HTML 파일명을 ETERNAL_PATROL.html로 변경하여 GitHub 루트에 덮어쓰기
2. 새 HTML SHA-256 계산
3. update.json의 gameVersion을 올리고 sha256을 새 값으로 변경
4. Commit
5. APK에서 [업데이트 확인] 누름

주의
- 현재 APK가 다른 서명키로 만들어졌다면 Android가 기존 앱 위에 설치를 거부할 수 있습니다.
  이 경우 기존 APK와 새 APK의 서명이 다르다는 뜻입니다. 같은 서명키를 계속 사용하려면 GitHub Secrets에
  배포용 keystore를 보관하도록 릴리즈 서명 workflow를 별도로 고정해야 합니다.
- applicationId는 기존 호환을 위해 com.silentseas.game 그대로입니다.
- 번들 게임 버전은 6.25.5입니다.
- 현재 updater는 update.json의 url 또는 gameUrl 둘 다 읽습니다.
