# 이미지 API

[API 명세 목차](README.md) · [응답 모델](models.md)

<a id="image-01"></a>

## IMAGE-01 프로필 이미지 업로드

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/images/profile` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Multipart | file | binary | 필수 | 이미지 파일 1개 |

`Content-Type: multipart/form-data`. 파일 제한과 오류는 아래 공통 규칙을 따릅니다.

### 응답

`data`: [ImageUpload](models.md#imageupload).

```json
{
  "message": "image_uploaded",
  "data": {
    "imageId": 3,
    "key": "profile/example.png",
    "url": "/uploads/profile/example.png"
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |

<a id="image-02"></a>

## IMAGE-02 게시글 이미지 업로드

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/images/posts` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Multipart | files | binary[] | 필수 | 같은 필드명으로 이미지 1~5개 전송 |

`Content-Type: multipart/form-data`. 업로드 결과의 imageId를 게시글 작성·수정의 imageIds에 전달합니다.

### 응답

`data`: [ImageUploadList](models.md#imageuploadlist).

```json
{
  "message": "images_uploaded",
  "data": [
    {
      "imageId": 3,
      "key": "post/example.png",
      "url": "/uploads/post/example.png"
    }
  ],
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 404 | NOT_FOUND | 빈 파일 목록 |
| 400 | TOO_MANY_IMAGES | 파일 5개 초과 |

## 업로드 규칙

| 항목 | 값 |
| --- | --- |
| 형식 | JPEG, PNG, GIF, WebP; MIME과 파일 시그니처 검사 |
| 파일 크기 | 기본 5MB (설정으로 변경 가능) |
| 요청 총 크기 | 기본 25MB (설정으로 변경 가능) |
| 저장 경로 | profile/{UUID}.확장자 또는 post/{UUID}.확장자 |
| URL | 기본 /uploads/ 접두사; 환경 설정에 따라 변경 |

| HTTP | message | 조건 |
| --- | --- | --- |
| 413 | file_size_exceeded | Multipart 파일 크기 한도 초과 |
| 413 | request_size_exceeded | Multipart 요청 총 크기 한도 초과 |

서비스의 빈 파일·형식·시그니처·크기 검사 실패는 IllegalArgumentException이며 공통 응답으로 매핑되지 않습니다. 누락된 Multipart 필드와 저장소 오류도 이 명세의 공통 오류 형식을 보장하지 않습니다.

```http
POST /api/images/posts
Content-Type: multipart/form-data; boundary=example

--example
Content-Disposition: form-data; name="files"; filename="photo.png"
Content-Type: image/png

<파일 바이너리>
--example--
```
