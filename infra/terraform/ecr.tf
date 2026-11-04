resource "aws_ecr_repository" "api" {
  #checkov:skip=CKV_AWS_136:AWS 관리형 AES256 암호화 사용(고객 관리 KMS 키는 비용·운영 부담)
  name                 = "stayops-api"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 20 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 20 }
      action       = { type = "expire" }
    }]
  })
}
