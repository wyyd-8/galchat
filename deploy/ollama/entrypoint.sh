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

if ! ollama show bge-m3 >/dev/null 2>&1; then
    ollama pull bge-m3 || echo "Unable to pull bge-m3 automatically; Ollama is still running."
fi

wait "$pid"
