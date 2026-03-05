# B02 文档解析分块入库 MVP 交付说明

## 目标

完成知识中台最小可运行闭环：

- 文档内容解析（text/markdown/html）
- 可配置分块（chunk size / overlap）
- 文档与分块持久化（JSONL）
- 基础检索接口（词法检索）

## 交付内容

目录：

- `knowledge-workflow-service/`

关键文件：

- `knowledge-workflow-service/app/main.py`
- `knowledge-workflow-service/app/routers/knowledge.py`
- `knowledge-workflow-service/app/services/parser.py`
- `knowledge-workflow-service/app/services/chunker.py`
- `knowledge-workflow-service/app/services/pipeline.py`
- `knowledge-workflow-service/app/services/store.py`
- `knowledge-workflow-service/app/services/search.py`
- `knowledge-workflow-service/scripts/b02_smoke.sh`
- `scripts/b02_smoke.sh`

## 接口

- `GET /health`
- `POST /api/v1/knowledge/ingest`
- `GET /api/v1/knowledge/documents`
- `GET /api/v1/knowledge/documents/{document_id}/chunks`
- `POST /api/v1/knowledge/search`

## 本地启动

```bash
cd knowledge-workflow-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8091 --reload
```

## 冒烟

```bash
./scripts/b02_smoke.sh
```

或在仓库根目录执行：

```bash
./scripts/b02_smoke.sh
```

## 说明

1. B02 采用 JSONL 落盘是为了先打通 ingest 闭环，后续 B03/B04 将演进为向量索引与混合检索。
2. 当前检索为词法检索（MVP），下一阶段引入 embedding + BM25 + rerank。
