# StayOps API — AWS 배포 구성 (P12). 코드만 있고 실제로 apply하지 않았다. docs/deploy/DEPLOYMENT.md 참고.
terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
  # 팀 사용 시 S3 backend(+ lockfile)를 쓴다. 예:
  # backend "s3" { bucket = "<state-bucket>", key = "stayops/terraform.tfstate", region = "ap-northeast-2", use_lockfile = true }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "stayops", Environment = var.environment, ManagedBy = "terraform", DataClass = "synthetic" }
  }
}

data "aws_availability_zones" "available" {
  #checkov:skip=CKV_AWS_394:앞의 두 AZ만 slice해서 쓰므로 목록이 늘어도 영향 없음
  state = "available"
}
