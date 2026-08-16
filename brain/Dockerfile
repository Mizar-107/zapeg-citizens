FROM python:3.12-alpine

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    CITIZENS_DB_PATH=/data/citizens-brain.sqlite3

RUN addgroup -S citizens \
    && adduser -S -G citizens citizens \
    && mkdir -p /data \
    && chown citizens:citizens /data

WORKDIR /app
COPY --chown=citizens:citizens citizen_brain/ /app/citizen_brain/

USER citizens
EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/healthz', timeout=2).read()"]

CMD ["python", "-m", "citizen_brain"]
