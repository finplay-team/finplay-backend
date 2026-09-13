data "aws_ami" "al2023_arm64" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-arm64"]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }
}

locals {
  web_instance_names = ["${var.project_name}-web-1", "${var.project_name}-web-2"]
  all_instance_names = concat(local.web_instance_names, ["${var.project_name}-scheduler"])

  cloudwatch_agent_config = file("${path.module}/files/cloudwatch-agent-config.json")
  compose_file_content    = file("${path.module}/../../compose.deploy.yaml")
}

# 웹 2대 — 둘 다 같은 ALB 타깃 그룹에 등록되어 동시에 트래픽을 받는다(alb.tf의
# aws_lb_target_group_attachment 참고). 배포는 롤링(한 대씩 순차 교체)으로 한다.
resource "aws_instance" "web" {
  for_each = toset(local.web_instance_names)

  ami                    = data.aws_ami.al2023_arm64.id
  instance_type          = var.ec2_instance_type
  subnet_id              = aws_subnet.public[index(local.web_instance_names, each.value) % length(aws_subnet.public)].id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  user_data = templatefile("${path.module}/files/user_data.sh.tftpl", {
    role                    = each.value
    cloudwatch_agent_config = local.cloudwatch_agent_config
    compose_file_content    = local.compose_file_content
    refresh_env_script = templatefile("${path.module}/files/refresh-env.sh.tftpl", {
      ssm_prefix    = local.ssm_prefix
      awslogs_group = aws_cloudwatch_log_group.instance[each.value].name
    })
  })

  tags = {
    Name = each.value
    Role = "web"
  }
}

# 스케줄러 1대 — ALB 타깃 그룹에 등록하지 않는다(트래픽을 받지 않음). 웹 인스턴스와 완전히
# 동일한 이미지·구성으로 뜬다 — 다중 인스턴스에서도 안전하게 동작하도록 이미 손본 배치 작업들
# (#564 빗썸 피드 리더 락, #565 재생세션 락, 그 외 기존 RedisLock류)이 어느 인스턴스에서
# 실행되든 정확히 하나만 실제로 일한다는 전제로 별도 프로필 분리 없이 그대로 띄운다.
resource "aws_instance" "scheduler" {
  ami                    = data.aws_ami.al2023_arm64.id
  instance_type          = var.ec2_instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  user_data = templatefile("${path.module}/files/user_data.sh.tftpl", {
    role                    = "${var.project_name}-scheduler"
    cloudwatch_agent_config = local.cloudwatch_agent_config
    compose_file_content    = local.compose_file_content
    refresh_env_script = templatefile("${path.module}/files/refresh-env.sh.tftpl", {
      ssm_prefix    = local.ssm_prefix
      awslogs_group = aws_cloudwatch_log_group.instance["${var.project_name}-scheduler"].name
    })
  })

  tags = {
    Name = "${var.project_name}-scheduler"
    Role = "scheduler"
  }
}
