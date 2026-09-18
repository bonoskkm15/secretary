# PhoneRelay 수집 규칙(레시피) 재설계 명세

> 이 문서는 구현 담당 AI에게 전달하는 작업 지시서다. 프로젝트 경로: `D:\Workspace\IdeaProjects\PhoneRelay`
> 작성일: 2026-09-17

## 0. 배경

PhoneRelay는 휴대폰(갤럭시 Z Flip7, Android 16)의 데이터를 수집해 개인 서버로 보내는 Android 앱이다.

- 언어/빌드: Kotlin, Jetpack Compose, minSdk 26, targetSdk 35, AGP 8.12 (IntelliJ IDEA에서 개발)
- 서버: `POST https://api.bonoskkm15.duckdns.org/v1/events`, `Authorization: Bearer <토큰>`
- 서버 응답: `{"accepted": ["event_id", ...]}` — 여기에 포함된 이벤트만 전송 완료로 처리
- 배포: 개인 사이드로드 APK (Play 스토어 배포 없음)

현재 문제점:

1. 권한·전송 설정·수집 항목·큐 상태가 **한 화면에 몰려 있다.**
2. 카드 SMS는 고정 스위치, 앱 알림은 별도 대화상자로 **수집 방식이 제각각이다.**
3. 보낼 내용을 **세밀하게 고를 수 없다** (포맷 추출, 필드 선택, 원문 포함 여부 등).
4. 실제로 어떤 JSON이 나가는지 **보내기 전에 확인할 수 없다.**

목표: 모든 수집을 **"규칙(레시피)" 하나의 모델로 통일**하고, 사용자가 **이름 → 어디서 → 무엇을(포맷 지정) → 보낼 항목 → 미리보기** 순서로 규칙을 만들게 한다.

---

## 1. 유지 / 교체 / 신규

| 구분 | 대상 | 비고 |
| --- | --- | --- |
| 유지 | `core/EventQueue` (SQLite 전송 큐, dedup_key) | 기록 탭 JSON 보기용 조회 메서드만 추가 |
| 유지 | `core/SendWorker`, `core/SendScheduler` | |
| 유지 | `transport/*` (HTTPS JSON, syslog TCP) | |
| 유지 | `core/SecretStore` (Keystore AES-GCM 토큰) | |
| 유지 | `core/PhoneEvent` 봉투(`phone_event/1`) | 형식 변경 금지 (서버 호환) |
| 유지 | `SmsReceiver`(단문), `MmsScanner`(장문 LMS), `CollectorHostService`(NotificationListenerService 상주 호스트), `BootReceiver` | 수신 후 처리부만 규칙 엔진으로 연결 |
| 교체 | `collector/cardsms/CardSmsCollector`, `KbCardParser` | 규칙 엔진 + 기본 프리셋으로 대체. 파서 단위 테스트의 검증 케이스는 템플릿 엔진 테스트로 옮긴다 |
| 교체 | `collector/notification/*` (NotificationRule 등) | 규칙 엔진으로 대체 |
| 신규 | `rule/` 패키지 (모델, 저장소, 템플릿 컴파일러, 엔진) | |
| 신규 | UI 전체 (탭 3개 + 규칙 마법사) | `ui/MainActivity.kt` 단일 파일 구조 폐기, 화면별 파일 분리 |

---

## 2. 화면 구성

하단 탭 3개 (Material3 `NavigationBar`).

```
[ 규칙 ]   [ 기록 ]   [ 설정 ]
```

### 2.1 규칙 탭

```
┌ 수집 규칙 ───────────────────── [+]┐
│ ● KB 신용카드 승인                 │
│   문자 · 수신 즉시 · 포맷 7필드     │
│ ● 카카오톡 알림                    │
│   앱 알림 · 도착 시 · 제목+내용     │
│ ○ 신한카드 승인 (꺼짐)             │
└────────────────────────────────────┘
```

- 각 행: 이름, 출처, 트리거, 요약, 켜기/끄기 스위치
- 행 탭 → 편집(마법사 재진입), 길게 누르기 → 복제/삭제
- 규칙마다 최근 매칭 시각, 오늘 매칭 건수 표시
- `+` → 새 규칙 마법사

