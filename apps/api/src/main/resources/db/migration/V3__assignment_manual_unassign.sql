-- P4: 관리자가 배정을 직접 해제하는 경우(DELETE /tasks/{id}/assignments/{assignmentId})의 종료 사유 추가.
-- V1을 수정하지 않고 CHECK 제약을 교체한다.

ALTER TABLE task_assignment DROP CONSTRAINT task_assignment_unassigned_reason_check;
ALTER TABLE task_assignment ADD CONSTRAINT task_assignment_unassigned_reason_check CHECK (unassigned_reason IN
    ('REASSIGNED', 'MANUAL_UNASSIGN', 'ASSIGNEE_INVALID_ON_RESUME', 'TASK_CANCELLED', 'TASK_COMPLETED'));
