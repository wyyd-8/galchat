from pathlib import Path

import jieba
import torch
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoModelForSequenceClassification, AutoTokenizer


class PredictRequest(BaseModel):
    context: str
    text: str


class CompletenessClassifier:
    def __init__(self, model_path: str, device: str) -> None:
        self.device = device
        self.tokenizer = AutoTokenizer.from_pretrained(model_path)
        self.model = AutoModelForSequenceClassification.from_pretrained(model_path, num_labels=2)
        self.model.to(self.device)
        self.model.eval()

        # Rule set kept consistent with current project logic in main.py.
        self.incomplete_endings = [
            "和", "与", "而且", "但是", "然后", "因为", "所以", "把", "被", "在", "从", "关于"
        ]
        self.complete_endings = ["了", "吗", "呢", "吧", "啊", "。", "！", "？", "!", "?"]

    def rule_based_check(self, text: str):
        if not text:
            return False

        last_char = text[-1]
        if last_char in self.complete_endings:
            return True

        words = list(jieba.cut(text))
        last_word = words[-1] if words else ""

        if last_word in self.incomplete_endings or last_char in self.incomplete_endings:
            return False

        return None

    def predict(self, user_text: str, context: str = "", threshold: float = 0.6) -> bool:
        rule_result = self.rule_based_check(user_text)
        if rule_result is not None:
            return bool(rule_result)

        if context:
            inputs = self.tokenizer(
                context,
                user_text,
                return_tensors="pt",
                truncation=True,
                max_length=128,
                padding=True,
            ).to(self.device)
        else:
            inputs = self.tokenizer(
                user_text,
                return_tensors="pt",
                truncation=True,
                max_length=128,
                padding=True,
            ).to(self.device)

        with torch.no_grad():
            outputs = self.model(**inputs)
            probs = torch.nn.functional.softmax(outputs.logits, dim=-1)
            prob_complete = probs[0][1].item()

        return prob_complete >= threshold


app = FastAPI(title="Completeness API")

_model_path = str(Path(__file__).resolve().parent / "bert_model")
_device = "cuda" if torch.cuda.is_available() else "cpu"
classifier = CompletenessClassifier(model_path=_model_path, device=_device)


@app.post("/predict", response_model=bool)
def predict(req: PredictRequest) -> bool:
    return classifier.predict(user_text=req.text, context=req.context)

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="localhost", port=8081)
