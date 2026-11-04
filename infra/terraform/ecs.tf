resource "aws_cloudwatch_log_group" "api" {
  #checkov:skip=CKV_AWS_158:CloudWatch 기본 암호화 사용(고객 관리 KMS 키 미사용)
  #checkov:skip=CKV_AWS_338:합성 데이터 데모 — 보관 기간은 변수(log_retention_days, 기본 30일)
  name              = "/ecs/${local.name}/api"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# 실행 역할: 이미지 pull, 로그 전송, DB 비밀 읽기만
resource "aws_iam_role" "execution" {
  name               = "${local.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "read_db_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.main.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "read_db_secret" {
  name   = "read-db-secret"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_db_secret.json
}

# 애플리케이션 역할: 현재 AWS API를 호출하지 않으므로 권한 없음
resource "aws_iam_role" "task" {
  name               = "${local.name}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

locals {
  image     = "${aws_ecr_repository.api.repository_url}:${var.image_tag}"
  db_secret = aws_db_instance.main.master_user_secret[0].secret_arn

  # 서버와 migration task가 같은 이미지·DB 설정을 쓴다. 비밀번호는 Secrets Manager JSON의 password 키로 주입.
  common_environment = [
    { name = "STAYOPS_DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.endpoint}/${aws_db_instance.main.db_name}?sslmode=require" },
    { name = "STAYOPS_WEB_ALLOWED_ORIGINS", value = var.web_origin },
  ]
  common_secrets = [
    { name = "STAYOPS_DB_USER", valueFrom = "${local.db_secret}:username::" },
    { name = "STAYOPS_DB_PASSWORD", valueFrom = "${local.db_secret}:password::" },
  ]
  log_configuration = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.api.name
      awslogs-region        = var.region
      awslogs-stream-prefix = "api"
    }
  }
}

resource "aws_ecs_task_definition" "api" {
  #checkov:skip=CKV_AWS_336:Tomcat이 임시 디렉터리에 쓴다(운영 전환 시 tmpfs volume + read-only)
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }
  container_definitions = jsonencode([{
    name                   = "api"
    image                  = local.image
    essential              = true
    readonlyRootFilesystem = false # Tomcat 임시 디렉터리
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    environment            = concat(local.common_environment, [{ name = "SPRING_PROFILES_ACTIVE", value = "aws" }])
    secrets                = local.common_secrets
    logConfiguration       = local.log_configuration
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health/liveness || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
    stopTimeout = 30 # server.shutdown=graceful
  }])
}

# 일회성 migration task — 새 이미지 배포 전에 `terraform output -raw migrate_command`로 실행한다.
resource "aws_ecs_task_definition" "migrate" {
  family                   = "${local.name}-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }
  container_definitions = jsonencode([{
    name                   = "migrate"
    image                  = local.image
    essential              = true
    readonlyRootFilesystem = true # 웹 서버 없이 Flyway만 실행
    environment            = concat(local.common_environment, [{ name = "SPRING_PROFILES_ACTIVE", value = "aws,migrate" }])
    secrets                = local.common_secrets
    logConfiguration       = local.log_configuration
  }])
}

resource "aws_ecs_service" "api" {
  #checkov:skip=CKV_AWS_333:NAT 비용 없이 ECR 접근하려고 공인 IP 사용. 인바운드는 SG로 ALB만 허용
  name                               = "api"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.api.arn
  desired_count                      = var.api_desired_count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true # NAT 없이 ECR·Secrets Manager 접근 (network.tf 주석 참고)
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.http]
}
