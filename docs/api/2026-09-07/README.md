# API 명세서 — 2026-09-07 구현 기준

상태: Draft · 작성일: 2026-09-11 · 기준: BE `9611c9816a7a79b9cc2369b2126ec43a217b3cb0`

코드에 구현된 30개 API의 요청·응답 명세입니다. 예시 값은 설명용이며 HTTP 실행 검증은 하지 않았습니다.

## API 목록

| ID | 기능 | 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- | --- | --- |
| [AUTH-01](auth.md#auth-01) | 로그인 | POST | `/api/auth` | 공개 | 200 |
| [AUTH-02](auth.md#auth-02) | 로그아웃 | DELETE | `/api/auth` | 공개 | 204 |
| [USER-01](users.md#user-01) | 회원가입 | POST | `/api/users` | 공개 | 201 |
| [USER-02](users.md#user-02) | 내 정보 조회 | GET | `/api/users/me` | 로그인 | 200 |
| [USER-03](users.md#user-03) | 회원 조회 | GET | `/api/users/{userId}` | 공개 | 200 |
| [USER-04](users.md#user-04) | 내 정보 수정 | PATCH | `/api/users/me` | 로그인 | 200 |
| [USER-05](users.md#user-05) | 프로필 이미지 수정 | PATCH | `/api/users/me/profile-image` | 로그인 | 200 |
| [USER-06](users.md#user-06) | 닉네임 수정 | PATCH | `/api/users/me/nickname` | 로그인 | 200 |
| [USER-07](users.md#user-07) | 비밀번호 변경 | PATCH | `/api/users/me/password` | 로그인 | 204 |
| [USER-08](users.md#user-08) | 회원 탈퇴 | DELETE | `/api/users/me` | 로그인 | 204 |
| [USER-09](users.md#user-09) | 이메일 중복 확인 | POST | `/api/users/check-email` | 공개 | 200 |
| [USER-10](users.md#user-10) | 닉네임 중복 확인 | POST | `/api/users/check-nickname` | 공개 | 200 |
| [TOPIC-01](topics.md#topic-01) | 오늘의 토픽 조회 | GET | `/api/topics/today` | 선택 | 200 |
| [TOPIC-02](topics.md#topic-02) | 토픽 목록 조회 | GET | `/api/topics` | 공개 | 200 |
| [TOPIC-03](topics.md#topic-03) | 투표 | POST | `/api/topics/{topicId}/vote` | 로그인 | 200 |
| [TOPIC-04](topics.md#topic-04) | 토픽 생성 | POST | `/api/topics` | 로그인 | 201 |
| [TOPIC-05](topics.md#topic-05) | 토픽 수정 | PATCH | `/api/topics/{topicId}` | 로그인 | 200 |
| [POST-01](posts.md#post-01) | 게시글 목록 조회 | GET | `/api/posts` | 공개 | 200 |
| [POST-02](posts.md#post-02) | 게시글 상세 조회 | GET | `/api/posts/{postId}` | 선택 | 200 |
| [POST-03](posts.md#post-03) | 게시글 작성 | POST | `/api/posts` | 로그인 | 201 |
| [POST-04](posts.md#post-04) | 게시글 수정 | PATCH | `/api/posts/{postId}` | 로그인·작성자 | 200 |
| [POST-05](posts.md#post-05) | 게시글 삭제 | DELETE | `/api/posts/{postId}` | 로그인·작성자 | 204 |
| [LIKE-01](posts.md#like-01) | 좋아요 등록 | POST | `/api/posts/{postId}/like` | 로그인 | 204 |
| [LIKE-02](posts.md#like-02) | 좋아요 취소 | DELETE | `/api/posts/{postId}/like` | 로그인 | 204 |
| [COMMENT-01](comments.md#comment-01) | 댓글 목록 조회 | GET | `/api/posts/{postId}/comments` | 선택 | 200 |
| [COMMENT-02](comments.md#comment-02) | 댓글 작성 | POST | `/api/posts/{postId}/comments` | 로그인 | 201 |
| [COMMENT-03](comments.md#comment-03) | 댓글 수정 | PATCH | `/api/posts/{postId}/comments/{commentId}` | 로그인·작성자 | 204 |
| [COMMENT-04](comments.md#comment-04) | 댓글 삭제 | DELETE | `/api/posts/{postId}/comments/{commentId}` | 로그인·작성자 | 204 |
| [IMAGE-01](images.md#image-01) | 프로필 이미지 업로드 | POST | `/api/images/profile` | 로그인 | 200 |
| [IMAGE-02](images.md#image-02) | 게시글 이미지 업로드 | POST | `/api/images/posts` | 로그인 | 200 |

## 공통 요청

| 항목 | 규칙 |
| --- | --- |
| 경로 | 프록시 기준 /api 포함. BE 직접 호출 시 /api 제외 |
| 본문 | application/json; 이미지 업로드는 multipart/form-data |
| 인증 | 로그인 응답으로 받은 세션 쿠키를 이후 요청에 전송 |
| 공개 | 로그인 없이 호출 가능 |
| 선택 | 로그인 없이 호출 가능, 로그인하면 본인 관련 필드 반영 |
| 로그인 | 세션의 userId 필요. 작성자 표시는 소유자 검사도 수행 |
| 날짜 | YYYY-MM-DD |
| 날짜·시각 | LocalDateTime, 예: 2026-09-07T12:00:00 |

필수 여부는 정상 요청에 필요한 값입니다. 별도 설명이 있는 API는 누락·잘못된 값에 대한 검증을 수행하지 않습니다. 비밀번호 특수문자는 `!@#$%^&*()_+-={}[]:";'<>?,./` 중 하나를 포함해야 합니다.

## 공통 응답

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| message | string | 성공 메시지 또는 오류 코드. 대소문자·공백을 그대로 사용 |
| data | object / array / integer / null | API별 응답 데이터 |
| error | object 또는 null | 성공 시 null, 처리된 오류는 reason 포함 |
| error.reason | string | 오류 설명 |

204 응답에는 본문이 없습니다. [응답 모델 표](models.md)에서 중첩 필드를 확인할 수 있습니다.

```json
{
  "message": "UNAUTHORIZED",
  "data": null,
  "error": {
    "reason": "인증이 필요합니다."
  }
}
```

## 오류 코드

| HTTP | message | error.reason |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증이 필요합니다. |
| 401 | INVALID_CREDENTIALS | 인증 정보가 올바르지 않습니다. |
| 401 | USER_NOT_FOUND | 사용자를 찾을 수 없습니다. |
| 401 | PASSWORD_MISMATCH | 비밀번호가 일치하지 않습니다. |
| 404 | NOT_FOUND | 요청하신 리소스를 찾을 수 없습니다. |
| 403 | ACCESS_DENIED | 접근이 권한이 없습니다. |
| 409 | DUPLICATE_EMAIL | 이미 사용 중인 이메일입니다. |
| 409 | DUPLICATE_NICKNAME | 이미 사용 중인 닉네임입니다. |
| 400 | INCORRECT_OLD_PASSWORD | 기존 비밀번호가 올바르지 않습니다. |
| 400 | PASSWORD_NOT_CHANGED | 새 비밀번호가 기존 비밀번호와 동일합니다. |
| 400 | VALIDATION_ERROR | 요청 데이터가 올바르지 않습니다. |
| 404 | TOPIC_NOT_FOUND | 오늘의 토픽을 찾을 수 없습니다. |
| 404 | OPTION_NOT_FOUND | 존재하지 않는 선택지입니다. |
| 400 | TOPIC_MISMATCH | 해당 토픽에 속한 옵션이 아닙니다. |
| 409 | DUPLICATE_VOTE | 이미 해당 토픽에 투표했습니다. |
| 409 | DUPLICATE_TOPIC_DATE | 해당 날짜에 이미 등록된 토픽이 있습니다. |
| 409 | DUPLICATE_LIKE | 이미 좋아요를 눌렀습니다. |
| 400 | TOO_MANY_IMAGES | 이미지는 최대 5장까지 업로드할 수 있습니다. |
| 400 | validation_error | 유효하지 않은 입력 값입니다. |
| 413 | file_size_exceeded | 파일 크기가 최대 허용 크기(5MB)를 초과했습니다. 5MB 이하의 파일만 업로드할 수 있습니다. |
| 413 | request_size_exceeded | 요청 전체 크기가 최대 허용 크기(25MB)를 초과했습니다. 여러 파일의 총 용량을 25MB 이하로 줄여주세요. |

`VALIDATION_ERROR`와 `validation_error`는 서로 다른 코드입니다. JSON 파싱·타입 변환·필수 Multipart 누락·처리되지 않은 예외에는 위 공통 형식이 보장되지 않습니다.

## 제품 정책과의 차이

| 항목 | 이 명세의 구현 | 목표 정책 |
| --- | --- | --- |
| 투표 인증 | 로그인 필수 | 익명 투표 허용 |
| 투표 가능 날짜 | 오늘의 토픽만 | 지난 Pick도 허용 |

## 코드 근거

[Controller·DTO·Service·Mapper](https://github.com/W-Gain/wepick-be/tree/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/) · [기존 API 목록](../implementation-inventory-2026-09-07.md)
