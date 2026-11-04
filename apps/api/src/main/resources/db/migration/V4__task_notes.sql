-- P5: 투숙 중 청소 요청 메모 등 작업 메모 (POST /rooms/{roomId}/stayover-cleanings의 notes).
-- 게스트 개인정보를 넣지 않는다는 운영 규칙은 애플리케이션·UI 안내로 관리한다.
ALTER TABLE housekeeping_task ADD COLUMN notes varchar(500);
