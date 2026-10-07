# 2026-09-07 API 구현 분석

- 상태: Draft — 코드 기반 현황, 목표 API 계약이 아님
- 분석일: 2026-09-07
- BE 기준 커밋: `9611c9816a7a79b9cc2369b2126ec43a217b3cb0`
- 검증: Controller·Service·DTO 정적 대조. HTTP·DB 실행 검증 및 OpenAPI 추출은 하지 않음.

이 문서의 “현재”는 위 분석일·커밋 시점을 뜻합니다. 이후 API 변경은 이 기록에 덮어쓰지 않고 새 날짜의 분석 또는 현행 OpenAPI에 반영합니다. 분석 오류 정정 시 정정일·근거를 남깁니다.

[요청·응답 상세 API 명세서](2026-09-07/README.md)

2026-09-11 정정: `ErrorResponse.reason`에 맞춰 공통 오류 필드명을 수정했습니다.

## 경로와 공통 처리

표의 경로는 **BE 내부 경로**입니다. 운영 Caddy를 통해 호출할 때 `/api`를 앞에 붙입니다. `/api/topics/today` → BE `/topics/today`. `/health`(FE), `/api/health`(BE), `/api/actuator/health`(Actuator)는 서로 구분합니다.

일반 성공 응답은 `{message,data,error:null}`, 명시적 204 응답은 본문이 없습니다. ErrorException 응답은 `{message:"오류코드",data:null,error:{reason:"설명"}}`입니다. 기존 topic_api.md의 일부 오류 예제와 다릅니다. Framework 기본 오류까지 모두 같은 형식이라고 가정하지 않습니다.

`로그인`은 SessionAuthFilter가 채운 userId와 AuthInterceptor의 `@Auth` 검사입니다. `선택`은 로그인 없이 조회 가능하지만 userId가 있으면 응답을 개인화합니다. `공개`는 해당 Controller의 로그인 요구가 없다는 뜻이며 공개 범위가 바람직하다는 승인 의미는 아닙니다.

## 도메인 엔드포인트 30개

| 영역 | 메서드 | BE 경로 | 인증·권한 | 입력 | 성공 응답 |
| --- | --- | --- | --- | --- | --- |
| auth | POST | `/auth` | 공개 | email, password | 200 AuthResponse |
| auth | DELETE | `/auth` | 공개 | 없음 | 204 |
| user | POST | `/users` | 공개 | UserCreateRequest | 201 data null |
| user | GET | `/users/me` | 로그인 | 없음 | 200 UserResponse |
| user | GET | `/users/{userId}` | 공개 | userId | 200 UserResponse |
| user | PATCH | `/users/me` | 로그인 | UserUpdateRequest | 200 UserUpdateResponse |
| user | PATCH | `/users/me/profile-image` | 로그인 | UserProfileImageUpdateRequest | 200 UserUpdateResponse |
| user | PATCH | `/users/me/nickname` | 로그인 | UserNicknameUpdateRequest | 200 UserUpdateResponse |
| user | PATCH | `/users/me/password` | 로그인 | UserPasswordUpdateRequest | 204, 세션 무효화 |
| user | DELETE | `/users/me` | 로그인 | 없음 | 204, User soft-delete·세션 무효화 |
| user | POST | `/users/check-email` | 공개 | email | 200 UserEmailCheckResponse |
| user | POST | `/users/check-nickname` | 공개 | nickname | 200 UserNicknameCheckResponse |
| topic | GET | `/topics/today` | 선택 | 없음 | 200 TopicResponse |
| topic | GET | `/topics` | 공개 | page, size, sort | 200 Page<TopicListResponse> |
| topic | POST | `/topics/{topicId}/vote` | 로그인 | optionId | 200 data null |
| topic | POST | `/topics` | 로그인; ADMIN 검사 없음 | CreateTopicRequest | 201 topicId |
| topic | PATCH | `/topics/{topicId}` | 로그인; ADMIN 검사 없음 | UpdateTopicRequest | 200 data null |
| post | GET | `/posts` | 공개 | lastPostId 선택, 서버 크기 5 | 200 PostPageResponse |
| post | GET | `/posts/{postId}` | 선택 | postId | 200 PostDetailResponse |
| post | POST | `/posts` | 로그인 | title, content, imageIds | 201 PostPageItemResponse |
| post | PATCH | `/posts/{postId}` | 로그인·작성자 | title, content, imageIds | 200 data null |
| post | DELETE | `/posts/{postId}` | 로그인·작성자 | postId | 204 |
| comment | GET | `/posts/{postId}/comments` | 선택 | lastCommentId 선택, size 기본 10 | 200 PostCommentPageResponse |
| comment | POST | `/posts/{postId}/comments` | 로그인 | content | 201 PostCommentPageItemResponse |
| comment | PATCH | `/posts/{postId}/comments/{commentId}` | 로그인·작성자 | content | 204 |
| comment | DELETE | `/posts/{postId}/comments/{commentId}` | 로그인·작성자 | postId, commentId | 204 |
| like | POST | `/posts/{postId}/like` | 로그인 | postId | 204 |
| like | DELETE | `/posts/{postId}/like` | 로그인 | postId | 204 |
| image | POST | `/images/profile` | 로그인 | multipart file | 200 ImageUploadResponse |
| image | POST | `/images/posts` | 로그인 | multipart files, 최대 5개 | 200 List<ImageUploadResponse> |

## Controller와 DTO 원본

