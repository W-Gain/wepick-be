# 게시글·좋아요 API

[API 명세 목차](README.md) · [응답 모델](models.md)

<a id="post-01"></a>

## POST-01 게시글 목록 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/posts` | 공개 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Query | lastPostId | integer(int64) | 선택 | 이 ID보다 작은 게시글 조회; 생략 시 첫 페이지 |

정상 상태 게시글을 ID 내림차순으로 최대 5개 반환합니다. 마지막 응답의 lastPostId를 다음 요청에 전달합니다. 빈 목록의 lastPostId는 null입니다.

### 응답

`data`: [PostPage](models.md#postpage).

```json
{
  "message": "Posts retrieved successfully",
  "data": {
    "posts": [
      {
        "postId": 10,
        "images": [
          {
            "imageId": 3,
            "imageUrl": "/uploads/post/example.png"
          }
        ],
        "title": "오늘의 이야기",
        "content": "의견을 남겨주세요.",
        "author": {
          "nickname": "위픽",
          "profileImageUrl": null
        },
        "createdAt": "2026-09-07T12:00:00",
        "likeCount": 0,
        "commentCount": 0,
        "viewCount": 0
      }
    ],
    "hasNext": false,
    "lastPostId": 10
  },
  "error": null
}
```

<a id="post-02"></a>

## POST-02 게시글 상세 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/posts/{postId}` | 선택 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |

조회할 때마다 조회수를 1 증가시킵니다. 비로그인 상태의 isAuthor·isLiked는 false입니다.

### 응답

`data`: [PostDetail](models.md#postdetail).

```json
{
  "message": "Posts retrieved successfully",
  "data": {
    "postId": 10,
    "images": [
      {
        "imageId": 3,
        "imageUrl": "/uploads/post/example.png"
      }
    ],
    "title": "오늘의 이야기",
    "content": "의견을 남겨주세요.",
    "author": {
      "nickname": "위픽",
      "profileImageUrl": null
    },
    "createdAt": "2026-09-07T12:00:00",
    "likeCount": 0,
    "commentCount": 0,
    "viewCount": 0,
    "isAuthor": false,
    "isLiked": false
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 404 | NOT_FOUND | 게시글이 없거나 삭제 상태 |

<a id="post-03"></a>

## POST-03 게시글 작성

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/posts` | 로그인 | 201 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | title | string | 필수 | 1~26자, 공백만 입력 불가 |
| Body | content | string | 필수 | 1~5000자, 공백만 입력 불가 |
| Body | imageIds | integer(int64)[] | 선택 | 등록된 이미지 ID; null·빈 배열이면 첨부 없음 |

```json
{
  "title": "오늘의 이야기",
  "content": "의견을 남겨주세요.",
  "imageIds": [
    3
  ]
}
```

생성 응답의 images는 매퍼에서 설정하지 않아 null입니다. 첨부 결과는 조회 API로 확인합니다. imageIds 개수에 대한 별도 5개 제한은 없습니다.

### 응답

`data`: [Post](models.md#post).

```json
{
  "message": "Post created",
  "data": {
    "postId": 10,
    "images": null,
    "title": "오늘의 이야기",
    "content": "의견을 남겨주세요.",
    "author": {
      "nickname": "위픽",
      "profileImageUrl": null
    },
    "createdAt": "2026-09-07T12:00:00",
    "likeCount": 0,
    "commentCount": 0,
    "viewCount": 0
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | NOT_FOUND | 이미지 없음 |

<a id="post-04"></a>

## POST-04 게시글 수정

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/posts/{postId}` | 로그인·작성자 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |
| Body | title | string | 필수 | 제목 전체 교체 |
| Body | content | string | 필수 | 내용 전체 교체 |
| Body | imageIds | integer(int64)[] | 선택 | 첨부 전체 교체; null·생략·빈 배열이면 모두 제거 |

```json
{
  "title": "수정한 제목",
  "content": "수정한 내용",
  "imageIds": []
}
```

title·content를 생략하면 null로 대입합니다. DTO의 길이·빈 값 검증이 실행되지 않아 잘못된 입력의 공통 오류 응답은 보장되지 않습니다.

### 응답

`data`: null.

```json
{
  "message": "Post updated",
  "data": null,
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 404 | NOT_FOUND | 게시글 또는 이미지 없음 |
| 403 | ACCESS_DENIED | 작성자 아님 |

<a id="post-05"></a>

## POST-05 게시글 삭제

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| DELETE | `/api/posts/{postId}` | 로그인·작성자 | 204 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |

게시글을 삭제 상태로 변경합니다.

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 404 | NOT_FOUND | 게시글 없음 |
| 403 | ACCESS_DENIED | 작성자 아님 |

<a id="like-01"></a>

## LIKE-01 좋아요 등록

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/posts/{postId}/like` | 로그인 | 204 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | NOT_FOUND | 게시글 없음 |
| 409 | DUPLICATE_LIKE | 이미 좋아요 등록 |

<a id="like-02"></a>

## LIKE-02 좋아요 취소

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| DELETE | `/api/posts/{postId}/like` | 로그인 | 204 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 404 | NOT_FOUND | 본인의 좋아요 기록 없음 |

