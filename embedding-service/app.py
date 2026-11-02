import os
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

MODEL_NAME = os.getenv("EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")
QUERY_PREFIX = os.getenv("QUERY_PREFIX", "为这个句子生成表示以用于检索相关文章：")
MAX_BATCH = int(os.getenv("MAX_BATCH", "64"))

app = FastAPI(title="zs-embedding-service")
_model = None


def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        # HF_ENDPOINT=https://hf-mirror.com 时 huggingface_hub 走镜像下载
        _model = SentenceTransformer(MODEL_NAME, device="cpu")
        _model.eval()
    return _model


class EncodeRequest(BaseModel):
    texts: List[str] = Field(..., min_length=1, max_length=MAX_BATCH)
    # query 会加 BGE 检索指令前缀；document 不加
    type: str = "document"


class EncodeResponse(BaseModel):
    vectors: List[List[float]]
    dim: int
    model: str


@app.on_event("startup")
def startup() -> None:
    get_model()


@app.get("/health")
def health():
    model = get_model()
    return {
        "status": "UP",
        "model": MODEL_NAME,
        "dim": model.get_sentence_embedding_dimension(),
    }


@app.post("/encode", response_model=EncodeResponse)
def encode(request: EncodeRequest):
    texts = [text.strip() for text in request.texts]
    if any(not text for text in texts):
        raise HTTPException(status_code=400, detail="texts contains blank string")
    if request.type == "query":
        texts = [QUERY_PREFIX + text for text in texts]
    vectors = get_model().encode(
        texts,
        batch_size=32,
        normalize_embeddings=True,
        convert_to_numpy=True,
        show_progress_bar=False,
    )
    return EncodeResponse(
        vectors=vectors.tolist(),
        dim=int(vectors.shape[1]),
        model=MODEL_NAME,
    )
