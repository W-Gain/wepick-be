# Wepick Backend

## 저장소 역할과 제품 설계

이 저장소는 **서버·데이터·상세 API**를 관리합니다. 제품·정책·ERD·화면 정의서·와이어프레임·공통 API 설계의 기준은 [wepick-product](https://github.com/W-Gain/wepick-product)입니다. [문서 관리 규칙](https://github.com/W-Gain/wepick-product/blob/main/docs/guides/repository-and-document-guide.md)을 따르며 설계 원본을 복사하지 않습니다.

아래 구현 설명은 기존 구현에 관한 기록이며 최신 제품 요구사항을 대신하지 않습니다. 현재 동작은 코드·검증 결과로 확인하고, 목표와의 차이는 [Product 전환 작업](https://github.com/W-Gain/wepick-product/blob/main/docs/plans/documentation-backlog.md)에 연결합니다.

Wepick의 투표·커뮤니티·세션 인증 API입니다. 현재 운영 목표는 단일 Docker host에서 frontend, backend, MySQL, Caddy를 함께 실행하는 구조입니다.

## Current runtime model

```text
Browser
  └─ Caddy
      ├─ /api/*     → Spring Boot backend
      ├─ /uploads/* → local upload volume (read-only)
      └─ /*         → Express frontend

Backend
  ├─ MySQL (application data + JDBC session)
  └─ ImageStorage → LocalImageStorage → /data/uploads
```

Frontend는 `multipart/form-data`로 backend에 이미지를 전송합니다. Browser가 S3에 직접 업로드하거나 Presigned URL을 받지 않습니다.

## Image storage contract

`ImageService`는 저장소 구현이 아닌 `ImageStorage` 인터페이스에 의존합니다.

```text
ImageService → ImageStorage
                 └─ LocalImageStorage (current)
```

현재 public URL은 `/uploads/{profile|post}/{uuid}.{extension}`이고, production에서는 Caddy가 같은 Docker volume을 read-only로 제공합니다.

### Upload endpoints

```text
POST /images/profile
  multipart/form-data: file

POST /images/posts
  multipart/form-data: files (maximum 5)
```

Backend validates file size, declared content type, and file signature. The current maximum is 5MB per file and 25MB per request.

## Runtime environment variables

Spring Boot reads standard environment variables. Docker Compose injects the database values in production.

```env
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/wepick
SPRING_DATASOURCE_USERNAME=wepick
SPRING_DATASOURCE_PASSWORD=replace-with-real-secret

SESSION_COOKIE_SECURE=true
SESSION_COOKIE_SAME_SITE=lax
CORS_ALLOWED_ORIGINS=https://wepick.example.com

IMAGE_STORAGE_LOCAL_ROOT=/data/uploads
IMAGE_PUBLIC_PREFIX=/uploads
APP_IMAGE_MAX_FILE_SIZE=5MB
APP_IMAGE_MAX_REQUEST_SIZE=25MB
APP_IMAGE_MAX_FILE_SIZE_BYTES=5242880
```

Do not commit production secrets. The host runtime file is managed by `wepick-infra` at `/etc/wepick/prod.env`.

## Local verification

```bash
./gradlew test --no-daemon
docker build -t wepick-be:local -f dockerfile .
```

A MySQL-backed smoke environment must verify health, login, image upload, image delivery through Caddy, and post creation with uploaded image IDs.

## Deployment ownership

- `wepick-be`: tests and immutable application image production
- `wepick-fe`: tests and immutable application image production
- `wepick-infra`: Compose, Caddy, runtime environment contract, host deployment and rollback

Existing AWS Terraform and deployment documents are legacy reference material only. They are not required to run the current backend image.

## Tech stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot |
| Database | MySQL 8 |
| Session | spring-session-jdbc |
| Image storage | Local Docker volume via `ImageStorage` |
| Container | Docker |
| CI/CD target | GitHub Actions + GHCR + host Compose |

## 문서

- [2026-09-07 API 구현 분석](docs/api/implementation-inventory-2026-09-07.md) — 코드 기반 30개 엔드포인트·인증·입출력 원본

- [제품·공통 설계](https://github.com/W-Gain/wepick-product)
- [내부 아키텍처](docs/architecture/BACKEND_ARCHITECTURE.md)
- [코딩 규칙](docs/architecture/CODING_CONVENTIONS.md)
- [기존 Topic API 및 목표와의 차이](docs/api/topic_api.md)
- [작업 지침](AGENTS.md)

상세 API·생성 OpenAPI는 BE 소유입니다. Controller·DTO 기반 명세를 갱신하며 Product에 생성 명세 사본을 만들지 않습니다.
