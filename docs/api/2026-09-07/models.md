# 응답 모델

[API 명세 목차](README.md)

각 표는 공통 응답의 `data` 내부 필드입니다. 배열 항목과 중첩 객체도 같은 이름의 모델을 따릅니다.

<a id="user"></a>

## User

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| userId | integer(int64) | 사용자 ID |
| email | string | 이메일 |
| profileImageUrl | string 또는 null | 프로필 이미지 URL |
| nickname | string | 닉네임 |

<a id="author"></a>

## Author

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| nickname | string | 작성자 닉네임 |
| profileImageUrl | string 또는 null | 프로필 이미지 URL |

<a id="post"></a>

## Post

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| postId | integer(int64) | 게시글 ID |
| images | PostImage[] 또는 null | 조회 시 배열, 생성 응답에서는 null |
| title | string | 제목 |
| content | string | 본문 |
| author | Author | 작성자 |
| createdAt | string(date-time) | 작성 시각 |
| likeCount | integer | 좋아요 수 |
| commentCount | integer | 댓글 수 |
| viewCount | integer | 조회수 |

<a id="postimage"></a>

## PostImage

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| imageId | integer(int64) | 이미지 ID |
| imageUrl | string | 이미지 URL |

<a id="postdetail"></a>

## PostDetail

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| Post의 모든 필드 | — | 게시글 기본 정보 |
| isAuthor | boolean | 요청자가 작성자인지 |
| isLiked | boolean | 요청자가 좋아요를 등록했는지 |

<a id="postpage"></a>

## PostPage

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| posts | Post[] | 게시글 목록 |
| hasNext | boolean | 다음 페이지 존재 여부 |
| lastPostId | integer(int64) 또는 null | 다음 요청 커서 |

<a id="comment"></a>

## Comment

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| commentId | integer(int64) | 댓글 ID |
| content | string | 내용 |
| author | Author | 작성자 |
| isAuthor | boolean | 요청자가 작성자인지 |
| createdAt | string(date-time) | 작성 시각 |

<a id="commentpage"></a>

## CommentPage

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| comments | Comment[] | 댓글 목록 |
| hasNext | boolean | 다음 페이지 존재 여부 |
| lastCommentId | integer(int64) 또는 null | 다음 요청 커서 |

<a id="topic"></a>

## Topic

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| topicId | integer(int64) | 토픽 ID |
| title | string | 제목 |
| description | string 또는 null | 설명 |
| targetDate | string(date) | 노출 날짜 |
| status | string | OPEN 또는 CLOSED |
| options | TopicOption[] | 선택지 |
| totalVotes | integer(int64) | 전체 투표 수 |
| votedOptionId | integer(int64) 또는 null | 본인이 투표한 선택지, 미투표·비로그인은 null |

<a id="topicoption"></a>

## TopicOption

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| optionId | integer(int64) | 선택지 ID |
| label | string | A 또는 B |
| text | string | 선택지 내용 |
| description | string 또는 null | 설명 |
| voteCount | integer(int64) | 득표 수 |
| percent | integer | 득표율 반올림, 전체 0표이면 0 |

<a id="topicpage"></a>

## TopicPage

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| content | TopicSummary[] | 토픽 목록 |
| number | integer | 0부터 시작하는 페이지 번호 |
| size | integer | 페이지 크기 |
| totalElements | integer(int64) | 전체 개수 |
| totalPages | integer | 전체 페이지 수 |
| first / last | boolean | 첫 / 마지막 페이지 여부 |
| numberOfElements | integer | 이번 페이지 개수 |
| empty | boolean | 빈 페이지 여부 |
| pageable / sort | object | Spring Page 직렬화 부가 정보 |

<a id="topicsummary"></a>

## TopicSummary

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| topicId | integer(int64) | 토픽 ID |
| title | string | 제목 |
| targetDate | string(date) | 노출 날짜 |
| status | string | OPEN 또는 CLOSED |

<a id="imageupload"></a>

## ImageUpload

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| imageId | integer(int64) | 등록된 이미지 ID |
| key | string | 저장 키 |
| url | string | 공개 이미지 URL |

<a id="imageuploadlist"></a>

## ImageUploadList

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| data[] | ImageUpload | 각 파일의 업로드 결과 |

