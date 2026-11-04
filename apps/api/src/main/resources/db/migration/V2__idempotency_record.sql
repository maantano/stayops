-- Idempotency-Key 결과 저장소 (docs/v4.1/02 §7, 04 §3)
-- 성공한 command의 최초 응답만 저장한다. 실패한 command는 rollback되므로 저장되지 않고, 재시도 시 다시 평가된다.

CREATE TABLE idempotency_record (
    id               uuid          PRIMARY KEY,
    actor_id         uuid          NOT NULL,
    endpoint         varchar(200)  NOT NULL,
    idempotency_key  varchar(100)  NOT NULL,
    request_hash     varchar(64)   NOT NULL,
    response_status  integer       NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_body    jsonb         NOT NULL,
    correlation_id   uuid          NOT NULL,
    created_at       timestamptz   NOT NULL,
    CONSTRAINT idempotency_record_scope_key UNIQUE (actor_id, endpoint, idempotency_key)
);

CREATE INDEX idempotency_record_created_idx ON idempotency_record (created_at);
