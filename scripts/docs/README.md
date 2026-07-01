# 문서 사이트 (MkDocs)

`docs/` 아래 markdown을 MkDocs Material로 묶어 정적 사이트로 만든다. 폐쇄망 self-host가 기본 시나리오다.

## 준비

```bash
pip install -r scripts/docs/requirements.txt
```

Python 3.9+ 필요. 가상환경을 쓰려면:

```bash
python -m venv .venv && source .venv/Scripts/activate   # Windows Git Bash
pip install -r scripts/docs/requirements.txt
```

## 미리보기

```bash
python -m mkdocs serve        # http://127.0.0.1:8000, 저장 시 자동 리로드
```

## 빌드 (정적 산출물)

```bash
python -m mkdocs build --strict   # → site/
```

`--strict`는 nav 누락이나 깨진 내부 링크를 빌드 실패로 잡는다. CI에 걸어두면 문서 링크가 썩는 걸 막는다.

## 폐쇄망 배포

인터넷 되는 곳에서 wheel을 미리 받아 옮긴다.

```bash
# 외부망
pip download -r scripts/docs/requirements.txt -d wheels/
# 사내망
pip install --no-index --find-links wheels/ -r scripts/docs/requirements.txt
python -m mkdocs build
```

`site/`를 nginx·Apache 같은 정적 서버나 컨테이너로 서빙하면 끝. 별도 런타임이 필요 없다.

## 생성 문서

`docs/generated/` 아래는 코드에서 뽑아낸 문서다. `.claude/agents/`의 문서 생성 서브에이전트가 채운다. 사용법은 [docs/generated/index.md](../../docs/generated/index.md) 참고.
