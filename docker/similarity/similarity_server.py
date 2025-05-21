from flask import Flask, request, jsonify
from sentence_transformers import CrossEncoder

app = Flask(__name__)
model = CrossEncoder("cross-encoder/stsb-roberta-base")

@app.route("/similarity", methods=["POST"])
def similarity():
    data = request.get_json()

    if isinstance(data, list):
        pairs = [(d.get("left"), d.get("right")) for d in data
                 if d.get("left") and d.get("right")]
        if not pairs:
            return jsonify([]), 200
        scores = model.predict(pairs).tolist()
        return jsonify(scores), 200

    left, right = data.get("left"), data.get("right")
    if isinstance(left, list) and isinstance(right, list) and len(left) == len(right):
        pairs  = list(zip(left, right))
        scores = model.predict(pairs).tolist()
        return jsonify(scores), 200

    if isinstance(left, str) and isinstance(right, str):
        score = float(model.predict([(left, right)])[0])
        return jsonify(score=round(score, 6)), 200

    return jsonify([]), 200


@app.route("/health", methods=["GET"])
def health():
    return "OK", 200

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
