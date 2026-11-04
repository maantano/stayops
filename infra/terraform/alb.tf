resource "aws_lb" "api" {
  #checkov:skip=CKV2_AWS_28:데모 비용 — WAF 미사용(공개 운영 시 managed rule 적용)
  #checkov:skip=CKV2_AWS_20:certificate_arn을 주면 80→443 리다이렉트(dynamic 블록이라 정적 검사가 판단 못함)
  #checkov:skip=CKV_AWS_91:데모 비용 — access log 버킷 미구성
  #checkov:skip=CKV_AWS_150:데모 환경을 지울 수 있게 삭제 보호 끔
  name                       = local.name
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "api" {
  #checkov:skip=CKV_AWS_378:ALB→task 구간은 VPC 내부 HTTP(TLS는 ALB에서 종료)
  name                 = "${local.name}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path                = "/actuator/health/readiness" # DB 연결이 끊기면 트래픽을 빼고, liveness는 컨테이너 재시작 판단에 쓴다
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

locals {
  https = var.certificate_arn != ""
}

resource "aws_lb_listener" "http" {
  #checkov:skip=CKV_AWS_103:인증서가 있으면 리다이렉트 전용, 없으면 데모용 HTTP(변수 설명 참고)
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = local.https ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = local.https ? [] : [1]
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.api.arn
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = local.https ? 1 : 0
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
