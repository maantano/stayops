resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-pg17"
  family = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

# 마스터 비밀번호는 RDS가 Secrets Manager에 만들고 회전한다 — Terraform state·코드에 비밀번호가 남지 않는다.
resource "aws_db_instance" "main" {
  #checkov:skip=CKV_AWS_157:데모 비용 — 단일 AZ(운영은 multi_az = true)
  #checkov:skip=CKV_AWS_118:데모 비용 — enhanced monitoring 미사용
  #checkov:skip=CKV_AWS_353:db.t4g.micro — performance insights 미사용
  #checkov:skip=CKV_AWS_161:앱은 Secrets Manager 비밀번호로 접속(IAM DB 인증 미사용)
  #checkov:skip=CKV_AWS_293:데모 환경을 지울 수 있게 변수로 제어(db_deletion_protection)
  identifier                      = local.name
  engine                          = "postgres"
  engine_version                  = "17"
  instance_class                  = var.db_instance_class
  allocated_storage               = 20
  max_allocated_storage           = 100
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "stayops"
  username                        = "stayops"
  manage_master_user_password     = true
  db_subnet_group_name            = aws_db_subnet_group.main.name
  parameter_group_name            = aws_db_parameter_group.postgres.name
  vpc_security_group_ids          = [aws_security_group.db.id]
  publicly_accessible             = false
  multi_az                        = false # 데모 비용. 운영은 true
  backup_retention_period         = 7
  deletion_protection             = var.db_deletion_protection
  skip_final_snapshot             = !var.db_deletion_protection
  final_snapshot_identifier       = var.db_deletion_protection ? "${local.name}-final" : null
  auto_minor_version_upgrade      = true
  copy_tags_to_snapshot           = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
}
