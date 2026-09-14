resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.project_name}-cache-subnet-group"
  subnet_ids = aws_subnet.private[*].id
}

# RDS와 달리 복제본을 둔다 — 판단 근거가 다르기 때문이다(ADR-0020 §2). PriceStore는 폴백이
# 없는 시세의 유일한 저장소라, Redis가 죽으면 그 경로는 폴백되는 게 아니라 기능이 멈춘다.
# 복제본 1 + Multi-AZ 자동 장애 조치로 단일 노드 장애가 곧 서비스 정지가 되는 것을 막는다.
resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "${var.project_name}-cache"
  # ElastiCache CreateReplicationGroup은 description에 ASCII 외 문자가 있으면
  # "non-printable control characters" InvalidParameterValue로 거부한다.
  # 한글·em dash 없이 ASCII로만 적는다.
  description = "FinPlay Redis - price snapshots, ranking ZSET, read cache, distributed lock"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.elasticache_node_type
  port           = 6379

  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.elasticache.id]

  # 전송 중 암호화를 켜면 클라이언트가 TLS로 붙어야 한다 — 앱의 SPRING_DATA_REDIS_SSL_ENABLED=true가
  # 이 설정과 반드시 짝을 이뤄야 한다. 안 맞으면 TCP는 연결되고 Redis 핸드셰이크만 타임아웃된다
  # (2026-08-11 실측, ADR-0020 §2).
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true

  tags = {
    Name = "${var.project_name}-cache"
  }
}
