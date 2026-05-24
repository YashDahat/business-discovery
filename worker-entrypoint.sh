#!/usr/bin/env bash
set -euo pipefail

echo "========================================"
echo "WORKER STARTED"
echo "========================================"
echo "TASK_ID        : ${TASK_ID:-<not set>}"
echo "BRIEF_ID       : ${BRIEF_ID:-<not set>}"
echo "BUSINESS_ID    : ${BUSINESS_ID:-<not set>}"
echo "ATTEMPT_NUMBER : ${ATTEMPT_NUMBER:-<not set>}"
echo "GITHUB_BRANCH  : ${GITHUB_BRANCH:-<not set>}"
echo "GITHUB_REPO_URL: ${GITHUB_REPO_URL:-<not set>}"
echo "DB_URL         : ${SPRING_DATASOURCE_URL:-<not set>}"
echo "TASK_DESC      : ${TASK_DESC:-<not set>}"
echo "========================================"

echo "[dummy-worker] Simulating work for 15 seconds..."
sleep 15

echo "[dummy-worker] Done — exiting 0"
exit 0