- [AuthController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/auth/controller/AuthController.java)
- [ImageController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/image/controller/ImageController.java)
- [PostCommentController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/controller/PostCommentController.java)
- [PostController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/controller/PostController.java)
- [PostLikeController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/controller/PostLikeController.java)
- [TopicController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/controller/TopicController.java)
- [UserController.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/controller/UserController.java)

입출력 필드·타입·검증 어노테이션은 아래 원본을 확인합니다. 모든 DTO 어노테이션이 모든 Controller에서 자동 검증되는 것은 아닙니다. 예를 들어 로그인에는 `@Valid`가 없습니다.

- [AuthRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/auth/dto/AuthRequest.java)
- [AuthResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/auth/dto/AuthResponse.java)
- [ImageResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/image/dto/ImageResponse.java)
- [ImageUploadResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/image/dto/ImageUploadResponse.java)
- [PostCommentRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/request/PostCommentRequest.java)
- [PostCreateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/request/PostCreateRequest.java)
- [PostUpdateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/request/PostUpdateRequest.java)
- [AuthorResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/response/AuthorResponse.java)
- [PostCommentPageItemResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/response/PostCommentPageItemResponse.java)
- [PostCommentPageResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/response/PostCommentPageResponse.java)
- [PostDetailResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/response/PostDetailResponse.java)
- [PostPageItemResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/response/PostPageItemResponse.java)
- [PostPageResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/post/dto/response/PostPageResponse.java)
- [CreateTopicRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/dto/request/CreateTopicRequest.java)
- [UpdateTopicRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/dto/request/UpdateTopicRequest.java)
- [VoteRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/dto/request/VoteRequest.java)
- [TopicListResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/dto/response/TopicListResponse.java)
- [TopicOptionResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/dto/response/TopicOptionResponse.java)
- [TopicResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/topic/dto/response/TopicResponse.java)
- [UserCreateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserCreateRequest.java)
- [UserEmailCheckRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserEmailCheckRequest.java)
- [UserNicknameCheckRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserNicknameCheckRequest.java)
- [UserNicknameUpdateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserNicknameUpdateRequest.java)
- [UserPasswordUpdateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserPasswordUpdateRequest.java)
- [UserProfileImageUpdateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserProfileImageUpdateRequest.java)
- [UserUpdateRequest.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/request/UserUpdateRequest.java)
- [UserEmailCheckResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/response/UserEmailCheckResponse.java)
- [UserNicknameCheckResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/response/UserNicknameCheckResponse.java)
- [UserResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/response/UserResponse.java)
- [UserUpdateResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/domain/user/dto/response/UserUpdateResponse.java)

## 계약 해석 시 주의할 차이

- 오늘 토픽 응답은 options의 voteCount·percent, totalVotes, votedOptionId를 포함합니다. 미투표·비로그인도 집계값을 받습니다.
- 토픽 목록은 targetDate DESC 기본 정렬의 Spring Pageable이며 기본 size는 명시적인 10 고정이 아닙니다. FE 호출 함수가 size=10을 전송합니다. 서비스는 과거 날짜·공개 상태를 필터링하지 않습니다.
- 투표는 LocalDate.now와 targetDate를 비교하며 회원만 가능하고 변경 API는 없습니다. 중복 409, 다른 날짜 404, 잘못된 선택지/주제 결합 오류가 있습니다. OPEN/CLOSED 검사 및 ADMIN 권한은 별도 구현되지 않았습니다.
- 게시글 목록은 lastPostId 커서와 고정 5개, 댓글은 lastCommentId와 기본 10개입니다. 댓글은 최신 묶음을 조회한 뒤 응답 목록을 reverse합니다.
- 회원 조회 UserResponse에는 email이 포함되고 `/users/{userId}`는 공개입니다. 소유자·공개 응답 분리는 후속 검토 대상입니다.
- 이미지 입력은 파일당 기본 5MB, 전체 요청 기본 25MB, posts 최대 5개이며 JPEG·PNG·GIF·WebP의 선언 형식과 signature를 검사합니다. 배포 환경 설정으로 크기가 달라질 수 있습니다.
- 이미지 업로드 응답은 imageId·key·url입니다. 파일 업로드 후 사용자 프로필/게시글 API에 imageId를 연결합니다.
- 카카오 OAuth·익명 식별·의견/공감·내 투표 기록·토픽 단건 상세·검수/예약/숨김 전용 API는 해당 기준 소스의 Controller 목록에서 확인되지 않았습니다.

## 오류·설정과 목표 설계

- [ApiResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/global/response/ApiResponse.java)
- [ErrorResponse.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/global/response/ErrorResponse.java)
- [ErrorCode.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/global/exception/ErrorCode.java)
- [GlobalExceptionHandler.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/global/exception/GlobalExceptionHandler.java)
- [SessionAuthFilter.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/global/filter/SessionAuthFilter.java)
- [AuthInterceptor.java](https://github.com/W-Gain/wepick-be/blob/9611c9816a7a79b9cc2369b2126ec43a217b3cb0/src/main/java/gguip1/community/global/interceptor/AuthInterceptor.java)

Springdoc 의존성은 있지만 이번 문서는 실행한 OpenAPI 사본이 아닙니다. [Product 2026-09-07 분석](https://github.com/W-Gain/wepick-product/blob/main/docs/analysis/2026-09-07/README.md)에서 화면·데이터·목표 차이를 확인합니다. 목표 정책은 Product, 상세 구현 명세의 원본은 BE로 유지합니다.
