#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [[ "${1:-}" != "--live" ]]; then
  echo '用法：./scripts/check-ai.sh --live（读取本地 .env，发送一次合成任务，产生标准 API 费用）' >&2
  exit 2
fi
if [[ -f .env ]]; then
  # Same trusted, locally maintained shell-compatible file as dev.sh.
  set -a
  source .env
  set +a
fi
if [[ "${AI_PROVIDER:-disabled}" != openai-compatible || -z "${AI_API_KEY:-}" || -z "${AI_BASE_URL:-}" || -z "${AI_MODEL:-}" ]]; then
  echo '请在本地配置 AI_PROVIDER=openai-compatible、AI_API_KEY、AI_BASE_URL 和 AI_MODEL。' >&2
  exit 2
fi
export AI_LIVE_CONFIG_PATH="$PROJECT_ROOT/scheduler-app/src/main/resources/application.yml"
exec ./scripts/mvn.sh -B -ntp -pl scheduler-agent -am \
  -Dtest=AiProviderLiveCheck -Dsurefire.failIfNoSpecifiedTests=false -Dai.live=true test
