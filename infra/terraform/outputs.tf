# 여기 나오는 값들은 그대로 GitHub 리포지터리 Settings → Variables에 등록한다
# (deploy/cd-runbook.md의 GitHub Variable 표를 이번 롤링 배포 구조에 맞게 갱신한 것).

output "aws_region" {
  value = "ap-northeast-2"
}

output "cd_role_arn" {
  value = aws_iam_role.cd_deploy.arn
}

output "ecr_repository" {
  value = aws_ecr_repository.app.name
}

output "alb_listener_arn" {
  value = aws_lb_listener.https.arn
}

output "web_target_group_arn" {
  value = aws_lb_target_group.web.arn
}

# deploy.yml의 strategy.matrix.instance_id가 fromJson(vars.WEB_INSTANCE_IDS)를 그대로
# matrix 차원으로 쓰므로, 배열(["i-...", "i-..."])이어야 한다 — {이름: ID} 객체를 넣으면
# matrix가 의도한 대로 인스턴스 ID 목록을 순회하지 않는다(PR #569 리뷰에서 지적, 실측 확인).
output "web_instance_ids" {
  value = [for name, inst in aws_instance.web : inst.id]
}

output "scheduler_instance_id" {
  value = aws_instance.scheduler.id
}

output "frontend_bucket_name" {
  value = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.frontend.id
}

output "route53_name_servers" {
  description = "도메인 등록업체 콘솔에서 네임서버를 이 값들로 바꿔야 한다 (Terraform이 할 수 없는 유일한 수동 단계)"
  value       = aws_route53_zone.main.name_servers
}

output "db_password" {
  sensitive = true
  value     = random_password.db.result
}
