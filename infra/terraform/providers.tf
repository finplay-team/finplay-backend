# 리전은 변수로 노출하지 않고 여기에 고정한다 — 실수로 다른 리전에 apply되는 것을 막기 위해서다
# (2026-09-14 팀 결정). 인프라 전체가 서울(ap-northeast-2) 기준으로 설계돼 있다.
terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2"

  default_tags {
    tags = {
      Project   = "finplay"
      ManagedBy = "terraform"
    }
  }
}

# CloudFront에 붙이는 ACM 인증서는 CloudFront가 어느 리전 소속도 아니라서, AWS가 그 인증서의
# 위치를 us-east-1 하나로 못박아 뒀다 — 위 리전 고정 원칙과는 별개로 CloudFront 전용 예외다.
# 다른 리전에서 발급한 인증서는 CloudFront 콘솔·API에서 아예 선택지에 뜨지 않는다.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project   = "finplay"
      ManagedBy = "terraform"
    }
  }
}
