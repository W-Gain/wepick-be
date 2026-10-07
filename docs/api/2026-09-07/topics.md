# 토픽·투표 API

[API 명세 목차](README.md) · [응답 모델](models.md)

<a id="topic-01"></a>

## TOPIC-01 오늘의 토픽 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/topics/today` | 선택 | 200 |

요청 매개변수와 본문 없음.

투표 전에도 집계 결과를 제공합니다. 로그인한 사용자의 투표가 있으면 votedOptionId를 반환합니다. OPEN/CLOSED 상태로 조회를 제한하지 않습니다.

### 응답

`data`: [Topic](models.md#topic).

```json
{
  "message": "Today's topic retrieved",
  "data": {
    "topicId": 1,
    "title": "아침에는 무엇을 마시나요?",
    "description": null,
    "targetDate": "2026-09-07",
    "status": "OPEN",
    "options": [
      {
        "optionId": 1,
        "label": "A",
        "text": "커피",
        "description": null,
        "voteCount": 3,
        "percent": 75
      },
      {
        "optionId": 2,
        "label": "B",
        "text": "차",
        "description": null,
        "voteCount": 1,
        "percent": 25
      }
    ],
    "totalVotes": 4,
    "votedOptionId": null
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 404 | TOPIC_NOT_FOUND | 서버 기준 오늘 날짜의 토픽 없음 |

<a id="topic-02"></a>

## TOPIC-02 토픽 목록 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/topics` | 공개 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Query | page | integer | 선택 | 0부터 시작 |
| Query | size | integer | 선택 | 페이지 크기; Spring Pageable 기본값 적용 |
| Query | sort | string | 선택 | 기본 targetDate,DESC |

과거 날짜·상태 필터 없이 전체 토픽을 조회합니다. 예시는 Page의 주요 필드이며 프레임워크 직렬화에 따라 pageable·sort 정보가 추가됩니다.

### 응답

`data`: [TopicPage](models.md#topicpage).

```json
{
  "message": "Topic archive retrieved",
  "data": {
    "content": [
      {
        "topicId": 1,
        "title": "아침에는 무엇을 마시나요?",
        "targetDate": "2026-09-07",
        "status": "OPEN"
      }
    ],
    "number": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true,
    "numberOfElements": 1,
    "empty": false
  },
  "error": null
}
```

<a id="topic-03"></a>

## TOPIC-03 투표

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/topics/{topicId}/vote` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | topicId | integer(int64) | 필수 | 대상 ID |
| Body | optionId | integer(int64) | 필수 | 선택지 ID |

```json
{
  "optionId": 1
}
```

로그인한 사용자만 오늘의 토픽에 한 번 투표할 수 있습니다. OPEN/CLOSED 검사는 없습니다. 투표 변경·취소 API는 없습니다.

### 응답

`data`: null.

```json
{
  "message": "Vote successful",
  "data": null,
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | TOPIC_NOT_FOUND | 토픽이 없거나 오늘 날짜가 아님 |
| 409 | DUPLICATE_VOTE | 이미 해당 토픽에 투표함 |
| 404 | OPTION_NOT_FOUND | 선택지 없음 |
| 400 | TOPIC_MISMATCH | 다른 토픽의 선택지 |

<a id="topic-04"></a>

## TOPIC-04 토픽 생성

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/topics` | 로그인 | 201 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | title | string | 필수 | 토픽 제목; 공백만 입력 불가 |
| Body | description | string | 선택 | 토픽 설명 |
| Body | targetDate | string(date) | 필수 | 노출 날짜 YYYY-MM-DD |
| Body | status | string | 필수 | OPEN 또는 CLOSED |
| Body | optionAText | string | 필수 | A 선택지; 공백만 입력 불가 |
| Body | optionADescription | string | 선택 | A 설명 |
| Body | optionBText | string | 필수 | B 선택지; 공백만 입력 불가 |
| Body | optionBDescription | string | 선택 | B 설명 |

```json
{
  "title": "아침에는 무엇을 마시나요?",
  "description": null,
  "targetDate": "2026-09-07",
  "status": "OPEN",
  "optionAText": "커피",
  "optionADescription": null,
  "optionBText": "차",
  "optionBDescription": null
}
```

로그인 여부만 확인하며 관리자 권한 검사는 없습니다.

### 응답

`data`: integer(int64), 생성된 topicId.

```json
{
  "message": "Topic created",
  "data": 1,
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |
| 409 | DUPLICATE_TOPIC_DATE | 해당 날짜에 토픽 존재 |

<a id="topic-05"></a>

## TOPIC-05 토픽 수정

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/topics/{topicId}` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | topicId | integer(int64) | 필수 | 대상 ID |
| Body | title | string | 선택 | 토픽 제목; null·생략 시 유지 |
| Body | description | string | 선택 | 토픽 설명; null·생략 시 유지 |
| Body | targetDate | string(date) | 선택 | 노출 날짜 YYYY-MM-DD; null·생략 시 유지 |
| Body | status | string | 선택 | OPEN 또는 CLOSED; null·생략 시 유지 |
| Body | optionAText | string | 선택 | A 선택지; null·생략 시 유지 |
| Body | optionADescription | string | 선택 | A 설명; null·생략 시 유지 |
| Body | optionBText | string | 선택 | B 선택지; null·생략 시 유지 |
| Body | optionBDescription | string | 선택 | B 설명; null·생략 시 유지 |

```json
{
  "title": "아침 음료 선택",
  "status": "CLOSED"
}
```

관리자 권한 검사는 없습니다. 문자열에 빈 문자열을 보낼 수 있으며 null로 기존 설명을 지울 수는 없습니다.

### 응답

`data`: null.

```json
{
  "message": "Topic updated",
  "data": null,
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 404 | TOPIC_NOT_FOUND | 토픽 없음 |
| 409 | DUPLICATE_TOPIC_DATE | 변경할 날짜에 다른 토픽 존재 |

