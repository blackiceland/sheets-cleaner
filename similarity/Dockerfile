FROM python:3.10-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_NUM_WORKERS=4

WORKDIR /app

# -------- зависимости --------
COPY requirements.txt .
RUN pip install --prefer-binary "numpy<2" && \
    pip install --prefer-binary \
        --index-url https://download.pytorch.org/whl/cpu \
        torch==2.1.0+cpu \
        torchvision==0.16.0+cpu \
        torchaudio==2.1.0+cpu && \
    pip install --prefer-binary -r requirements.txt

# -------- заранее скачиваем модель (кэш слоя) --------
RUN python - <<'PY'
from sentence_transformers import CrossEncoder
CrossEncoder("cross-encoder/stsb-roberta-base")
PY

# -------- приложение --------
COPY similarity_server.py .
COPY ai_model/ ./ai_model

EXPOSE 5000
CMD ["python", "similarity_server.py"]