# 회원 API

[API 명세 목차](README.md) · [응답 모델](models.md)

<a id="user-01"></a>

## USER-01 회원가입

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/users` | 공개 | 201 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | email | string | 필수 | 이메일 형식, 1~247자, 공백만 입력 불가 |
| Body | password | string | 필수 | 8~20자, 대문자·소문자·숫자·특수문자 포함 |
| Body | password2 | string | 필수 | password와 동일 |
| Body | nickname | string | 필수 | 1~30자, 공백 포함 불가 |
| Body | profileImageId | integer(int64) | 선택 | null이면 프로필 이미지 없음 |

```json
{
  "email": "user@example.com",
  "password": "Example1!",
  "password2": "Example1!",
  "nickname": "위픽",
  "profileImageId": null
}
```

### 응답

`data`: null.

```json
{
  "message": "Registration successful",
  "data": null,
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |
| 401 | PASSWORD_MISMATCH | 비밀번호 확인 불일치 |
| 409 | DUPLICATE_EMAIL | 이메일 중복 |
| 409 | DUPLICATE_NICKNAME | 닉네임 중복 |
| 404 | NOT_FOUND | 지정한 이미지 없음 |

<a id="user-02"></a>

## USER-02 내 정보 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/users/me` | 로그인 | 200 |

요청 매개변수와 본문 없음.

### 응답

`data`: [User](models.md#user).

```json
{
  "message": "get_user_success",
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
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |

<a id="user-03"></a>

## USER-03 회원 조회

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/users/{userId}` | 공개 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Path | userId | integer(int64) | 필수 | 대상 ID |

이메일도 응답에 포함됩니다.

### 응답

`data`: [User](models.md#user).

```json
{
  "message": "get_user_success",
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
| 401 | USER_NOT_FOUND | 사용자 없음 |

<a id="user-04"></a>

## USER-04 내 정보 수정

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/users/me` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | nickname | string | 선택 | null·생략 시 유지; 최대 30자, 공백 불가 |
| Body | profileImageId | integer(int64) | 선택 | null·생략 시 기존 이미지 제거 |

```json
{
  "nickname": "위픽",
  "profileImageId": null
}
```

### 응답

`data`: [User](models.md#user).

```json
{
  "message": "update_user_success",
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
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 409 | DUPLICATE_NICKNAME | 닉네임 중복 |
| 404 | NOT_FOUND | 이미지 없음 |

<a id="user-05"></a>

## USER-05 프로필 이미지 수정

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/users/me/profile-image` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | profileImageId | integer(int64) | 선택 | null·생략 시 제거 |

```json
{
  "profileImageId": 3
}
```

### 응답

`data`: [User](models.md#user).

```json
{
  "message": "update_profile_image_success",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "profileImageUrl": "/uploads/profile/example.png",
    "nickname": "위픽"
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 404 | NOT_FOUND | 이미지 없음 |

<a id="user-06"></a>

## USER-06 닉네임 수정

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/users/me/nickname` | 로그인 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | nickname | string | 필수 | null·빈 값·공백만 입력 불가 |

```json
{
  "nickname": "위픽"
}
```

이 API에는 회원가입의 30자 제한·문자열 내부 공백 검증이 적용되지 않습니다.

### 응답

`data`: [User](models.md#user).

```json
{
  "message": "update_nickname_success",
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
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 400 | VALIDATION_ERROR | 닉네임이 null 또는 blank |
| 409 | DUPLICATE_NICKNAME | 닉네임 중복 |

<a id="user-07"></a>

## USER-07 비밀번호 변경

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| PATCH | `/api/users/me/password` | 로그인 | 204 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | oldPassword | string | 필수 | 기존 비밀번호 |
| Body | newPassword | string | 필수 | 8~20자, 대문자·소문자·숫자·특수문자 포함 |
| Body | newPassword2 | string | 필수 | newPassword와 동일 |

```json
{
  "oldPassword": "Example1!",
  "newPassword": "Example2!",
  "newPassword2": "Example2!"
}
```

변경 후 세션을 무효화하고 `JSESSIONID` 쿠키를 만료시킵니다. oldPassword·newPassword2에는 필드 검증 어노테이션이 없습니다.

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |
| 401 | USER_NOT_FOUND | 사용자 없음 |
| 400 | INCORRECT_OLD_PASSWORD | 기존 비밀번호 불일치 |
| 400 | PASSWORD_NOT_CHANGED | 기존 비밀번호와 동일 |
| 401 | PASSWORD_MISMATCH | 새 비밀번호 확인 불일치 |

<a id="user-08"></a>

## USER-08 회원 탈퇴

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| DELETE | `/api/users/me` | 로그인 | 204 |

요청 매개변수와 본문 없음.

사용자를 탈퇴 상태로 변경하고 삭제 시각을 기록합니다. 이메일에 `_deleted`를 붙이고 세션을 무효화합니다.

### 응답

본문 없음.

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 로그인 세션 없음 |
| 401 | USER_NOT_FOUND | 사용자 없음 |

<a id="user-09"></a>

## USER-09 이메일 중복 확인

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/users/check-email` | 공개 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | email | string | 필수 | 이메일 형식, 1~247자, 공백만 입력 불가 |

```json
{
  "email": "user@example.com"
}
```

`isExisted: true`이면 이미 사용 중입니다. message는 중복 여부와 관계없이 동일합니다.

### 응답

`data`: boolean 필드 `isExisted`를 가진 객체.

```json
{
  "message": "email_available",
  "data": {
    "isExisted": false
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |

<a id="user-10"></a>

## USER-10 닉네임 중복 확인

| 메서드 | 경로 | 인증 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/users/check-nickname` | 공개 | 200 |

### 요청

| 위치 | 필드 | 타입 | 필수 | 조건 |
| --- | --- | --- | --- | --- |
| Body | nickname | string | 필수 | 1~30자, 공백 불가 |

```json
{
  "nickname": "위픽"
}
```

`isExisted: true`이면 이미 사용 중입니다.

### 응답

`data`: boolean 필드 `isExisted`를 가진 객체.

```json
{
  "message": "nickname_available",
  "data": {
    "isExisted": false
  },
  "error": null
}
```

### 오류

| HTTP | message | 발생 조건 |
| --- | --- | --- |
| 400 | validation_error | 요청 필드의 검증 조건 위반 |

