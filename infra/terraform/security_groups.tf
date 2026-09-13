# ADR-0020 §5 원칙을 그대로 따른다 — RDS·ElastiCache의 인바운드 소스는 CIDR이 아니라 EC2의
# 보안 그룹 ID로 지정한다. 인스턴스가 몇 대로 늘어나도 같은 보안 그룹만 달면 접근 규칙을
# 다시 만들 필요가 없다. 기본 VPC 보안 그룹(default)은 재사용하지 않는다.
#
# AWS 보안 그룹의 description 필드(그룹 자체·규칙 둘 다)는 특정 ASCII 문자만 허용하고
# 한글은 거부한다(terraform validate에서 실측) — 그래서 description은 영어로 짧게 쓰고,
# 자세한 맥락은 이 파일의 주석(# )으로 남긴다.

# ALB 인바운드 — 인터넷에서 80/443만 연다
resource "aws_security_group" "alb" {
  name        = "${var.project_name}-alb-sg"
  description = "ALB inbound - internet 80/443 only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP, redirected to HTTPS by the listener"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-alb-sg"
  }
}

# 웹·스케줄러 EC2 공통 — 앱 포트는 ALB에서만 받는다. SSH 인바운드는 열지 않는다
# (SSM만 사용, ADR-0021 §결정 3).
resource "aws_security_group" "ec2" {
  name        = "${var.project_name}-ec2-sg"
  description = "Web and scheduler EC2 - app port from ALB only, no SSH"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "ALB to app container"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # 아웃바운드 전체 허용 — SSM·ECR pull·빗썸/KIS/OpenAI 등 외부 API 호출에 필요하다.
  egress {
    description = "All outbound - SSM, ECR pull, external APIs"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-ec2-sg"
  }
}

# RDS 인바운드 — EC2 보안 그룹에서만 3306 허용
resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds-sg"
  description = "RDS inbound - from EC2 security group only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL from EC2"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-rds-sg"
  }
}

# ElastiCache 인바운드 — EC2 보안 그룹에서만 6379 허용
resource "aws_security_group" "elasticache" {
  name        = "${var.project_name}-elasticache-sg"
  description = "ElastiCache inbound - from EC2 security group only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Redis from EC2"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-elasticache-sg"
  }
}
