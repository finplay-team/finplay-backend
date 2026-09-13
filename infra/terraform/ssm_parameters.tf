# 시크릿은 EC2에 .env 파일로 수동 배치하는 대신 SSM Parameter Store(SecureString)에 두고,
# EC2의 refresh-env.sh(ec2.tf의 user_data)가 부팅·재실행 시점에 이걸 읽어 .env를 만든다.
#
# 두 갈래로 나뉜다.
# 1) Terraform이 값 자체를 아는 것(DB 접속 정보) — 여기서 직접 쓴다.
# 2) 서드파티 시크릿(OPENAI_API_KEY 등) — Terraform은 파라미터 "이름"만 선언하고 값은
#    ignore_changes로 관리 밖에 둔다. 실제 값은 scripts/put-secrets.sh로 사용자가 1회 채운다.
#    Terraform state에 서드파티 시크릿 원문을 남기지 않기 위해서다.

locals {
  ssm_prefix = "/${var.project_name}/prod"

  generated_parameters = {
    "DB_PASSWORD"                   = random_password.db.result
    "DB_URL"                        = "jdbc:mysql://${aws_db_instance.main.address}:3306/${var.db_name}?useSSL=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
    "DB_USERNAME"                   = var.db_username
    "REDIS_HOST"                    = aws_elasticache_replication_group.main.primary_endpoint_address
    "REDIS_PORT"                    = "6379"
    "SPRING_DATA_REDIS_SSL_ENABLED" = "true"
    "CORS_ALLOWED_ORIGINS"          = "https://www.${var.domain_name}"
    "OAUTH_STATE_COOKIE_SECURE"     = "true"
    "ECR_REGISTRY"                  = "${data.aws_caller_identity.current.account_id}.dkr.ecr.ap-northeast-2.amazonaws.com"
  }

  # .env.example에서 뽑은 서드파티 시크릿 목록 — 사용자가 scripts/put-secrets.sh로 채운다.
  external_secret_names = [
    "JWT_SECRET",
    "KAKAO_CLIENT_ID",
    "KAKAO_CLIENT_SECRET",
    "KAKAO_REDIRECT_URI",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
    "NAVER_REDIRECT_URI",
    "OAUTH_STATE_SECRET",
    "OAUTH_LOGIN_REDIRECT_URI",
    "OAUTH_REAUTH_REDIRECT_URI",
    "EMAIL_VERIFICATION_SECRET",
    "PASSWORD_RESET_SECRET",
    "RESEND_API_KEY",
    "EMAIL_FROM",
    "KIS_APP_KEY",
    "KIS_APP_SECRET",
    "NAVER_SEARCH_CLIENT_ID",
    "NAVER_SEARCH_CLIENT_SECRET",
    "DART_API_KEY",
    "OPENAI_API_KEY",
  ]
}

data "aws_caller_identity" "current" {}

resource "aws_ssm_parameter" "generated" {
  for_each = local.generated_parameters

  name  = "${local.ssm_prefix}/${each.key}"
  type  = "SecureString"
  value = each.value

  tags = {
    Name = "${var.project_name}-${each.key}"
  }
}

resource "aws_ssm_parameter" "external_secret" {
  for_each = toset(local.external_secret_names)

  name  = "${local.ssm_prefix}/${each.value}"
  type  = "SecureString"
  value = "CHANGE_ME" # scripts/put-secrets.sh로 실값을 채운다. 이 placeholder를 Terraform이 되돌리지 않는다.

  lifecycle {
    ignore_changes = [value]
  }

  tags = {
    Name = "${var.project_name}-${each.value}"
  }
}
