#!/usr/bin/env bash
# Docker Compose test environment helper. Integration tests connect to these services;
# they never start or stop containers themselves.
#
#   scripts/test-env.sh up        start postgres + redis and wait until healthy
#   scripts/test-env.sh it        up, then ./gradlew integrationTest   (default)
#   scripts/test-env.sh all       up, then ./gradlew test integrationTest
#   scripts/test-env.sh status    docker compose ps
#   scripts/test-env.sh logs [svc] recent logs
#   scripts/test-env.sh down      stop containers (data volumes are kept)
#
# Wiping data (docker compose down -v) is deliberately NOT here: run it yourself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ensure_env() {
  if [[ ! -f .env ]]; then
    cp .env.example .env
    echo "test-env: created .env from .env.example"
  fi
}

up() {
  ensure_env
  echo "test-env: starting postgres + redis ..."
  docker compose up -d --wait --wait-timeout 120 postgres redis
  docker compose ps
}

gradle() {
  if [[ -x ./gradlew ]]; then ./gradlew "$@"; else ./gradlew.bat "$@"; fi
}

cmd="${1:-it}"
shift || true
case "$cmd" in
  up) up ;;
  it) up; gradle integrationTest "$@" ;;
  all) up; gradle test integrationTest "$@" ;;
  status) docker compose ps ;;
  logs) docker compose logs --tail 200 "$@" ;;
  down) docker compose down ;;
  -h|--help|help) sed -n '2,13p' "$0" ;;
  *) echo "unknown command: $cmd (try --help)" >&2; exit 64 ;;
esac
