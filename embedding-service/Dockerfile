FROM python:3.11-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    HF_ENDPOINT=https://hf-mirror.com \
    EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5

COPY requirements.txt .
# 显式安装 CPU torch，避免 sentence-transformers 拉取 CUDA 依赖导致镜像数 GB
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.2.2 \
    && pip install --no-cache-dir -r requirements.txt

COPY app.py .

EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
