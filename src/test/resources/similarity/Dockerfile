FROM python:3.10-slim

ENV HF_HOME=/models
WORKDIR /app

COPY .cache/models /models
COPY requirements.txt .
RUN pip install --prefer-binary -r requirements.txt

COPY similarity_server.py .
EXPOSE 5000
CMD ["gunicorn", "--bind", "0.0.0.0:5000", "similarity_server:app"]
