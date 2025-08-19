FROM python:3.10-slim AS build
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --upgrade pip \
 && pip install --no-cache-dir -r requirements.txt

RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('cross-encoder/stsb-roberta-base', tokenizer_args={'use_fast': False}).save('/model')"

FROM python:3.10-slim
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /usr/local/lib/python3.10/site-packages /usr/local/lib/python3.10/site-packages
COPY --from=build /usr/local/bin /usr/local/bin
COPY --from=build /model /app/model
COPY similarity_server.py .

ENV MODEL_PATH=/app/model \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    PYTHONUNBUFFERED=1

RUN adduser --disabled-password --gecos '' appuser
USER appuser

EXPOSE 5000
HEALTHCHECK CMD sh -c 'curl -fs http://localhost:$PORT/health || exit 1'
CMD ["sh","-c","gunicorn -w 2 -b 0.0.0.0:$PORT similarity_server:app"]