### 2.2 기록 탭

- 큐의 최근 이벤트 목록: 상태(⏳대기 / ✅전송 / ❌실패), 시각, 규칙 이름, 요약
- 항목 탭 → **실제 전송 JSON 전체**를 보여주는 상세 화면 (복사 버튼)
- 상단: 대기/전송/실패 건수, 마지막 전송 결과, `지금 전송`, `실패 건 재시도`
- 형식 불일치(`match_error`) 이벤트는 별도 표시하고, 상세 화면에서 **"이 원문으로 규칙 수정"** 버튼 제공

### 2.3 설정 탭

- 전송: HTTPS(주소·토큰) / syslog(호스트·포트) 선택, 기기 ID, Wi-Fi 전용, `연결 테스트` 버튼
- 권한 상태: 문자 수신·읽기, 알림 접근, 알림 표시, 배터리 최적화 제외 — 각각 상태 표시 + 설정 이동 버튼
- 사이드로드 앱 안내: 권한이 회색이면 "앱 정보 → ⋮ → 제한된 설정 허용"
- 규칙 내보내기/가져오기 (JSON)

---

## 3. 규칙 만들기 마법사

한 단계씩 넘기는 화면. 이전/다음 버튼, 마지막에 저장.

```
① 이름       KB 신용카드 승인
② 어디서     [문자] [앱 알림]            ← 1차 구현 범위
             (통화 / 위치 / 기기 상태는 2차, 버튼은 "준비 중"으로 표시)
③ 샘플       [최근 문자에서 고르기] [최근 알림에서 고르기] [직접 붙여넣기]
④ 포맷 지정  샘플에서 값 부분을 선택해 필드로 지정
⑤ 조건·옵션  매칭 조건, 불일치 처리
⑥ 보낼 항목  필드·메타데이터 체크
⑦ 미리보기   샘플로 생성한 실제 JSON 확인 → 저장
```

### ② 어디서 (출처)

| 출처 | 추가 설정 | 입력 텍스트 |
| --- | --- | --- |
| 문자 (SMS/LMS) | 발신번호 필터(선택, 여러 개, 숫자만 비교) | 문자 본문 |
| 앱 알림 | 앱 선택(설치 앱 목록, 여러 개 가능), 이벤트: 도착 / 제거 | 선택: 제목, 내용, 제목+내용 (`제목\n내용`) |

- 앱 알림은 진행 중(ongoing) 알림, 그룹 요약 알림, 이 앱 자신의 알림은 기본 제외
- 같은 알림 갱신이 반복되면 `(package, key, title, text)` 해시로 중복 제거

### ③ 샘플

- **최근 문자에서 고르기:** `READ_SMS`로 최근 50건(SMS + MMS 텍스트) 목록 표시. ②의 발신번호 필터가 있으면 먼저 거른다.
- **최근 알림에서 고르기:** `CollectorHostService`가 최근 알림 50건을 **메모리에만** 보관(앱 재시작 시 사라져도 됨). ②에서 고른 앱으로 필터.
- **직접 붙여넣기:** 텍스트 입력.
- 샘플 원문은 규칙에 저장한다 (나중에 편집·미리보기에 재사용).

### ④ 포맷 지정 (핵심)

샘플 텍스트를 보여주고, 사용자가 **텍스트 일부를 선택 → "필드로 지정"** 하면 그 부분이 자리표시자로 바뀐다.

```
KB국민카드{card_last4}
{status}
{amount:number} 원({payment_type})
{merchant}
고객명 {_}
승인시각 {approved_at:datetime(MM/dd HH:mm)}
누적 {cumulative:number}원
```

필드 지정 시 입력받는 값:

| 항목 | 설명 |
| --- | --- |
| 표시 이름 | 한글 가능 (예: 카드끝자리) |
| JSON 키 | 영문 snake_case. 기본값은 자동 제안, 수정 가능 |
| 타입 | 텍스트 / 숫자 / 날짜·시간 / 무시 |
| 날짜 형식 | 날짜·시간 타입일 때 (`MM/dd HH:mm`, `yyyy.MM.dd HH:mm` 등 프리셋 + 직접 입력) |
| 값 매핑 (선택) | 텍스트 타입에서 치환 표. 예: `승인→approved`, `승인취소→cancelled`, `취소→cancelled`, `거절→declined` |

