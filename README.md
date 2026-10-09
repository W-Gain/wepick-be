# Wepick Backend

## 저장소 역할과 제품 설계

이 저장소는 **서버 코드·실행·테스트·DB 마이그레이션·생성 OpenAPI**를 관리합니다. 목표 구현 설계는 팀 내부 설계 문서에서 관리합니다.

아래 구현 설명은 기존 구현에 관한 기록이며 최신 제품 요구사항을 대신하지 않습니다. 현재 동작은 코드·검증 결과로 확인하고, 목표와의 차이는 팀 내부 설계 문서 기준으로 확인합니다.

Wepick의 투표·커뮤니티·세션 인증 API입니다. 현재 운영 배포 환경은 없습니다. 목표 런타임은 단일 Docker host에서 frontend, backend, MySQL, Caddy를 함께 실행하는 구조이며, 배포 방식은 Product 전환 계획 8단계에서 정합니다.

## Target runtime model

```text
Browser
  └─ Caddy
      ├─ /api/*     → Spring Boot backend
      ├─ /uploads/* → local upload volume (read-only)
      └─ /*         → frontend static files (Vite dist)

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

Spring Boot reads standard environment variables. 모든 값에 로컬 개발용 기본값이 있으며, 운영에서 반드시 지정할 값은 아래 **필수** 항목입니다.

| 변수 | 구분 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `SPRING_DATASOURCE_URL` | 필수 | `jdbc:mysql://localhost:3306/wepick-be` | MySQL 접속 URL |
| `SPRING_DATASOURCE_USERNAME` | 필수 | `root` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | 필수 | `root` | DB 비밀번호 |
| `SESSION_COOKIE_SECURE` | 필수 (운영) | `false` | HTTPS 운영에서는 `true` |
| `CORS_ALLOWED_ORIGINS` | 선택 | `http://localhost:3000` | 브라우저가 `/api`를 같은 출처로 호출하면 영향 없음 |
| `SESSION_COOKIE_SAME_SITE` | 선택 | `lax` | 같은 출처 구조에서는 기본값 사용 |
| `IMAGE_STORAGE_LOCAL_ROOT` | 선택 | `/data/uploads` | 업로드 저장 경로 |
| `IMAGE_PUBLIC_PREFIX` | 선택 | `/uploads` | 업로드 공개 URL 접두사 |
| `APP_IMAGE_MAX_FILE_SIZE` | 선택 | `5MB` | multipart 파일당 최대 크기 |
| `APP_IMAGE_MAX_REQUEST_SIZE` | 선택 | `25MB` | multipart 요청당 최대 크기 |
| `APP_IMAGE_MAX_FILE_SIZE_BYTES` | 선택 | `5242880` | 이미지 검증용 파일당 최대 바이트. `APP_IMAGE_MAX_FILE_SIZE`와 같은 값을 유지 |

비밀값은 커밋하지 않습니다. 운영 값의 이름과 예시는 `wepick-infra`의 `environments/prod/.env.example`이 관리합니다.

## Local verification

Java 21과 실행 중인 Docker Engine 25 이상(API 1.44)이 필요합니다. Spring Boot가 관리하는 Testcontainers 1.21.3의 Docker 29 호환을 위해 테스트 전용 `docker-java.properties`에서 API 1.44를 지정합니다. 테스트는 Testcontainers가 별도의 빈 MySQL 8 컨테이너와 임의 포트를 만들고 종료 시 정리합니다. 로컬 개발 DB를 사용하지 않습니다. 전체 Spring 컨텍스트를 띄워 Flyway V1 적용, Hibernate `validate`, 기존 회원·토픽·투표 저장, JDBC 세션 저장·principal 조회·속성 삭제를 검증합니다.

```bash
./gradlew test --no-daemon
docker build -t wepick-be:local -f dockerfile .
```

### PIT 변이 테스트

PIT Gradle 플러그인 1.19.0, PIT 1.22.1, JUnit 5 플러그인 1.2.3을 고정해 Java 21에서 `./gradlew pitest`로 실행합니다. 대상 클래스와 JUnit 테스트 클래스는 정확한 전체 클래스 이름(FQCN)으로 지정해야 합니다. 인자가 비어 있거나 컴파일된 클래스를 찾지 못하거나 일치하는 테스트가 없으면 PIT 실행 전에 실패합니다. 와일드카드는 받지 않으며 콤마로 여러 클래스를 지정할 수 있습니다. 중첩·익명 클래스는 바깥 클래스에 자동 포함되지 않으므로, 대상으로 삼으려면 컴파일된 이름(예: `Outer$Inner`)을 직접 추가하고 셸에서 `$`가 확장되지 않도록 인자를 작은따옴표로 감쌉니다.

```bash
./gradlew pitest \
  -PpitestTargetClasses=gguip1.community.global.health.HealthController \
  -PpitestTargetTests=gguip1.community.global.health.HealthControllerTest \
  --no-daemon
```

XML과 HTML 보고서는 `build/reports/pitest/mutations.xml`, `build/reports/pitest/index.html`에 생성됩니다. 이 설정은 기본 점수 임계값을 두지 않으며 지정된 클래스·테스트 범위만 분석합니다.

