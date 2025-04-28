from flask import Flask, request, jsonify
from sentence_transformers import CrossEncoder

app = Flask(__name__)
model = CrossEncoder("cross-encoder/stsb-roberta-base")

@app.route("/similarity", methods=["POST"])
def similarity():
    data = request.get_json()
    left = data.get("left")
    right = data.get("right")

    if not left or not right:
        return jsonify(score=0.0)

    score = model.predict([(left, right)])[0]

    return jsonify(score=round(float(score), 6))

@app.route("/health", methods=["GET"])
def health():
    return "OK", 200

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