화면에는 **템플릿 편집 모드**(자리표시자 문법을 직접 수정)도 제공한다. 두 모드는 같은 데이터를 편집한다.

필드 타입 규칙:

| 타입 | 매칭 정규식 | 변환 |
| --- | --- | --- |
| 텍스트 | `(.+?)` (줄 끝 자리표시자는 `(.+)`) | 앞뒤 공백 제거, 값 매핑 적용 |
| 숫자 | `(-?[\d,]+(?:\.\d+)?)` | 쉼표 제거 → Long, 소수점 있으면 Double |
| 날짜·시간 | 지정 형식에서 생성 (`MM`→`\d{1,2}`, `yyyy`→`\d{4}`, `HH`→`\d{1,2}`, `mm`→`\d{2}` …) | ISO-8601 `+09:00`. 연도가 없으면 수신 시각의 연도를 쓰되, 결과가 수신 시각보다 1일 넘게 미래면 전년도로 보정 |
| 무시 `{_}` | `.*?` | 전송하지 않음 (고객명 등 개인정보) |

### 템플릿 → 정규식 컴파일 규칙

1. 자리표시자 밖의 텍스트는 `Regex.escape` 처리한다.
2. 연속 공백은 `\s+`, 줄바꿈은 `\s*\n\s*`로 바꿔서 공백 차이에 관대하게 매칭한다.
3. 입력 텍스트는 매칭 전에 정규화한다: CRLF→LF, 줄 앞뒤 공백 제거, 빈 줄 제거, 맨 앞의 `[Web발신]` 같은 대괄호 한 줄 제거.
4. 전체 일치(`matchEntire`)를 기본으로 하고, 옵션으로 "부분 일치 허용"(`find`)을 둔다.
5. 컴파일 실패, 필드 키 중복, 인접한 두 자리표시자 사이에 구분 텍스트가 없는 경우는 저장 전에 오류로 알린다.

### ⑤ 조건·옵션

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| 포함 키워드 | 없음 | 모두 포함해야 매칭 시도 (빠른 사전 필터) |
| 제외 키워드 | `인증번호` | 하나라도 있으면 무시 |
| 포맷 없이 원문 전송 | 끔 | 켜면 ④를 건너뛰고 텍스트 전체를 `text` 필드로 보냄 |
| 불일치 알림 | 켬 | 사전 필터(발신번호·키워드)는 통과했는데 포맷이 안 맞으면 `match_error` 이벤트 전송 (원문 포함, `{_}` 영역은 알 수 없으므로 원문 전체에서 규칙에 등록된 마스킹 패턴만 적용) |
| 마스킹 패턴 | `고객명 .*` 줄 제거 | 원문을 보낼 때 적용할 정규식 목록 |

### ⑥ 보낼 항목

체크박스 목록:

- 추출 필드 (④에서 지정한 필드 각각, `무시` 제외)
- 메타데이터: 수신 시각(기본 ☑), 출처 채널 sms/mms/notification(기본 ☑), 발신번호(기본 ☐, 켜면 마스킹 옵션 `010-****-1234`), 앱 이름·패키지(알림일 때 기본 ☑)
- 원문 포함 (기본 ☐)
- 이벤트 발생 시각으로 쓸 필드 선택: 날짜·시간 필드 중 하나 또는 "수신 시각"

### ⑦ 미리보기

- 샘플에 규칙을 적용한 결과를 **표(필드명 · 값)** 와 **실제 전송 JSON** 두 가지로 보여준다.
- 매칭 실패 시 어느 줄에서 막혔는지 표시한다 (줄 단위로 부분 매칭을 시도해 첫 실패 줄을 강조).
- `샘플로 테스트 전송` 버튼: 실제 큐에 `trigger: "manual"`로 넣고 즉시 전송.
- 저장 시 규칙 ID는 UUID, `type` 슬러그는 이름에서 자동 생성(수정 가능, 영문 snake_case).

