# embedding-service

独立 Python FastAPI 微服务，加载 `BAAI/bge-small-zh-v1.5`，为 Java 侧提供批量向量编码。
在线搜索和离线索引共用同一个接口，LLM 不参与本服务。

## 启动

### 方式 A：本地 Python

```bash
# 国内网络建议走 HF 镜像
export HF_ENDPOINT=https://hf-mirror.com          # Windows PowerShell: $env:HF_ENDPOINT="https://hf-mirror.com"

pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8090
```

### 方式 B：Docker（限制 512MB）

```bash
# 在项目根目录执行
docker compose build embedding
docker compose up -d embedding
curl http://127.0.0.1:8090/health
```

`docker-compose.yml` 中 `embedding.mem_limit=512m`；模型缓存挂载到 `zs-hf-cache`，首次启动需下载约 95MB 模型。若 512MB 下 OOM，将 `mem_limit` 调到 768m 并记录原因。

首次启动会下载约 95 MB 模型到 `~/.cache/huggingface`（Docker 为 `zs-hf-cache`）。

## 接口

### GET /health

```json
{"status": "UP", "model": "BAAI/bge-small-zh-v1.5", "dim": 512}
```

### POST /encode

请求：

```json
{
  "texts": ["西湖附近的双人火锅", "静安寺日料"],
  "type": "query"
}
```

- `texts`：1–64 条，单条建议不超过 512 字符。
- `type`：`query` 时自动加 BGE 检索指令前缀；`document`（默认）不加。

响应：

```json
{
  "vectors": [[0.01, -0.02, "..."], ["..."]],
  "dim": 512,
  "model": "BAAI/bge-small-zh-v1.5"
}
```

向量已 L2 归一化，可直接用于余弦相似度。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMBEDDING_MODEL` | `BAAI/bge-small-zh-v1.5` | 模型名或本地路径 |
| `HF_ENDPOINT` | 无 | 设为 `https://hf-mirror.com` 使用镜像 |
| `QUERY_PREFIX` | `为这个句子生成表示以用于检索相关文章：` | BGE 查询指令 |
| `MAX_BATCH` | `64` | 单次最大批量 |
