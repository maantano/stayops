variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "environment" {
  type    = string
  default = "demo"
}

variable "image_tag" {
  description = "ECR에 push한 API 이미지 태그 (git SHA 권장, ECR은 태그 변경 불가로 설정)"
  type        = string
}

variable "web_origin" {
  description = "CORS 허용 origin — Vercel에 배포한 웹 주소 (예: https://stayops.vercel.app)"
  type        = string
}

variable "certificate_arn" {
  description = "ALB HTTPS용 ACM 인증서 ARN. 비우면 HTTP만 연다(데모 전용 — 공개 시에는 반드시 지정)"
  type        = string
  default     = ""
}

variable "cpu_architecture" {
  description = "이미지 아키텍처. Apple Silicon에서 빌드했다면 ARM64"
  type        = string
  default     = "ARM64"
  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "ARM64 또는 X86_64"
  }
}

variable "api_desired_count" {
  type    = number
  default = 2
}

variable "api_cpu" {
  type    = number
  default = 512
}

variable "api_memory" {
  type    = number
  default = 1024
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_deletion_protection" {
  description = "데모 환경을 지울 수 있게 기본 false. 실제 데이터가 있다면 true"
  type        = bool
  default     = false
}

variable "log_retention_days" {
  type    = number
  default = 30
}