---

## 4. 데이터 모델

### 4.1 규칙 (저장: 앱 내부 JSON 파일 `rules.json` 또는 SQLite 테이블)

```json
{
  "id": "8d0c7c1e-…",
  "name": "KB 신용카드 승인",
  "type": "kb_card_approval",
  "enabled": true,
  "source": {
    "kind": "sms",
    "senders": ["15881688"]
  },
  "sample": "KB국민카드6005\n승인\n83,600 원(일시불)\n…",
  "template": "KB국민카드{card_last4}\n{status}\n{amount:number} 원({payment_type})\n{merchant}\n고객명 {_}\n승인시각 {approved_at:datetime(MM/dd HH:mm)}\n누적 {cumulative:number}원",
  "fields": [
    { "key": "card_last4", "label": "카드끝자리", "type": "text" },
    { "key": "status", "label": "상태", "type": "text",
      "map": { "승인": "approved", "승인취소": "cancelled", "취소": "cancelled", "거절": "declined" } },
    { "key": "amount", "label": "금액", "type": "number" },
    { "key": "payment_type", "label": "결제방식", "type": "text" },
    { "key": "merchant", "label": "가맹점", "type": "text" },
    { "key": "approved_at", "label": "승인시각", "type": "datetime", "format": "MM/dd HH:mm" },
    { "key": "cumulative", "label": "누적금액", "type": "number" }
  ],
  "match": {
    "mode": "full",
    "include": [],
    "exclude": ["인증번호"],
    "rawOnly": false,
    "reportMismatch": true,
    "maskPatterns": ["(?m)^고객명 .*$"]
  },
  "output": {
    "fields": ["card_last4", "status", "amount", "payment_type", "merchant", "approved_at", "cumulative"],
    "occurredAtField": "approved_at",
    "meta": { "receivedAt": true, "channel": true, "sender": false, "senderMask": true, "app": false },
    "includeRaw": false
  },
  "createdAt": "2026-09-17T10:00:00+09:00",
  "updatedAt": "2026-09-17T10:00:00+09:00"
}
```

앱 알림 규칙의 `source` 예:

```json
{ "kind": "notification", "packages": ["com.kakao.talk"], "events": ["posted"], "text": "title_and_text" }
```

### 4.2 전송 이벤트 (기존 봉투 유지)

```json
{
  "schema": "phone_event/1",
  "event_id": "0f8e2c1a-…",
  "source": "rule",
  "type": "kb_card_approval",
  "trigger": "event",
  "occurred_at": "2026-09-14T21:38:00+09:00",
  "collected_at": "2026-09-14T21:38:07.412+09:00",
  "device": { "id": "galaxy-zflip7", "model": "SM-F766N", "app_version": "0.2.0" },
  "data": {
    "rule_id": "8d0c7c1e-…",
    "rule_name": "KB 신용카드 승인",
    "channel": "sms",
    "received_at": "2026-09-14T21:38:07+09:00",
    "fields": {
      "card_last4": "6005",
      "status": "approved",
      "amount": 83600,
      "payment_type": "일시불",
      "merchant": "제주화로집 신논현점",
      "approved_at": "2026-09-14T21:38:00+09:00",
      "cumulative": 5520828
    }
  }
}
```

형식 불일치 이벤트: `"type": "match_error"`, `data`에 `rule_id`, `rule_name`, `rule_type`, `channel`, `raw`(마스킹 적용), `failed_line`(첫 실패 줄 번호).

---

## 5. 처리 흐름

```
SmsReceiver / MmsScanner / CollectorHostService.onNotificationPosted
        │  (InputMessage: channel, sender or package, title, text, receivedAt, dedupKey)
        ▼
RuleEngine.process(input)
        │  활성 규칙 중 source.kind 일치 → 발신번호/앱 필터 → include/exclude 키워드
        │  → 템플릿 매칭 → 필드 변환 → output 선택 → PhoneEvent 생성
        ▼
EventQueue.enqueue(event, dedupKey = "<inputDedupKey>:<ruleId>")
        ▼
SendScheduler.sendNow()
```

