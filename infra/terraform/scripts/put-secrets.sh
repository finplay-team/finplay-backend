#!/bin/bash
# 로컬 .env(진짜 값이 채워진 파일)를 읽어 서드파티 시크릿을 SSM Parameter Store에 1회 이관한다.
# ssm_parameters.tf가 이름만 선언해 둔 파라미터들(external_secret_names)에 실값을 채우는 스크립트다.
# DB_PASSWORD 등 Terraform이 직접 아는 값은 여기서 다루지 않는다 — Terraform이 이미 넣어뒀다.
#
# 사용법: ./put-secrets.sh /path/to/실제값이-채워진.env
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${1:-../../.env}"
SSM_PREFIX="/finplay/prod"

if [ ! -f "$ENV_FILE" ]; then
  echo "파일을 찾을 수 없습니다: $ENV_FILE" >&2
  exit 1
fi

# 이름 목록을 여기 다시 하드코딩하지 않는다 — 정본은 ssm_parameters.tf의
# local.external_secret_names이고, 이 스크립트는 terraform output으로 그 값을 그대로 읽는다.
SECRET_KEYS=()
while IFS= read -r key; do
  SECRET_KEYS+=("$key")
done < <(
  terraform -chdir="${SCRIPT_DIR}/.." output -json external_secret_names |
    python3 -c 'import json, sys; print("\n".join(json.load(sys.stdin)))'
)

for key in "${SECRET_KEYS[@]}"; do
  value=$(grep -m1 "^${key}=" "$ENV_FILE" | cut -d= -f2- || true)
  if [ -z "$value" ]; then
    echo "건너뜀 (값 없음): $key"
    continue
  fi

  aws ssm put-parameter \
    --name "${SSM_PREFIX}/${key}" \
    --type SecureString \
    --value "$value" \
    --overwrite \
    --region ap-northeast-2 \
    --output text >/dev/null

  echo "이관 완료: $key"
done

echo "완료. 각 EC2에서 SSM Send Command로 /opt/finplay/refresh-env.sh를 재실행해야 .env에 반영됩니다."
