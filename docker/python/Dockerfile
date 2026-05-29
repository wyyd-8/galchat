FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY docker/python/requirements.txt /tmp/requirements.txt
RUN pip install -r /tmp/requirements.txt

COPY python/ /app/

EXPOSE 8081 8082