- 한 메시지가 여러 규칙에 매칭되면 규칙마다 이벤트를 만든다 (규칙 ID가 dedup 키에 포함되므로 중복 아님).
- 리시버는 약 10초 안에 끝나야 하므로 기존처럼 `goAsync()` + 백그라운드 스레드에서 처리하고, 네트워크 전송은 WorkManager에 맡긴다.
- 규칙 목록은 메모리에 캐시하고, 저장·수정 시 캐시를 갱신한다. 컴파일된 정규식도 캐시한다.

---

## 6. 기본 프리셋

앱 최초 실행 시 규칙이 하나도 없으면 아래 프리셋을 **꺼진 상태**로 1개 추가한다 (사용자가 확인 후 켬).

- 이름: `KB 신용카드 승인`, 출처: 문자, 발신번호 필터 없음, 템플릿·필드는 4.1 예시와 동일

---

## 7. 권한

| 출처 | 권한 |
| --- | --- |
| 문자 | `RECEIVE_SMS`, `READ_SMS` (런타임) |
| 앱 알림, LMS 상시 감시 | 알림 접근 (설정 화면 이동) |
| 공통 | `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `QUERY_ALL_PACKAGES` 대신 `<queries>`에 LAUNCHER 인텐트 선언 (앱 목록용) |

- 규칙을 켤 때 필요한 권한이 없으면 그 자리에서 요청한다.
- 포그라운드 서비스(`dataSync`)는 쓰지 않는다 (Android 15 하루 6시간 제한). 상주는 NotificationListenerService가 맡는다.

---

## 8. 테스트 (JVM 단위 테스트, 필수)

템플릿 엔진은 Android 의존성 없이 순수 Kotlin으로 작성하고 아래를 통과시킨다.

1. 4.1 샘플 문자 → 7개 필드 값 정확히 추출 (`amount=83600`, `approved_at=2026-09-14T21:38:00+09:00`, `cumulative=5520828`, `status=approved`)
   - `DateTimeFormatter.ISO_OFFSET_DATE_TIME`은 초가 0이어도 `:00`을 적는다. 값은 `21:38:00`이 맞다.
2. 맨 앞 `[Web발신]` 줄이 있어도 매칭
3. 연도 경계: `12/31 23:59` 승인 + `2027-01-01 00:01` 수신 → 2026년
4. `승인취소` + `(03개월)` → `status=cancelled`, `payment_type=03개월`
5. 금액 줄이 없는 문자 → 매칭 실패, `failed_line` 반환
6. `인증번호` 포함 문자 → exclude로 무시
7. `{_}` 필드(고객명)는 결과에 포함되지 않음
8. 누적 줄 공백 차이(`누적 5,520,828 원`)도 매칭
9. 잘못된 템플릿(필드 키 중복, 인접 자리표시자) → 컴파일 오류
10. 원문 전송 시 마스킹 패턴 적용 (`김*만` 미포함)

---

## 9. 완료 기준

- [ ] 탭 3개(규칙 / 기록 / 설정)로 화면 분리
- [ ] 문자·앱 알림 출처로 마법사를 통해 규칙 생성·수정·복제·삭제
- [ ] 샘플에서 텍스트 선택으로 필드 지정 + 템플릿 직접 편집
- [ ] 미리보기에서 필드 표와 실제 JSON 확인, 테스트 전송
- [ ] 기록 탭에서 이벤트별 실제 전송 JSON 확인
- [ ] 기존 카드 SMS 수집기·알림 규칙 코드 제거, 프리셋으로 대체
- [ ] 8장 단위 테스트 전부 통과
- [ ] `./gradlew assembleDebug` 성공, 경고 외 오류 없음

## 10. 2차 범위 (이번에는 구현하지 않음)

- 출처 추가: 통화 기록, 위치(장소 도착·이탈, 주기), 기기 상태(배터리·네트워크), 건강(Health Connect), 앱 사용 시간
- 주기 전송 트리거 (WorkManager 15분 이상)
- 서버에서 규칙 내려받기(원격 설정)
