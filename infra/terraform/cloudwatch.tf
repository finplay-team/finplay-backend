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

# ── EC2 기본 메트릭 ────────────────────────────────────────────────

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
#
# member_clusters는 리소스가 실제로 생성된 뒤에만 알 수 있는 값이라(apply 시점 계산),
# 아직 없는 리소스의 for_each 키로 쓸 수 없다(terraform plan 실측 오류: "known only after
# apply"). AWS가 멤버 클러스터를 "<replication_group_id>-0XX"로 순번 명명하는 규칙이
# 문서화돼 있어, 그 이름을 미리 계산해 정적인 for_each 키로 쓴다.
locals {
  elasticache_member_cluster_ids = [
    for i in range(2) : format("%s-cache-%03d", var.project_name, i + 1)
  ]
}

resource "aws_cloudwatch_metric_alarm" "elasticache_cpu" {
  for_each = toset(local.elasticache_member_cluster_ids)

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
  for_each = toset(local.elasticache_member_cluster_ids)

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
  for_each = toset(local.elasticache_member_cluster_ids)

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
