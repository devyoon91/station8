<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo/station8-dark.svg">
    <img src="assets/logo/station8-light.svg" alt="Station8" width="320">
  </picture>
</p>

# Station8

> 8호선을 옆으로 누이면 ∞ — 무한히 반복되는 자동화. 노드는 노선의 한 **역(Station)** 이 된다.

Oracle/MariaDB 환경에서 동작하는 경량 Java 워크플로우 오케스트레이션 엔진. Temporal의 Durable Execution을 벤치마킹하되 외부 메시지 큐 없이 DB의 `SKIP LOCKED` 만으로 분산 작업 처리를 보장한다.

## 메타포

| 일반 용어 | Station8 용어 | 설명 |
|---|---|---|
| Workflow / DAG | **Line** | 한 줄로 이어지는 노선. 워크플로우 한 단위. |
| Node / Step | **Station** | 노선 위의 한 역. 액티비티 1회 실행 단위. |
| Edge / Dependency | **Track** | 역 간 의존성. FROM 완료 시 TO 활성화. |

본 메타포는 클래스명·엔티티명·테이블명까지 일관되게 적용된다 (예: `LineDefinition`/`LineStation`/`LineTrack`, `U_LINE_DEFINITION`/`U_LINE_STATION`/`U_LINE_TRACK`). 다만 액티비티 자체는 노선의 운영 단위가 아니라 사용자가 작성하는 "한 번의 작업"이므로 일반 용어 `@Activity`/`H_LINE_ACTIVITY_EXECUTION`로 유지한다.

## 핵심 특성

- **Durable Execution** — 모든 액티비티 실행 상태를 DB에 영속화하여 장애 시 중단 지점부터 재개.
- **DB 기반 워커 폴링** — `FOR UPDATE SKIP LOCKED`로 외부 큐 없이 분산 작업 처리.
- **자동 재시도** — Exponential Backoff 기반 재시도 + DLQ.
- **간결한 SDK** — `@LineDefinition`, `@Activity` 어노테이션만으로 라인 정의.
- **운영 UI** — Dashboard / Lines (서브웨이 맵 노선도) / Builder / Schedules / DLQ / Activity Catalog.

## 기술 스택

- Java 21
- Spring Boot 3.4.x
- Oracle / MariaDB / H2 (`DbDialect` 추상화)
- Gradle

## 프로젝트 구조

- `station8-engine` — 엔진 코어. 어노테이션, DAG 인터프리터, 워커 폴링, DLQ.
- `station8-app` — Spring Boot 애플리케이션. 컨트롤러, Mustache 뷰, 비즈니스 액티비티.
- `e2e-tests` — REST Assured 기반 회귀 테스트 (compose 환경에서만 활성).

## 빠른 시작 (Docker)

```bash
git clone git@github.com:devyoon91/station8.git
cd station8
cp .env.example .env                       # (선택) 환경변수 커스터마이즈
docker compose -f docker/docker-compose.yml up --build -d
# http://localhost:8080  → /login (첫 부팅 시 콘솔 로그에서 자동 생성된 admin 비밀번호 확인)
```

`.env.example`에 초기 ADMIN 비밀번호 / DataSource / DLQ 웹훅 등 운영자가 다룰 모든 환경변수가 주석과 함께 정리되어 있다. `STATION8_INITIAL_ADMIN_PASSWORD`를 비워두면 첫 부팅에서 랜덤 비밀번호가 자동 생성되어 콘솔에 1회 출력된다 (#121).

사전 조건(Docker Desktop 24+ 등)과 DB 수동 셋업·트러블슈팅은 [docs/QUICKSTART.md](docs/QUICKSTART.md)에 정리되어 있다.

## 문서

먼저 [AGENTS.md](AGENTS.md)(허브)를 읽고 작업 성격에 맞는 문서를 선택하세요.

- [AGENTS.md](AGENTS.md) — 협업 가이드 (허브)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 모듈 흐름과 런타임 시퀀스
- [docs/QUICKSTART.md](docs/QUICKSTART.md) — 5분 설명
- [docs/line-engine-spec.md](docs/line-engine-spec.md) — 엔진 명세
- [docs/HOWTO.md](docs/HOWTO.md) — 사용자 가이드 (라인 작성)
- [docs/PLUGINS.md](docs/PLUGINS.md) — 플러그인 가이드
- [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md) — DB 규칙
- [docs/ERROR_CODES.md](docs/ERROR_CODES.md) — 에러 코드
- [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) — 협업 규정
- [GitHub Issues](https://github.com/devyoon91/station8/issues) · [Milestones](https://github.com/devyoon91/station8/milestones)

## 코어 인터페이스

특정 DB 기술에 종속되지 않는 코어 계약.

- `@LineDefinition` — 라인 클래스 식별 어노테이션 (선택적 이름).
- `@Activity` — 액티비티 메서드 식별 + 재시도 정책 (`retryCount`, `backoffSeconds`).
- `LineExecutor` — 인스턴스 시작/재개 인터페이스.
- `LineContext` — 실행 맥락. 입력/출력, 시도 횟수, 속성, 체크포인트.
- `TaskExecutor` — 현재 액티비티 실행, 다음 단계 스케줄, 완료/실패 처리.
- `LineWorker` — DB 폴링(SKIP LOCKED) + 스레드 풀 기반 비동기 실행기.
