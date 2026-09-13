resource "random_id" "bucket_suffix" {
  byte_length = 4
}

# 커뮤니티 게시글 첨부 이미지. 퍼블릭 액세스는 전부 막고, EC2 역할(iam.tf)에서만 접근한다 —
# ADR-0020 §3, "LocalFileStorageService는 다중 인스턴스에서 깨진다"는 문제를 해결한 그 버킷이다.
resource "aws_s3_bucket" "community_images" {
  bucket = "${var.project_name}-community-images-${random_id.bucket_suffix.hex}"

  tags = {
    Name = "${var.project_name}-community-images"
  }
}

resource "aws_s3_bucket_public_access_block" "community_images" {
  bucket = aws_s3_bucket.community_images.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "community_images" {
  bucket = aws_s3_bucket.community_images.id

  versioning_configuration {
    status = "Enabled"
  }
}

# 프론트(finplay-frontend 레포)의 정적 빌드 산출물. 퍼블릭으로 열지 않고 CloudFront Origin
# Access Control(OAC)로만 접근을 허용한다 — 버킷 정책은 cloudfront.tf에서 배포 ARN을
# 참조해야 하므로 그쪽에 둔다.
resource "aws_s3_bucket" "frontend" {
  bucket = "${var.project_name}-frontend-${random_id.bucket_suffix.hex}"

  tags = {
    Name = "${var.project_name}-frontend"
  }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  versioning_configuration {
    status = "Enabled"
  }
}
