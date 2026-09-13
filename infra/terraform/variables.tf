variable "project_name" {
  description = "리소스 이름 접두어로 쓰는 프로젝트 이름"
  type        = string
  default     = "finplay"
}

variable "domain_name" {
  description = "Route 53에 새로 만들 호스팅존의 루트 도메인"
  type        = string
  default     = "finplay.site"
}

variable "github_repository" {
  description = "GitHub OIDC 신뢰 정책에 넣을 리포지터리 (owner/repo). 브랜치는 deploy_branch로 별도 지정한다."
  type        = string
  default     = "finplay-team/finplay-backend"
}

variable "deploy_branch" {
  description = "배포를 트리거하는 브랜치. OIDC 신뢰 정책의 sub 조건에 이 브랜치까지 못박는다(레포까지만 제한하면 다른 브랜치 워크플로우도 역할을 가져갈 수 있다 — ADR-0021 §결정 2)."
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "퍼블릭 서브넷(EC2·ALB) CIDR — AZ 2개"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "private_subnet_cidrs" {
  description = "프라이빗 서브넷(RDS·ElastiCache) CIDR — AZ 2개"
  type        = list(string)
  default     = ["10.0.11.0/24", "10.0.12.0/24"]
}

variable "ec2_instance_type" {
  description = "웹·스케줄러 EC2 공통 인스턴스 타입"
  type        = string
  default     = "t4g.small"
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.small"
}

variable "rds_allocated_storage_gb" {
  type    = number
  default = 20
}

variable "rds_skip_final_snapshot" {
  description = "true면 삭제 시 최종 스냅샷을 안 만든다. 지금은 재구축 반복 중이라 true — 운영이 안정되면 false로 바꾸는 것을 권장한다."
  type        = bool
  default     = true
}

variable "db_name" {
  type    = string
  default = "finplay"
}

variable "db_username" {
  type    = string
  default = "finplay_admin"
}

variable "elasticache_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "cloudwatch_log_retention_days" {
  description = "EC2 CloudWatch 로그 그룹 보관 기간 — 비용 제한 목적"
  type        = number
  default     = 14
}

variable "alarm_actions" {
  description = "CloudWatch 알람이 발생했을 때 호출할 ARN 목록(SNS 토픽 등). 지금은 빈 리스트 — 콘솔에서 알람 상태만 확인한다. 나중에 SNS 토픽 ARN을 여기 하나 추가하면 모든 알람에 한 번에 이메일 알림이 붙는다."
  type        = list(string)
  default     = []
}

variable "ecr_image_count_limit" {
  description = "ECR 이미지 태그가 커밋 SHA라 매 배포마다 쌓인다 — 이 개수 초과분은 자동 만료"
  type        = number
  default     = 10
}
