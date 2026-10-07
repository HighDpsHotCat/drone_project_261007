# DroneController

STM32H743 + ESP32-S3/OV3660 드론용 Kotlin / Jetpack Compose Android 컨트롤러입니다.

- BLE 검색, 연결/해제, 조종 서비스 준비 상태 표시
- 연결 완료 후 컨트롤 화면 진입, 뒤로가기 시 기기 연결 화면 복귀
- 왼쪽 전후·좌우 / 오른쪽 상승·하강 조이스틱, 동시 터치 및 중립 복귀
- Wi-Fi MJPEG 실시간 영상과 조이스틱 오버레이
- MP4 녹화 시작/중지 및 영상 공유
- 비상 정지 요청, 화면 이탈·앱 백그라운드 전환 처리

ESP32/STM32 펌웨어는 별도로 필요합니다. 연결 UUID, 명령 패킷, 영상 서버와 휴대폰 테스트 절차는 [연동 규약](docs/ESP32_PROTOCOL.md)을 참고하세요.
앱만으로 위치·고도 유지 또는 비행 제어가 구현되지는 않습니다.

## 개발

Android Studio에서 프로젝트를 열고 Gradle 동기화 후 실제 Android 휴대폰에서 실행하세요.
JDK 21, Android SDK 36.1, Build Tools 36.0.0이 필요합니다. 최소 Android 버전은 8.0/API 26입니다.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

이 Codex 클라우드에서는 먼저 `source /workspace/.cloud-setup/activate.sh`를 실행합니다.
APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
BLE·카메라·MP4 녹화는 실제 휴대폰과 기체로 검증해야 합니다.
