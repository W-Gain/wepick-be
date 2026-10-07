# 댓글 API

[API 명세 목차](README.md) · [응답 모델](models.md)

<a id="comment-01"></a>

## COMMENT-01 댓글 목록 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/posts/{postId}/comments` | 선택 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |
| Query | lastCommentId | integer(int64) | 선택 | 이 ID보다 작은 댓글 조회; 생략 시 첫 페이지 |
| Query | size | integer | 선택 | 기본 10, 명시적 최소·최대 검증 없음 |

최신 댓글부터 페이지를 선택한 후 페이지 내부는 ID 오름차순으로 반환합니다. 다음 요청에는 응답의 lastCommentId를 사용합니다. 빈 목록의 커서는 null입니다. 게시글 존재 여부는 별도로 확인하지 않습니다.

### 응답

`data`: [CommentPage](models.md#commentpage).

```json
{
  "message": "Comments retrieved successfully",
  "data": {
    "comments": [
      {
        "commentId": 20,
        "content": "제 생각도 같아요.",
        "author": {
          "nickname": "위픽",
          "profileImageUrl": null
        },
        "isAuthor": true,
        "createdAt": "2026-09-07T12:05:00"
      }
    ],
    "hasNext": false,
    "lastCommentId": 20
  },
  "error": null
}
```

<a id="comment-02"></a>

## COMMENT-02 댓글 작성

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/posts/{postId}/comments` | 로그인 | 201 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |
| Body | content | string | 필수 | 댓글 내용 |

```json
{
  "content": "제 생각도 같아요."
}
```

DTO에 선언된 1~300자·빈 값 검증은 이 요청에서 실행되지 않습니다.

### 응답

`data`: [Comment](models.md#comment).

```json
{
  "message": "Comment created",
  "data": {
    "commentId": 20,
    "content": "제 생각도 같아요.",
    "author": {
      "nickname": "위픽",
      "profileImageUrl": null
    },
    "isAuthor": true,
    "createdAt": "2026-09-07T12:05:00"
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | NOT_FOUND | 게시글 없음 |

<a id="comment-03"></a>

## COMMENT-03 댓글 수정

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/posts/{postId}/comments/{commentId}` | 로그인·작성자 | 204 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |
| Path | commentId | integer(int64) | 필수 | 대상 ID |
| Body | content | string | 필수 | 댓글 내용 |

```json
{
  "content": "의견을 수정합니다."
}
```

postId와 댓글의 소속 게시글을 대조하지 않습니다. DTO의 1~300자·빈 값 검증은 실행되지 않습니다.

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | NOT_FOUND | 댓글 없음 |
| 403 | ACCESS_DENIED | 댓글 작성자 아님 |

<a id="comment-04"></a>

## COMMENT-04 댓글 삭제

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| DELETE | `/api/posts/{postId}/comments/{commentId}` | 로그인·작성자 | 204 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | postId | integer(int64) | 필수 | 대상 ID |
| Path | commentId | integer(int64) | 필수 | 대상 ID |

postId와 댓글의 소속 게시글을 대조하지 않습니다. 댓글을 물리 삭제하고 실제 소속 게시글의 댓글 수를 줄입니다.

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | NOT_FOUND | 댓글 없음 |
| 403 | ACCESS_DENIED | 댓글 작성자 아님 |

