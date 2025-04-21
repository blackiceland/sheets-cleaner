from flask import Flask, request, jsonify
from sentence_transformers import SentenceTransformer, util

app = Flask(__name__)
model = SentenceTransformer("all-mpnet-base-v2")


@app.route("/similarity", methods=["POST"])
def similarity():
    data = request.get_json()
    left = data.get("left")
    right = data.get("right")

    if not left or not right:
        return jsonify(score=0.0)

    emb1 = model.encode(left, convert_to_tensor=True)
    emb2 = model.encode(right, convert_to_tensor=True)
    score = util.cos_sim(emb1, emb2).item()

    return jsonify(score=round(score, 6))


@app.route("/health", methods=["GET"])
def health():
    return "OK", 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)


