# sneezecast Backend Guide

백엔드 워크스페이스 엔트리 문서다. 규칙의 정본은 `docs/*.md` 이고, 이 문서는 지도 역할만 한다. 새 규칙은 `docs/` 를 먼저 고친다.

## 먼저 읽는다

1. [docs/modules.md](docs/modules.md) — 모듈 구성과 각 모듈에 넣는 것 / 넣지 않는 것
2. [docs/architecture-guide.md](docs/architecture-guide.md) — 서비스 경계, 계층 흐름, 데이터 흐름, 개인정보 경계
3. [docs/coding-conventions.md](docs/coding-conventions.md) — 네이밍, 예외, 영속성, Feign, 로그

도메인 불변식(보고·집계·자료 부족·운영자 검토·개인정보)은 루트 [`CLAUDE.md`](../CLAUDE.md) 가 정본이다.

## 구성 요약

- Spring Cloud MSA + Hexagonal. Java 21, Spring Boot 3.4.x, Spring Cloud 2024.0.x, 패키지 루트 `com.sneezecast`
- `core`(common · persistence · redis · security) / `cloud`(service-discovery · api-gateway) / `service`(auth · surveillance · batch)
- 흐름: `Controller → WebUseCase → WebFacade → Processor → Port → Adapter`, `Info → Presenter → Response`

## 자주 어기는 것

- **surveillance 에 `member_id` 를 저장하지 않는다.** 보고자는 `reporter_key` 로만 식별한다.
- 공개 API 는 집계·발행된 안내만 읽고, 표본 부족이면 수치 없이 `INSUFFICIENT` 만 내린다.
- JPA 연관관계 어노테이션 금지, 네이티브 쿼리 금지, 응답 ID 는 `String`.
- 외부 I/O 를 트랜잭션 안에서 기다리지 않는다. 모든 외부 호출에 timeout.
- 증상·회원 식별정보·토큰을 로그에 남기지 않는다.

## 명령

아직 Gradle 프로젝트가 없다 (#6 에서 구성). 구성되면 빌드·테스트 명령을 여기에 적는다.

## 파일 인코딩

모든 파일은 UTF-8 (no BOM), LF. Windows 기본 CP949 로 저장하면 한글이 깨진다.
