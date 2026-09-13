resource "aws_cloudwatch_log_group" "instance" {
  for_each = toset(local.all_instance_names)

  name              = "/${var.project_name}/${each.value}"
  retention_in_days = var.cloudwatch_log_retention_days
}

locals {
  instance_by_name = merge(
    { for name, inst in aws_instance.web : name => inst },
    { "${var.project_name}-scheduler" = aws_instance.scheduler },
  )
}

# ── EC2 (CloudWatch Agent 커스텀 메트릭 + 기본 메트릭) ────────────────
# 디스크 사용률 알람은 cd-runbook.md에 기록된 2026-08-24 디스크 100% 사고(이미지 태그가
# 커밋 SHA라 배포마다 쌓이는데 정리 단계가 없었다) 재발 방지가 목적이다.

resource "aws_cloudwatch_metric_alarm" "ec2_disk" {
  for_each = local.instance_by_name

  alarm_name          = "${each.key}-disk-used-percent-high"
  namespace           = "Finplay/EC2"
  metric_name         = "disk_used_percent"
  dimensions          = { InstanceId = each.value.id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching" # 에이전트가 죽어 메트릭이 안 올라오는 것도 이상 신호로 본다
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ec2_memory" {
  for_each = local.instance_by_name

  alarm_name          = "${each.key}-mem-used-percent-high"
  namespace           = "Finplay/EC2"
  metric_name         = "mem_used_percent"
  dimensions          = { InstanceId = each.value.id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ec2_cpu" {
  for_each = local.instance_by_name

  alarm_name          = "${each.key}-cpu-high"
  namespace           = "AWS/EC2"
  metric_name         = "CPUUtilization"
  dimensions          = { InstanceId = each.value.id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

# ── RDS ────────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${var.project_name}-db-free-storage-low"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648 # 2GB
  comparison_operator = "LessThanThreshold"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.project_name}-db-cpu-high"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${var.project_name}-db-connections-high"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 50
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

# ── ElastiCache ────────────────────────────────────────────────────
# 노드(멤버 클러스터) 단위로 발행되는 메트릭이라 복제본 포함 2개 노드 각각에 건다.

resource "aws_cloudwatch_metric_alarm" "elasticache_cpu" {
  for_each = toset(aws_elasticache_replication_group.main.member_clusters)

  alarm_name          = "${each.value}-cpu-high"
  namespace           = "AWS/ElastiCache"
  metric_name         = "CPUUtilization"
  dimensions          = { CacheClusterId = each.value }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "elasticache_memory" {
  for_each = toset(aws_elasticache_replication_group.main.member_clusters)

  alarm_name          = "${each.value}-memory-used-high"
  namespace           = "AWS/ElastiCache"
  metric_name         = "DatabaseMemoryUsagePercentage"
  dimensions          = { CacheClusterId = each.value }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "elasticache_evictions" {
  for_each = toset(aws_elasticache_replication_group.main.member_clusters)

  alarm_name          = "${each.value}-evictions"
  namespace           = "AWS/ElastiCache"
  metric_name         = "Evictions"
  dimensions          = { CacheClusterId = each.value }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

# ── ALB ────────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_hosts" {
  alarm_name = "${var.project_name}-alb-unhealthy-hosts"
  namespace  = "AWS/ApplicationELB"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.web.arn_suffix
  }
  metric_name         = "UnHealthyHostCount"
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name = "${var.project_name}-alb-5xx-high"
  namespace  = "AWS/ApplicationELB"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "alb_latency" {
  alarm_name = "${var.project_name}-alb-target-response-time-high"
  namespace  = "AWS/ApplicationELB"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 3
  threshold           = 2
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_actions
}
