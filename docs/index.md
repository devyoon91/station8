# Station8 Docs

Station8은 Oracle/MariaDB 환경에서 도는 경량 Java 워크플로우 오케스트레이션 엔진이다. Temporal의 Durable Execution을 벤치마킹하되, 외부 메시지 큐 없이 DB의 `FOR UPDATE SKIP LOCKED`만으로 분산 작업 처리를 보장한다.

이 사이트는 `docs/` 아래 markdown을 MkDocs(Material)로 묶은 문서 포털이다. 손으로 쓴 가이드와, 코드에서 뽑아낸 생성 문서(API/기능/테이블/화면/운영)를 한곳에서 본다.

## 어디부터 볼까

- 처음이면 [Quickstart](QUICKSTART.md) — Docker 한 줄로 띄우고 5분 안에 라인 하나 돌려보기.
- 액티비티를 직접 만들 거면 [How-To](HOWTO.md)와 [플러그인 개발 가이드](PLUGIN_DEVELOPMENT.md).
- 엔진이 어떻게 도는지 궁금하면 [아키텍처](ARCHITECTURE.md)와 [Line Engine Spec](line-engine-spec.md).
- 설계 결정의 배경은 의사결정(ADR) 섹션.

## 메타포

한 번의 액티비티 실행이 하나의 **Station**(역), 그 역들을 잇는 노선이 하나의 **Line**, 역 간 의존성이 **Track**이다. 이 메타포는 클래스·엔티티·테이블명까지 일관되게 적용된다.

| 일반 용어 | Station8 용어 |
|---|---|
| Workflow / DAG | **Line** |
| Node / Step | **Station** |
| Edge / Dependency | **Track** |

## 생성 문서

[생성 문서](generated/index.md) 섹션은 코드에서 자동으로 뽑아낸다. `.claude/agents/`의 문서 생성 서브에이전트가 컨트롤러·엔티티·스키마·뷰를 읽어 API 문서, 기능정의서, 테이블 정의서, 화면정의서, 운영 메뉴얼을 채운다. 생성 방법은 [생성 문서 개요](generated/index.md)를 참고.

## 로컬에서 보기

```bash
pip install -r scripts/docs/requirements.txt
python -m mkdocs serve      # http://127.0.0.1:8000
python -m mkdocs build      # 정적 산출물 → site/
```

폐쇄망에서는 `site/`를 사내 정적 서버나 컨테이너로 서빙하면 된다. 자세한 건 [scripts/docs/README.md](https://github.com/devyoon91/station8/blob/main/scripts/docs/README.md) 참고.
