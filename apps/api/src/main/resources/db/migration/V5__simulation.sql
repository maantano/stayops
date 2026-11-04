-- P11 시뮬레이션 (docs/v4.1/02 §2 SimulationRun, §9).
-- 데이터셋은 P10 생성기(tools/simulator) 출력이며 합성 데이터만 허용한다. 업로드 후 변경하지 않는다(불변 입력 스냅샷).

CREATE TABLE simulation_dataset (
    id                 uuid          PRIMARY KEY,
    property_id        uuid          NOT NULL REFERENCES property (id),
    seed               bigint        NOT NULL,
    generator_version  varchar(40)   NOT NULL,
    config_hash        varchar(64)   NOT NULL,
    content_hash       varchar(64)   NOT NULL,
    summary            jsonb         NOT NULL,  -- 객실·예약 수, 기간 등 표시용 요약
    payload            jsonb         NOT NULL,  -- 데이터셋 원본
    created_by         uuid          NOT NULL,
    created_at         timestamptz   NOT NULL,
    CONSTRAINT simulation_dataset_unique_content UNIQUE (property_id, content_hash)
);

CREATE TABLE simulation_run (
    id              uuid          PRIMARY KEY,
    property_id     uuid          NOT NULL REFERENCES property (id),
    dataset_id      uuid          NOT NULL REFERENCES simulation_dataset (id),
    seed            bigint        NOT NULL,     -- 데이터셋 seed (엔진 자체는 난수를 쓰지 않는다)
    engine_version  varchar(40)   NOT NULL,
    policy_version  varchar(40)   NOT NULL,
    scenarios       jsonb         NOT NULL,     -- 실행한 정책·파라미터 (첫 번째가 기준)
    results         jsonb         NOT NULL,     -- 정책별 KPI + 기준 대비 차이
    status          varchar(20)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    created_by      uuid          NOT NULL,
    started_at      timestamptz   NOT NULL,
    completed_at    timestamptz,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT simulation_run_completed_has_time CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE INDEX simulation_run_property_created_idx ON simulation_run (property_id, created_at DESC, id);
CREATE INDEX simulation_dataset_property_created_idx ON simulation_dataset (property_id, created_at DESC, id);
