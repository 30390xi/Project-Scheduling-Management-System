#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [[ ! -f .env ]]; then
  echo '请先执行 cp .env.example .env，并配置本地开发密码。' >&2
  exit 1
fi
# Only source this project's trusted, locally maintained shell-compatible .env.
set -a
source .env
set +a
export SPRING_PROFILES_ACTIVE=dev
export DB_URL="jdbc:postgresql://localhost:${DB_PORT:-54329}/${DB_NAME:-scheduler}"
docker compose up -d --wait postgres
./scripts/mvn.sh -q -DskipTests package
if [[ "$(uname -s)" == Darwin ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
exec java -jar scheduler-app/target/scheduler-app-0.1.0-SNAPSHOT.jar
