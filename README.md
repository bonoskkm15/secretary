# PhoneRelay

문자와 앱 알림을 사용자가 만든 수집 규칙(레시피)으로 읽어 JSON 이벤트로 바꾸고, 로컬 큐를 거쳐 서버로 전송하는 Android 앱입니다.

## 현재 구현 범위

- `규칙 / 기록 / 설정` 3개 탭
- 규칙 추가·편집·복제·삭제·활성화/비활성화
- 7단계 규칙 마법사: 이름 → 출처 → 샘플 → 템플릿 → 조건 → 출력 → 미리보기
- SMS/LMS 출처: 발신번호 필터, 최근 문자 샘플, 직접 원문 입력
- 앱 알림 출처: 설치된 앱 선택, 알림 도착/제거, 제목·내용·제목+내용
- 템플릿 자리표시자: `{key}`, `{key:number}`, `{key:datetime(MM/dd HH:mm)}`, `{_}`
- 전체 일치/부분 일치, 포함·제외 키워드, 원문 전송, 원문 마스킹, 형식 불일치 기록
- 숫자·날짜 변환, 텍스트 값 매핑, 발생 시각 필드 지정
- 최근 전송 JSON 상세 확인 및 복사, 형식 불일치 원문에서 규칙 편집 시작
- 규칙 JSON 내보내기/가져오기(서버 토큰은 포함하지 않음)
- SQLite 전송 큐, 중복 방지, 실패 재시도, HTTPS Bearer 토큰 전송

통화·위치·기기 상태와 주기 트리거는 다음 개발 단계의 준비 중 항목입니다.

## 소스 구조

```
app/src/main/java/com/bonoskkm15/phonerelay/
  PhoneRelayApp.kt          Application, 주기 전송 등록
  collector/                출처별 수신부 (SmsReceiver, MmsScanner)
  core/                     봉투·큐·전송 스케줄·설정·토큰·상주 서비스
  rule/                     규칙 모델·저장소·템플릿 컴파일러·규칙 엔진 (Android 비의존)
  transport/                HTTPS JSON, syslog TCP
  ui/                       탭 3개와 규칙 마법사
app/src/test/               규칙 엔진 JVM 단위 테스트
```

`rule/TemplateCompiler.kt`와 `rule/RuleEngine.preview()`는 Android API를 쓰지 않으므로
에뮬레이터 없이 `./gradlew test`로 검증합니다.

## 서버 설정

앱을 설치한 뒤 설정 화면에서 다음 값을 입력합니다.

- 서버 주소: `https://api.bonoskkm15.duckdns.org/v1/events`
- 토큰: 서버의 `PHONELOG_TOKEN` 값
- 기기 ID: 기기를 구분할 이름

서버 응답은 다음 형식이어야 합니다.

```json
{"accepted":["event-id-1"]}
```

앱은 `accepted`에 포함된 이벤트만 전송 완료로 표시합니다.

## 빌드

JDK 17 이상과 Android SDK(플랫폼 35)만 있으면 됩니다. Gradle은 래퍼가 알아서 내려받으므로 따로 설치하지 않아도 됩니다.

```powershell
# Android SDK 위치를 local.properties 에 적거나 환경변수로 지정
$env:ANDROID_HOME = "C:\Users\<계정>\AppData\Local\Android\Sdk"

.\gradlew.bat test            # 규칙 엔진 단위 테스트
.\gradlew.bat assembleDebug   # 디버그 APK
```

macOS·Linux에서는 `./gradlew test`, `./gradlew assembleDebug`입니다.

생성 파일은 `app\build\outputs\apk\debug\app-debug.apk`입니다.

IDE는 IntelliJ IDEA(Android 플러그인)나 Android Studio 어느 쪽이든 됩니다. IntelliJ를 쓸 경우 AGP 버전이 플러그인 지원 범위 안에 있어야 하므로 `gradle/libs.versions.toml`의 `agp` 값을 함부로 올리지 않습니다. IDE 없이 명령줄만으로도 위 두 명령이면 충분합니다.

실기기에서 SMS 수신·읽기 권한과 알림 접근 권한을 허용해야 합니다. LMS 보완 스캔은 알림 접근 서비스가 연결된 뒤 동작합니다.

Google Play 배포 시 SMS 권한 정책 검토가 필요합니다. 개인 기기에서 직접 설치하는 테스트 APK와 Play 배포 요건은 다를 수 있습니다.

## 앱 알림 수집 규칙

`규칙` 탭의 `+`를 누르고 `앱 알림`을 선택한 뒤 모니터링할 앱, 알림 도착·제거 행위, 제목·내용 조합을 지정합니다. Android의 알림 접근 권한이 필요하며, 다른 앱 내부의 버튼 클릭이나 화면 내용 전체를 읽는 기능은 제공하지 않습니다.
