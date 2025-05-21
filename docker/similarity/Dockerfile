FROM python:3.10-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_NUM_WORKERS=4

WORKDIR /app

COPY requirements.txt .
RUN pip install --prefer-binary "numpy<2" && \
    pip install --prefer-binary \
        --index-url https://download.pytorch.org/whl/cpu \
        torch==2.1.0+cpu \
        torchvision==0.16.0+cpu \
        torchaudio==2.1.0+cpu && \
    pip install --prefer-binary -r requirements.txt

RUN python - <<'PY'
from sentence_transformers import CrossEncoder
CrossEncoder("cross-encoder/stsb-roberta-base")
PY

COPY similarity_server.py .

EXPOSE 5000
CMD ["python", "similarity_server.py"]