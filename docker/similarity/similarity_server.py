from flask import Flask, request, jsonify
from prometheus_flask_exporter import PrometheusMetrics
import itertools, os, types, sys

dist = types.ModuleType("torch.distributed")
dist.is_available = lambda: False
sys.modules["torch.distributed"] = dist
sys.modules["torch.distributed.rpc"] = types.ModuleType("torch.distributed.rpc")
sys.modules["torch.distributed.tensor"] = types.ModuleType("torch.distributed.tensor")

from sentence_transformers import CrossEncoder

app = Flask(__name__)
metrics = PrometheusMetrics(app)

model = CrossEncoder(
    os.getenv("MODEL_PATH", "/app/model"),
    tokenizer_args={"use_fast": False}
)


def batched(seq, size: int = 64):
    for i in range(0, len(seq), size):
        yield seq[i : i + size]


@app.route("/similarity", methods=["POST"])
def similarity():
    data = request.get_json()

    if isinstance(data, list):
        pairs = [
            (d.get("left"), d.get("right"))
            for d in data
            if d.get("left") and d.get("right")
        ]
        if not pairs:
            return jsonify([]), 200

        scores = list(
            itertools.chain.from_iterable(
                model.predict(chunk).tolist() for chunk in batched(pairs)
            )
        )
        return jsonify(scores), 200

    left, right = data.get("left"), data.get("right")

    if isinstance(left, list) and isinstance(right, list) and len(left) == len(right):
        scores = list(
            itertools.chain.from_iterable(
                model.predict(chunk).tolist()
                for chunk in batched(list(zip(left, right)))
            )
        )
        return jsonify(scores), 200

    if isinstance(left, str) and isinstance(right, str):
        score = float(model.predict([(left, right)])[0])
        return jsonify(score=round(score, 6)), 200

    return jsonify([]), 200


@app.route("/health", methods=["GET"])
def health():
    return "OK", 200
