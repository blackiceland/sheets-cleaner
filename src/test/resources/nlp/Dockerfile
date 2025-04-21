FROM python:3.10-slim

WORKDIR /app
COPY similarity_server.py .
COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('all-mpnet-base-v2')"

EXPOSE 5000

CMD ["python", "similarity_server.py"]
