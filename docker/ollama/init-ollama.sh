#!/bin/sh
set -eu

OLLAMA_MODEL="${OLLAMA_MODEL:-qwen3-embedding:latest}"

ollama serve &
OLLAMA_PID=$!

until ollama list >/dev/null 2>&1; do
  sleep 2
done

if ! ollama list | grep -q "^${OLLAMA_MODEL}[[:space:]]"; then
  ollama pull "${OLLAMA_MODEL}"
fi

wait "${OLLAMA_PID}"
