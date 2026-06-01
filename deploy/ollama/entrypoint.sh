#!/bin/sh
set -eu

ollama serve &
pid="$!"

cleanup() {
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

trap cleanup INT TERM

until ollama list >/dev/null 2>&1; do
    sleep 1
done

if ! ollama show qwen3-embedding:4b >/dev/null 2>&1; then
    ollama pull qwen3-embedding:4b || echo "Unable to pull qwen3-embedding:4b automatically; Ollama is still running."
fi

wait "$pid"
