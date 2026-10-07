# 인증 API

[API 명세 목차](README.md) · [응답 모델](models.md)

<a id="auth-01"></a>

## AUTH-01 로그인

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/auth` | 공개 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | email | string | 필수 | 등록한 이메일 |
| Body | password | string | 필수 | 비밀번호 |

```json
{
  "email": "user@example.com",
  "password": "Example1!"
}
```

성공하면 세션에 userId를 저장합니다. DTO의 이메일·비밀번호 검증 어노테이션은 이 요청에서 실행되지 않습니다.

### 응답

`data`: [User](models.md#user).

```json
{
  "message": "login_success",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "profileImageUrl": null,
    "nickname": "위픽"
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | INVALID_CREDENTIALS | 이메일이 없거나 비밀번호 불일치 |

<a id="auth-02"></a>

## AUTH-02 로그아웃

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| DELETE | `/api/auth` | 공개 | 204 |

요청 매개변수와 본문 없음.

기존 세션을 무효화하고 `JSESSIONID` 쿠키를 만료시킵니다. 세션이 없어도 204를 반환합니다.

### 응답

본문 없음.

