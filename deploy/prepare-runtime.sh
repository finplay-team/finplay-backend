#!/bin/bash
set -euo pipefail

ROLE="${1:?role is required: web or scheduler}"
AWS_REGION_VALUE="${2:?aws region is required}"
APP_DIR="/home/ec2-user/finplay"
ENV_FILE="$APP_DIR/.env"
REFRESH_DIR="/opt/finplay"
BASE_REFRESH_SCRIPT="$REFRESH_DIR/refresh-env-base.sh"
REFRESH_SCRIPT="$REFRESH_DIR/refresh-env.sh"
ROLE_CONFIG="$REFRESH_DIR/runtime-role.conf"

case "$ROLE" in
  web)
    SPRING_PROFILE="prod,web"
    HEALTHCHECK_COMMAND="curl -fsS http://localhost:8080/actuator/health"
    ;;
  scheduler)
    SPRING_PROFILE="prod,scheduler"
    HEALTHCHECK_COMMAND="test -r /proc/1/cmdline && grep -aq 'app.jar' /proc/1/cmdline && test -f /tmp/finplay-scheduler-ready"
    ;;
  *)
    echo "지원하지 않는 역할: $ROLE" >&2
    exit 1
    ;;
esac

if [ ! -f "$REFRESH_SCRIPT" ]; then
  echo "refresh-env.sh가 없습니다: $REFRESH_SCRIPT" >&2
  exit 1
fi

mkdir -p "$REFRESH_DIR"
if [ ! -f "$BASE_REFRESH_SCRIPT" ]; then
  cp "$REFRESH_SCRIPT" "$BASE_REFRESH_SCRIPT"
fi

{
  printf 'AWS_REGION_VALUE=%q\n' "$AWS_REGION_VALUE"
  printf 'SPRING_PROFILE=%q\n' "$SPRING_PROFILE"
  printf 'HEALTHCHECK_COMMAND=%q\n' "$HEALTHCHECK_COMMAND"
} > "$ROLE_CONFIG"
chmod 600 "$ROLE_CONFIG"

cat > "$REFRESH_SCRIPT" <<'EOF'
#!/bin/bash
set -euo pipefail

ROLE_CONFIG="/opt/finplay/runtime-role.conf"
ENV_FILE="/home/ec2-user/finplay/.env"
TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT

. "$ROLE_CONFIG"
/opt/finplay/refresh-env-base.sh

awk -F= '!/^(AWS_REGION|SPRING_PROFILES_ACTIVE|APP_HEALTHCHECK_COMMAND)=/' "$ENV_FILE" > "$TMP_FILE"
printf 'AWS_REGION=%s\n' "$AWS_REGION_VALUE" >> "$TMP_FILE"
printf 'SPRING_PROFILES_ACTIVE=%s\n' "$SPRING_PROFILE" >> "$TMP_FILE"
printf 'APP_HEALTHCHECK_COMMAND=%s\n' "$HEALTHCHECK_COMMAND" >> "$TMP_FILE"
mv "$TMP_FILE" "$ENV_FILE"
chown ec2-user:ec2-user "$ENV_FILE"
chmod 600 "$ENV_FILE"
EOF
chmod 700 "$REFRESH_SCRIPT"

"$REFRESH_SCRIPT"

if ! grep -q '^APP_IMAGE=' "$ENV_FILE"; then
  echo "APP_IMAGE가 .env에 없습니다: $ENV_FILE" >&2
  exit 1
fi

if ! docker compose -f "$APP_DIR/compose.deploy.yaml" config --quiet; then
  echo "역할별 런타임 설정으로 Compose 검증에 실패했습니다" >&2
  exit 1
fi
