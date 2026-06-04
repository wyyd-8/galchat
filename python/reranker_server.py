import logging
import os
import time

import torch
from fastapi import FastAPI
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer


logger = logging.getLogger("reranker")


class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    top_n: int = Field(default=3, ge=1)


class RerankResult(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: list[RerankResult]


class LocalReranker:
    def __init__(
        self,
        model_path: str,
        device: str,
        max_length: int,
        batch_size: int,
        torch_dtype: str,
    ) -> None:
        self.device = device
        self.max_length = max_length
        self.batch_size = max(1, batch_size)
        self.tokenizer = AutoTokenizer.from_pretrained(model_path, trust_remote_code=True)
        self.model = AutoModelForSequenceClassification.from_pretrained(
            model_path,
            trust_remote_code=True,
            torch_dtype=_torch_dtype(torch_dtype),
        )
        self.model.to(self.device)
        self.model.eval()

    def rerank(self, query: str, documents: list[str], top_n: int = 3) -> list[RerankResult]:
        if not query or not documents:
            return []

        started_at = time.perf_counter()
        scores = []
        for start in range(0, len(documents), self.batch_size):
            batch_documents = documents[start : start + self.batch_size]
            scores.extend(self._score_batch(query, batch_documents))

        ranked = sorted(
            [
                RerankResult(index=index, score=float(score))
                for index, score in enumerate(scores)
            ],
            key=lambda item: item.score,
            reverse=True,
        )

        elapsed_ms = (time.perf_counter() - started_at) * 1000
        logger.info("reranked %d documents in %.1f ms", len(documents), elapsed_ms)
        return ranked[: min(top_n, len(ranked))]

    def _score_batch(self, query: str, documents: list[str]) -> list[float]:
        inputs = self.tokenizer(
            [query] * len(documents),
            [document or "" for document in documents],
            padding=True,
            truncation=True,
            return_tensors="pt",
            max_length=self.max_length,
        ).to(self.device)

        with torch.no_grad():
            outputs = self.model(**inputs, return_dict=True)
            return outputs.logits.view(-1).float().cpu().tolist()


def _torch_dtype(dtype: str):
    match dtype:
        case "auto":
            return "auto"
        case "float16":
            return torch.float16
        case "bfloat16":
            return torch.bfloat16
        case "float32":
            return torch.float32
        case _:
            raise ValueError("RERANKER_TORCH_DTYPE must be auto, float16, bfloat16, or float32")


def _default_device() -> str:
    if torch.cuda.is_available():
        return "cuda"
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


app = FastAPI(title="Reranker API")

_default_model_path = "Alibaba-NLP/gte-multilingual-reranker-base"
_model_path = os.getenv("RERANKER_MODEL_PATH", str(_default_model_path))
_device = os.getenv("RERANKER_DEVICE", _default_device())
_max_length = int(os.getenv("RERANKER_MAX_LENGTH", "8192"))
_batch_size = int(os.getenv("RERANKER_BATCH_SIZE", "4"))
_torch_dtype_name = os.getenv("RERANKER_TORCH_DTYPE", "auto")
reranker = LocalReranker(
    model_path=_model_path,
    device=_device,
    max_length=_max_length,
    batch_size=_batch_size,
    torch_dtype=_torch_dtype_name,
)


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest) -> RerankResponse:
    return RerankResponse(
        results=reranker.rerank(
            query=req.query,
            documents=req.documents,
            top_n=req.top_n,
        )
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="localhost", port=8082)