PIT 실행 전에 전용 `pitestSelectedTest` 태스크가 지정한 테스트만 실행합니다. 일반 `./gradlew test` 태스크는 필터링하지 않으며 전체 회귀 테스트를 독립적으로 실행합니다. 위 `HealthController` 대상은 도구 동작을 확인하는 스모크 범위이며 기능별 커버리지나 품질 기준을 뜻하지 않습니다.

### 빈 MySQL에서 로컬 실행

```bash
docker compose up --build -d
docker compose logs backend
curl http://localhost:8080/actuator/health
docker compose exec mysql mysql -uwepick -pwepick wepick_be \
  -e 'SELECT version, description, success FROM flyway_schema_history;'
docker compose down
```

빈 DB에서는 앱 시작 시 `V1__baseline_current_schema.sql`을 적용한 뒤 Hibernate가 스키마를 검증합니다. `version=1`, `success=1`과 health의 `UP`을 확인합니다. 재시작 시 V1을 다시 실행하지 않습니다. Compose 볼륨은 `down` 후에도 유지됩니다.

다른 로컬 서비스와 포트가 겹치면 별도 Compose 프로젝트와 포트를 지정합니다. 모든 Compose 명령에 같은 프로젝트·환경 값을 사용합니다.

```bash
BE_HTTP_PORT=18081 MYSQL_PORT=13316 docker compose -p wepick-be-local up --build -d
curl http://localhost:18081/actuator/health
BE_HTTP_PORT=18081 MYSQL_PORT=13316 docker compose -p wepick-be-local down
```

### 스키마 기준선

Flyway가 애플리케이션 테이블과 Spring Session JDBC 테이블을 함께 관리합니다. `ddl-auto=validate`, `spring.session.jdbc.initialize-schema=never`를 유지하며, 스키마 변경은 새 버전의 `src/main/resources/db/migration` SQL로 추가합니다. 적용된 V1은 수정하지 않습니다. V1은 현재 이메일·비밀번호 및 게시판 스키마를 보존하며 목표 ERD 변경은 포함하지 않습니다.

기존 Hibernate 관리 DB에는 이 V1을 바로 실행하지 않습니다. `baseline-on-migrate`는 기본값 `false`이며, 이 작업은 빈 DB 경로를 검증합니다. 기존 DB 전환은 백업과 V1 대비 스키마 일치 확인 후 명시적인 Flyway baseline(version 1) 절차를 별도로 수행해야 합니다. 기존 개발 볼륨을 삭제하는 것으로 데이터 전환을 대신하지 않습니다.

### V3 사전 점검

기존 DB에 V3를 적용하기 전에 대상 DB에 대해 읽기 전용 점검을 실행합니다.

```bash
mysql --host=YOUR_HOST --user=YOUR_USER --password YOUR_DATABASE < scripts/v3-preflight.sql
```

`YOUR_HOST`, `YOUR_USER`, `YOUR_DATABASE`는 대상 DB 접속 정보로 바꿉니다.

두 조회 결과가 모두 0건인지 확인한 뒤 적용합니다. 이 점검은 같은 비NULL 토픽 안의 중복 선택지 라벨과, 존재하지 않거나 토픽이 지정되지 않았거나 다른 토픽에 속한 선택지를 가리키는 표를 찾습니다. 토픽이 지정되지 않은 미참조 선택지와 저장된 `vote_count`와 실제 표 수의 차이는 수정하지 않으며 자동 보정하지 않습니다.

마이그레이션에 실패하면 임의로 다시 실행하지 말고 현재 스키마의 부분 적용 객체와 `flyway_schema_history`를 확인합니다. 복구 방법을 결정하기 전에 Flyway `repair`를 실행하지 않습니다.

A MySQL-backed smoke environment must verify health, login, image upload, image delivery through Caddy, and post creation with uploaded image IDs.

## Delivery

- CI: Pull Request와 `main` push에서 GitHub Actions가 `./gradlew test`를 실행합니다.
- 이미지 발행과 배포 파이프라인은 없습니다. 2026-10-07에 GHCR 발행과 기존 AWS 배포 워크플로를 제거했고, 배포 방식은 Product 전환 계획 8단계에서 다시 정합니다.
- `wepick-infra`는 목표 런타임 구성(Compose, Caddy, 환경 계약)을 관리합니다.

## Tech stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot |
| Database | MySQL 8 |
| Session | spring-session-jdbc |
| Image storage | Local Docker volume via `ImageStorage` |
| Container | Docker |
| CI | GitHub Actions (test only) |

## 문서

- [2026-09-07 API 구현 분석](docs/api/implementation-inventory-2026-09-07.md) — 코드 기반 30개 엔드포인트·인증·입출력 원본

- [이전 프로젝트 아키텍처 분석](docs/architecture/BACKEND_ARCHITECTURE.md) — 2025-12 이전 분석 기록, 목표 설계 아님
- [이전 프로젝트 코딩 규칙 분석](docs/architecture/CODING_CONVENTIONS.md) — 2025-12 이전 분석 기록, 목표 설계 아님
- [기존 Topic API 및 목표와의 차이](docs/api/topic_api.md)
- [작업 지침](AGENTS.md)

상세 API·생성 OpenAPI는 BE 소유입니다. Controller·DTO 기반 명세를 코드와 함께 갱신합니다.
