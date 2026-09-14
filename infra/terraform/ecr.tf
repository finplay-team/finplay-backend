resource "aws_ecr_repository" "app" {
  name                 = "${var.project_name}-api"
  image_tag_mutability = "IMMUTABLE" # 태그는 커밋 SHA다 — 같은 태그가 시점마다 다른 이미지를 가리키면 롤백 대상을 이름으로 지목할 수 없다 (ADR-0021 §맥락)

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = "${var.project_name}-api"
  }
}

# 이미지 태그가 커밋 SHA라 매 배포마다 쌓인다 — 오래된 태그부터 수명주기 정책으로 정리한다.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "최근 이미지 ${var.ecr_image_count_limit}개만 유지"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_image_count_limit
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
