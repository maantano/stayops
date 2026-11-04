output "api_url" {
  description = "웹의 NEXT_PUBLIC_STAYOPS_API_BASE_URL로 쓴다"
  value       = "${local.https ? "https" : "http"}://${aws_lb.api.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "db_secret_arn" {
  value = local.db_secret
}

output "migrate_command" {
  description = "새 이미지 배포 전에 실행하는 migration task (완료·exit code 0 확인 후 서비스 갱신)"
  value = join(" ", [
    "aws ecs run-task --region ${var.region} --cluster ${aws_ecs_cluster.main.name}",
    "--launch-type FARGATE --task-definition ${aws_ecs_task_definition.migrate.family}",
    "--network-configuration 'awsvpcConfiguration={subnets=[${join(",", aws_subnet.public[*].id)}],securityGroups=[${aws_security_group.api.id}],assignPublicIp=ENABLED}'",
  ])
}
