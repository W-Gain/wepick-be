# 1. 프로젝트 개요 (Project Overview)

> 2025-12 이전 프로젝트 분석 기록이며 목표 설계가 아닙니다. 목표 구현 설계는 팀 내부 설계 문서에서 관리합니다.

## 1.1. 프로젝트 목적

이 프로젝트는 커뮤니티 서비스를 제공하기 위한 백엔드 애플리케이션입니다. 사용자는 게시글을 작성, 조회, 수정, 삭제할 수 있으며, 댓글과 '좋아요' 기능을 통해 상호작용할 수 있습니다. 또한, 이미지 업로드 및 관리를 지원합니다.

## 1.2. 주요 아키텍처

- **언어 및 프레임워크**: Java 21, Spring Boot 3.x
- **데이터베이스**: Spring Data JPA를 통한 RDBMS 사용 (H2 또는 MySQL/PostgreSQL 등)
- **아키텍처 스타일**: 계층형 아키텍처 (Layered Architecture) 및 도메인 주도 설계 (Domain-Driven Design)
    - `global`: 인증, 예외 처리, 설정 등 프로젝트 전반에 적용되는 공통 기능
    - `domain`: 각 비즈니스 도메인(`user`, `post`, `image` 등)을 패키지로 분리하여 관리
        - `controller`: API 엔드포인트 정의
        - `service`: 비즈니스 로직 처리
        - `repository`: 데이터베이스 연동
        - `entity`: 데이터베이스 테이블과 매핑되는 객체
        - `dto`: 데이터 전송 객체 (Data Transfer Object)
- **인증**: Spring Security 표준 필터 체인을 사용하지 않고, `SessionAuthFilter` 와 `AuthInterceptor` 를 이용한 독자적인 세션 기반 인증 시스템을 구현했습니다.
- **클라우드 서비스**: AWS S3를 이미지 저장소로 활용합니다 (`spring-cloud-aws`).

## 1.3. 기술 스택

| 구분 | 기술 | 버전 | 비고 |
| --- | --- | --- | --- |
| Language | Java | 21 | |
| Framework | Spring Boot | 3.x | |
| Database | Spring Data JPA | | H2 (메모리), MySQL/PostgreSQL |
| Cloud | Spring Cloud AWS | | S3 for image storage |
| Build Tool | Gradle | | |
