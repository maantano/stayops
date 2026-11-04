-- StayOps core schema (docs/v4.1/02_DOMAIN_AND_API.md §2, §10)
-- 규칙: 모든 시각은 timestamptz(UTC instant). enum은 varchar + CHECK. 이미 적용된 migration은 수정하지 않는다.

CREATE EXTENSION IF NOT EXISTS btree_gist; -- ADR-004 exclusion constraint

-- ---------------------------------------------------------------------------
-- property
-- ---------------------------------------------------------------------------
CREATE TABLE property (
    id                          uuid         PRIMARY KEY,
    name                        varchar(100) NOT NULL,
    timezone                    varchar(64)  NOT NULL, -- IANA zone id
    default_check_in_time       time         NOT NULL,
    default_check_out_time      time         NOT NULL,
    operational_buffer_minutes  integer      NOT NULL CHECK (operational_buffer_minutes >= 0),
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    version                     bigint       NOT NULL
);

-- ---------------------------------------------------------------------------
-- room
-- ---------------------------------------------------------------------------
CREATE TABLE room (
    id                       uuid         PRIMARY KEY,
    property_id              uuid         NOT NULL REFERENCES property (id),
    room_number              varchar(20)  NOT NULL,
    room_type                varchar(30)  NOT NULL,
    floor                    integer      NOT NULL,
    operational_status       varchar(30)  NOT NULL CHECK (operational_status IN
        ('VACANT_DIRTY', 'CLEANING', 'INSPECTING', 'READY', 'OCCUPIED', 'STAYOVER_CLEANING')),
    availability_status      varchar(30)  NOT NULL CHECK (availability_status IN ('AVAILABLE', 'OUT_OF_SERVICE')),
    availability_reason      varchar(500),
    availability_changed_at  timestamptz,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    version                  bigint       NOT NULL,
    CONSTRAINT room_number_unique_per_property UNIQUE (property_id, room_number),
    CONSTRAINT room_property_id_unique UNIQUE (property_id, id), -- composite FK 대상
    CONSTRAINT room_oos_requires_reason CHECK (
        availability_status = 'AVAILABLE' OR (availability_reason IS NOT NULL AND btrim(availability_reason) <> ''))
);

-- ---------------------------------------------------------------------------
-- reservation  (게스트 개인정보 컬럼 없음: 합성 데이터만 사용)
-- ---------------------------------------------------------------------------
CREATE TABLE reservation (
    id                      uuid         PRIMARY KEY,
    property_id             uuid         NOT NULL,
    room_id                 uuid         NOT NULL,
    status                  varchar(20)  NOT NULL CHECK (status IN
        ('CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW')),
    scheduled_check_in_at   timestamptz  NOT NULL,
    scheduled_check_out_at  timestamptz  NOT NULL,
    actual_check_in_at      timestamptz,
    actual_check_out_at     timestamptz,
    guest_count             integer      NOT NULL CHECK (guest_count > 0),
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    version                 bigint       NOT NULL,
    CONSTRAINT reservation_room_same_property FOREIGN KEY (property_id, room_id) REFERENCES room (property_id, id),
    CONSTRAINT reservation_interval_valid CHECK (scheduled_check_in_at < scheduled_check_out_at),
    CONSTRAINT reservation_checked_in_has_time CHECK (
        status NOT IN ('CHECKED_IN', 'CHECKED_OUT') OR actual_check_in_at IS NOT NULL),
    CONSTRAINT reservation_checked_out_has_time CHECK (
        status <> 'CHECKED_OUT' OR actual_check_out_at IS NOT NULL),
    CONSTRAINT reservation_no_overlap EXCLUDE USING gist (
        room_id WITH =,
        tstzrange(scheduled_check_in_at, scheduled_check_out_at, '[)') WITH &&
    ) WHERE (status IN ('CONFIRMED', 'CHECKED_IN'))
);

CREATE UNIQUE INDEX reservation_one_checked_in_per_room ON reservation (room_id) WHERE status = 'CHECKED_IN';
CREATE INDEX reservation_property_check_in_idx ON reservation (property_id, scheduled_check_in_at, id);

-- ---------------------------------------------------------------------------
-- staff / staff_shift
-- ---------------------------------------------------------------------------
CREATE TABLE staff (
    id            uuid         PRIMARY KEY,
    property_id   uuid         NOT NULL REFERENCES property (id),
    display_name  varchar(50)  NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('FRONT_DESK', 'HOUSEKEEPER', 'INSPECTOR', 'MANAGER')),
    active        boolean      NOT NULL,
    skills        text[]       NOT NULL DEFAULT '{}',
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL,
    CONSTRAINT staff_property_id_unique UNIQUE (property_id, id)
);

