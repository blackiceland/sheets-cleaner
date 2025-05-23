FROM python:3.10-slim AS wheels
WORKDIR /deps
COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --prefer-binary \
        --extra-index-url https://download.pytorch.org/whl/cpu \
        torch==2.1.0+cpu torchvision==0.16.0+cpu torchaudio==2.1.0+cpu \
        -r requirements.txt

FROM wheels AS model
ENV HF_HUB_OFFLINE=0
RUN --mount=type=cache,target=/root/.cache/huggingface \
    python - <<'PY'
from huggingface_hub import snapshot_download
snapshot_download(
    "cross-encoder/stsb-roberta-base",
    repo_type="model",
    local_dir="/model",
    local_dir_use_symlinks=False,
    resume_download=False
)
PY

FROM python:3.10-slim
WORKDIR /app
COPY --from=wheels /usr/local/lib/python3.10/site-packages /usr/local/lib/python3.10/site-packages
COPY --from=model /model /app/model
COPY similarity_server.py .
ENV MODEL_PATH=/app/model HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 PYTHONUNBUFFERED=1
EXPOSE 5000
HEALTHCHECK CMD curl -fs http://localhost:5000/health || exit 1
CMD ["python", "similarity_server.py"]
