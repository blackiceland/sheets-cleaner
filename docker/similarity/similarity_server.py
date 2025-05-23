from flask import Flask, request, jsonify
from sentence_transformers import CrossEncoder
from prometheus_flask_exporter import PrometheusMetrics
import os

app = Flask(__name__)
metrics = PrometheusMetrics(app)
model = CrossEncoder(os.getenv("MODEL_PATH", "/app/model"), tokenizer_args={"use_fast": False})


@app.route("/similarity", methods=["POST"])
def similarity():
    data = request.get_json()
    if isinstance(data, list):
        pairs = [(d.get("left"), d.get("right")) for d in data if d.get("left") and d.get("right")]
        return jsonify(model.predict(pairs).tolist() if pairs else []), 200

    left, right = data.get("left"), data.get("right")
    if isinstance(left, list) and isinstance(right, list) and len(left) == len(right):
        return jsonify(model.predict(list(zip(left, right))).tolist()), 200
    if isinstance(left, str) and isinstance(right, str):
        return jsonify(score=float(model.predict([(left, right)])[0])), 200
    return jsonify([]), 200


@app.route("/health", methods=["GET"])
def health():
    return "OK", 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", 5000)))
