import os

import torch
from fastapi import FastAPI
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer


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
    def __init__(self, model_path: str, device: str) -> None:
        self.device = device
        self.tokenizer = AutoTokenizer.from_pretrained(model_path)
        self.model = AutoModelForSequenceClassification.from_pretrained(model_path)
        self.model.to(self.device)
        self.model.eval()

    def rerank(self, query: str, documents: list[str], top_n: int = 3) -> list[RerankResult]:
        if not query or not documents:
            return []

        queries = [query] * len(documents)
        passages = [document or "" for document in documents]
        inputs = self.tokenizer(
            queries,
            passages,
            return_tensors="pt",
            padding=True,
            truncation=True,
            max_length=512,
        ).to(self.device)

        with torch.no_grad():
            outputs = self.model(**inputs)
            scores = outputs.logits.view(-1).float().cpu().tolist()

        ranked = sorted(
            [
                RerankResult(index=index, score=float(score))
                for index, score in enumerate(scores)
            ],
            key=lambda item: item.score,
            reverse=True,
        )

        return ranked[: min(top_n, len(ranked))]


app = FastAPI(title="Reranker API")

_default_model_path = "BAAI/bge-reranker-v2-m3"
_model_path = os.getenv("RERANKER_MODEL_PATH", str(_default_model_path))
_device = "cuda" if torch.cuda.is_available() else "cpu"
reranker = LocalReranker(model_path=_model_path, device=_device)


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

    uvicorn.run(
        app,
        host=os.getenv("RERANKER_HOST", "0.0.0.0"),
        port=int(os.getenv("RERANKER_PORT", "8082")),
    )
