# Topic API 명세서

> 기존 구현 참고 문서 — 최신 제품 요구사항과 차이가 있습니다 (2026-09-07 확인). 아래 로그인 필수·당일만 투표 가능 규칙은 [Product의 익명 투표·지난 Pick 참여 흐름](https://github.com/W-Gain/wepick-product/blob/main/docs/product/core-user-flows-and-mvp-scope.md)과 다릅니다. 후속 전환 설계에서 실제 코드와 대조해야 하며, 이 문서를 목표 정책으로 사용하지 않습니다. 이번 이전에서는 아래 요청·응답과 API 구현을 변경하지 않았습니다.

이 문서는 프론트엔드 연동을 위한 Topic(양자택일 투표) 도메인의 API 사용법을 설명합니다.

---

## 📅 기본 정보
- **Time Zone**: 모든 날짜와 시간은 **Asia/Seoul (KST)** 기준입니다.
- **날짜 포맷**: `YYYY-MM-DD` (예: `2025-12-08`)
- **투표 규칙**: 
    - 하루에 하나의 토픽만 활성화됩니다.
    - 투표는 **해당 토픽의 날짜(`targetDate`) 당일**에만 가능합니다.
    - 지난 날짜의 토픽은 자동으로 "종료" 상태로 간주되며 투표할 수 없습니다.

---

## 1. 오늘의 토픽 조회
매일 자정 갱신되는 오늘의 토픽을 조회합니다. 로그인한 유저는 본인의 투표 여부(`votedOptionId`)를 확인할 수 있습니다.

- **URL**: `GET /api/topics/today`
- **Auth**: 선택 (로그인 시 투표 여부 반환, 비로그인 시 `null`)

### Request
Header에 `JSESSIONID` 쿠키가 있으면 로그인 유저로 처리됩니다.

### Response (Success)
```json
{
  "message": "Today's topic retrieved",
  "data": {
    "topicId": 1,
    "title": "아침형 인간 vs 저녁형 인간",
    "description": "당신의 생산성 스타일은 무엇인가요?",
    "targetDate": "2025-12-08",
    "status": "OPEN",
    "options": [
      {
        "optionId": 10,
        "label": "A",
        "text": "아침형 인간",
        "description": "일찍 일어나는 새가 벌레를 잡는다",
        "voteCount": 150,
        "percent": 60
      },
      {
        "optionId": 11,
        "label": "B",
        "text": "저녁형 인간",
        "description": "밤은 창의성의 시간",
        "voteCount": 100,
        "percent": 40
      }
    ],
    "totalVotes": 250,
    "votedOptionId": 10  // 투표하지 않았거나 비로그인 시 null
  },
  "error": null
}
```

---

## 2. 투표하기
특정 토픽의 옵션(A 또는 B)에 투표합니다.
**주의:** 해당 토픽의 `targetDate`가 **오늘**일 때만 투표가 가능합니다.

- **URL**: `POST /api/topics/{topicId}/vote`
- **Auth**: **필수**

### Request
```json
{
  "optionId": 10
}
```

### Response (Success)
```json
{
  "message": "Vote successful",
  "data": null,
  "error": null
}
```

### Response (Fail)
- **409 Conflict**: 이미 투표한 경우 (`DUPLICATE_VOTE`)
- **404 Not Found**: 오늘 날짜의 토픽이 아닌 경우 (투표 기간 만료)

---

## 3. 토픽 생성 (관리자용)
새로운 토픽을 생성합니다. 
- **제약 사항**: 같은 날짜(`targetDate`)에 이미 토픽이 있으면 생성할 수 없습니다. (1일 1토픽 원칙)
- **Time Zone 주의**: `targetDate`는 KST 기준으로 저장됩니다.

- **URL**: `POST /api/topics`
- **Auth**: **필수**

### Request
```json
{
  "title": "아침형 인간 vs 저녁형 인간",
  "description": "당신의 생활 패턴은?",
  "targetDate": "2025-12-08",
  "status": "OPEN",
  "optionAText": "아침형 인간",
  "optionADescription": "일찍 일어나는 새가 벌레를 잡는다",
  "optionBText": "저녁형 인간",
  "optionBDescription": "밤은 창의성의 시간"
}
```

### Response (Success)
```json
{
  "message": "Topic created",
  "data": 1, // 생성된 topicId
  "error": null
}
```

### Response (Fail - 날짜 중복)
```json
{
  "message": "해당 날짜에 이미 등록된 토픽이 있습니다.",
  "error": {
    "code": "DUPLICATE_TOPIC_DATE",
    "status": 409,
    "message": "해당 날짜에 이미 등록된 토픽이 있습니다."
  }
}
```

---

## 4. 토픽 수정 (관리자용)
기존 토픽의 내용을 수정합니다. 날짜(`targetDate`)를 변경할 경우, 변경하려는 날짜에 이미 토픽이 있다면 실패합니다.

- **URL**: `PATCH /api/topics/{topicId}`
- **Auth**: **필수**

### Request
수정이 필요한 필드만 보내면 됩니다. (전체 필드 선택 가능)
```json
{
  "title": "수정된 제목",
  "optionAText": "수정된 옵션 A 텍스트",
  "optionADescription": "수정된 옵션 A 설명"
}
```

### Response (Success)
```json
{
  "message": "Topic updated",
  "data": null,
  "error": null
}
```

---

## 5. 토픽 아카이브 조회
과거의 토픽 목록을 조회합니다.
- **정렬**: 기본적으로 날짜 내림차순(`targetDate,DESC`)입니다.
- **상태 표시**: 프론트엔드에서는 `targetDate < today`인 경우 "종료됨"으로 표시하면 됩니다.

- **URL**: `GET /api/topics`
- **Query Params**:
    - `page`: 페이지 번호 (0부터 시작, 기본 0)
    - `size`: 페이지 당 개수 (기본 10)

### Request Example
`GET /api/topics?page=0&size=5`

### Response (Success)
```json
{
  "message": "Topic archive retrieved",
  "data": {
    "content": [
      {
        "topicId": 1,
        "title": "아침형 인간 vs 저녁형 인간",
        "targetDate": "2025-12-08",
        "status": "OPEN"  // 실제 투표 가능 여부는 날짜 비교 필요
      },
      // ...
    ],
    "pageable": { ... },
    "totalElements": 10,
    "totalPages": 2,
    "last": false
  },
  "error": null
}
```