CREATE TABLE staff_shift (
    id             uuid         PRIMARY KEY,
    staff_id       uuid         NOT NULL REFERENCES staff (id),
    starts_at      timestamptz  NOT NULL,
    ends_at        timestamptz  NOT NULL,
    break_minutes  integer      NOT NULL CHECK (break_minutes >= 0),
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL,
    CONSTRAINT staff_shift_interval_valid CHECK (starts_at < ends_at),
    CONSTRAINT staff_shift_no_overlap EXCLUDE USING gist (
        staff_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

-- ---------------------------------------------------------------------------
-- housekeeping_task
-- ---------------------------------------------------------------------------
CREATE TABLE housekeeping_task (
    id                          uuid          PRIMARY KEY,
    property_id                 uuid          NOT NULL,
    room_id                     uuid          NOT NULL,
    reservation_id              uuid          REFERENCES reservation (id),
    source_task_id              uuid          REFERENCES housekeeping_task (id),
    type                        varchar(30)   NOT NULL CHECK (type IN
        ('CHECKOUT_CLEANING', 'STAYOVER_CLEANING', 'RE_CLEANING', 'INSPECTION')),
    workflow_group              varchar(30)   NOT NULL CHECK (workflow_group IN ('ROOM_WORKFLOW')),
    origin                      varchar(30)   NOT NULL CHECK (origin IN
        ('CHECKOUT_COMMAND', 'CLEANING_COMPLETED', 'INSPECTION_FAILED', 'STAYOVER_REQUEST', 'MANAGER_REWORK')),
    origin_reason               varchar(500),
    status                      varchar(20)   NOT NULL CHECK (status IN
        ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'BLOCKED', 'COMPLETED', 'CANCELLED')),
    status_before_blocked       varchar(20)   CHECK (status_before_blocked IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')),
    scheduled_at                timestamptz   NOT NULL,
    due_at                      timestamptz,
    estimated_duration_minutes  integer       NOT NULL CHECK (estimated_duration_minutes > 0),
    duration_source             varchar(20)   NOT NULL CHECK (duration_source IN ('USER_INPUT', 'CONFIG_DEFAULT')),
    actual_started_at           timestamptz,
    actual_completed_at         timestamptz,
    cancellation_code           varchar(20)   CHECK (cancellation_code IN ('MANUAL', 'CHECKOUT')),
    cancellation_reason         varchar(500),
    created_by                  uuid          NOT NULL, -- actor id (인증 방식 OPEN → FK 없음)
    created_at                  timestamptz   NOT NULL,
    updated_at                  timestamptz   NOT NULL,
    version                     bigint        NOT NULL,
    CONSTRAINT task_room_same_property FOREIGN KEY (property_id, room_id) REFERENCES room (property_id, id),
    -- 02 §10.2 상태-필드 불변식
    CONSTRAINT task_blocked_iff_status_before_blocked CHECK ((status = 'BLOCKED') = (status_before_blocked IS NOT NULL)),
    CONSTRAINT task_cancelled_iff_cancellation_code CHECK ((status = 'CANCELLED') = (cancellation_code IS NOT NULL)),
    CONSTRAINT task_manual_cancel_requires_reason CHECK (
        cancellation_code IS DISTINCT FROM 'MANUAL' OR (cancellation_reason IS NOT NULL AND btrim(cancellation_reason) <> '')),
    CONSTRAINT task_rework_requires_reason CHECK (
        origin <> 'MANAGER_REWORK' OR (origin_reason IS NOT NULL AND btrim(origin_reason) <> '')),
    CONSTRAINT task_in_progress_has_start CHECK (status <> 'IN_PROGRESS' OR actual_started_at IS NOT NULL),
    CONSTRAINT task_completed_has_times CHECK (
        status <> 'COMPLETED' OR (actual_started_at IS NOT NULL AND actual_completed_at IS NOT NULL)),
    CONSTRAINT task_completed_after_start CHECK (
        actual_completed_at IS NULL OR (actual_started_at IS NOT NULL AND actual_completed_at >= actual_started_at)),
    -- type × origin × 연결 필드 (02 §2 HousekeepingTask sourceTaskId 의미)
    CONSTRAINT task_type_origin_consistent CHECK (
           (type = 'CHECKOUT_CLEANING' AND origin = 'CHECKOUT_COMMAND' AND reservation_id IS NOT NULL AND source_task_id IS NULL)
        OR (type = 'STAYOVER_CLEANING' AND origin = 'STAYOVER_REQUEST' AND reservation_id IS NOT NULL AND source_task_id IS NULL)
        OR (type = 'INSPECTION' AND origin IN ('CLEANING_COMPLETED', 'MANAGER_REWORK') AND source_task_id IS NOT NULL)
        OR (type = 'RE_CLEANING' AND origin = 'INSPECTION_FAILED' AND source_task_id IS NOT NULL)
        OR (type = 'RE_CLEANING' AND origin = 'MANAGER_REWORK' AND source_task_id IS NULL)
    )
);

-- 객실당 active workflow task 1개 (02 §2.9, ADR-003 최종 방어선)
CREATE UNIQUE INDEX task_one_active_workflow_per_room ON housekeeping_task (room_id, workflow_group)
    WHERE status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'BLOCKED');
-- checkout cleaning은 예약당 1개. stayover는 예약당 여러 번 가능하므로 제외
CREATE UNIQUE INDEX task_one_checkout_cleaning_per_reservation ON housekeeping_task (reservation_id)
    WHERE type = 'CHECKOUT_CLEANING';
CREATE INDEX task_property_status_scheduled_idx ON housekeeping_task (property_id, status, scheduled_at, id);
CREATE INDEX task_room_created_idx ON housekeeping_task (room_id, created_at);
CREATE INDEX task_source_task_idx ON housekeeping_task (source_task_id) WHERE source_task_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- task_assignment (append-only 이력)
-- ---------------------------------------------------------------------------
CREATE TABLE task_assignment (
    id                 uuid          PRIMARY KEY,
    task_id            uuid          NOT NULL REFERENCES housekeeping_task (id),
    staff_id           uuid          NOT NULL REFERENCES staff (id),
    assigned_at        timestamptz   NOT NULL,
    assigned_by        uuid          NOT NULL,
    unassigned_at      timestamptz,
    unassigned_reason  varchar(40)   CHECK (unassigned_reason IN
        ('REASSIGNED', 'ASSIGNEE_INVALID_ON_RESUME', 'TASK_CANCELLED', 'TASK_COMPLETED')),
    unassigned_note    varchar(500),
    CONSTRAINT assignment_unassigned_pair CHECK ((unassigned_at IS NULL) = (unassigned_reason IS NULL)),
    CONSTRAINT assignment_interval_valid CHECK (unassigned_at IS NULL OR unassigned_at >= assigned_at)
);

CREATE UNIQUE INDEX assignment_one_active_per_task ON task_assignment (task_id) WHERE unassigned_at IS NULL;
CREATE INDEX assignment_staff_active_idx ON task_assignment (staff_id) WHERE unassigned_at IS NULL;

-- ---------------------------------------------------------------------------
-- task_blocker
-- ---------------------------------------------------------------------------
CREATE TABLE task_blocker (
    id                       uuid          PRIMARY KEY,
    task_id                  uuid          NOT NULL REFERENCES housekeeping_task (id),
    type                     varchar(30)   NOT NULL CHECK (type IN
        ('DND', 'LINEN_SHORTAGE', 'SUPPLY_SHORTAGE', 'MAINTENANCE', 'STAFF_UNAVAILABLE', 'OTHER')),
    description              varchar(500),
    blocked_at               timestamptz   NOT NULL,
    estimated_resolution_at  timestamptz,
    resolved_at              timestamptz,
    resolved_by              uuid,
    resolution_type          varchar(20)   CHECK (resolution_type IN ('RESOLVED', 'TASK_CANCELLED')),
    resolution_note          varchar(500),
    CONSTRAINT blocker_resolution_consistent CHECK (
        (resolved_at IS NULL AND resolution_type IS NULL AND resolved_by IS NULL)
        OR (resolved_at IS NOT NULL AND resolution_type IS NOT NULL AND resolved_by IS NOT NULL)),
    CONSTRAINT blocker_other_requires_description CHECK (
        type <> 'OTHER' OR (description IS NOT NULL AND btrim(description) <> '')),
    CONSTRAINT blocker_resolved_after_blocked CHECK (resolved_at IS NULL OR resolved_at >= blocked_at)
);

CREATE INDEX blocker_task_active_idx ON task_blocker (task_id) WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------------------
-- inspection_record
-- ---------------------------------------------------------------------------
CREATE TABLE inspection_record (
    id                  uuid          PRIMARY KEY,
    task_id             uuid          NOT NULL UNIQUE REFERENCES housekeeping_task (id),
    result              varchar(10)   NOT NULL CHECK (result IN ('PASSED', 'FAILED')),
    failure_reason      varchar(500),
    checklist_snapshot  jsonb         NOT NULL,
    inspected_by        uuid          NOT NULL REFERENCES staff (id),
    inspected_at        timestamptz   NOT NULL,
    CONSTRAINT inspection_failed_requires_reason CHECK (
        result <> 'FAILED' OR (failure_reason IS NOT NULL AND btrim(failure_reason) <> ''))
);

-- ---------------------------------------------------------------------------
-- operational_event (append-only)
-- ---------------------------------------------------------------------------
CREATE TABLE operational_event (
    id                      uuid          PRIMARY KEY,
    property_id             uuid          NOT NULL REFERENCES property (id),
    aggregate_type          varchar(40)   NOT NULL,
    aggregate_id            uuid          NOT NULL,
    event_type              varchar(60)   NOT NULL,
    aggregate_version       bigint        NOT NULL,
    actor_type              varchar(20)   NOT NULL CHECK (actor_type IN ('STAFF', 'SYSTEM')),
    actor_id                uuid,
    correlation_id          uuid          NOT NULL,
    idempotency_key         varchar(100),
    payload_schema_version  integer       NOT NULL CHECK (payload_schema_version > 0),
    payload                 jsonb         NOT NULL,
    occurred_at             timestamptz   NOT NULL,
    CONSTRAINT event_staff_actor_has_id CHECK (actor_type <> 'STAFF' OR actor_id IS NOT NULL)
);

CREATE INDEX event_aggregate_idx ON operational_event (aggregate_type, aggregate_id, occurred_at, id);
CREATE INDEX event_property_time_idx ON operational_event (property_id, occurred_at, id);
CREATE INDEX event_correlation_idx ON operational_event (correlation_id);
