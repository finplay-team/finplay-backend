resource "random_password" "db" {
  length  = 32
  special = false # JDBC URL·쉘 스크립트에 특수문자 이스케이프 문제를 만들지 않기 위해 영숫자만 쓴다
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnet-group"
  subnet_ids = aws_subnet.private[*].id

  tags = {
    Name = "${var.project_name}-db-subnet-group"
  }
}

# Single-AZ로 간다 — 목적이 고가용성이 아니라 EC2와의 생명주기 분리이기 때문이다(ADR-0020 §1).
# EC2를 버려도 원장이 남는다는 요구는 Single-AZ로 이미 충족된다. Multi-AZ는 비용을 두 배로
# 만들면서 이 요구에 아무것도 더하지 않는다 — 실사용자 트래픽이 붙는 시점에 무중단으로 켤 수
# 있으므로 지금 결정하지 않는다.
resource "aws_db_instance" "main" {
  identifier     = "${var.project_name}-db"
  engine         = "mysql"
  engine_version = "8.4"

  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage_gb
  storage_type      = "gp3"

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  multi_az               = false
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  backup_retention_period = 7
  skip_final_snapshot     = var.rds_skip_final_snapshot
  deletion_protection     = var.rds_deletion_protection

  tags = {
    Name = "${var.project_name}-db"
  }
}
