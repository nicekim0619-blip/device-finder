# 📡 기기 탐지기 (안드로이드 앱)

주변 블루투스 기기를 창 없이 바로 스캔해서 레이더에 보여주고, 거리를 대략 알려줘요.

## 기능
- BLE 스캔: 이어폰, 워치, 태그 같은 기기
- 클래식 검색: '검색 가능' 상태인 핸드폰 (노란 점 📱)
- 목록에서 기기를 누르면 추적 모드 (뜨거움/차가움 + 진동)

## APK 만드는 법
### Android Studio
1. 압축을 풀고 Android Studio → Open → DeviceFinder 폴더 선택
2. 준비가 끝나면 메뉴 Build → Build App Bundle(s) / APK(s) → Build APK(s)
3. app/build/outputs/apk/debug/app-debug.apk 를 폰으로 옮겨 설치

### GitHub
이 폴더를 저장소에 올리면 .github/workflows/build.yml 이 자동으로 APK를 만들어요.
Actions 탭 → 완료된 작업 → device-finder-apk 다운로드
