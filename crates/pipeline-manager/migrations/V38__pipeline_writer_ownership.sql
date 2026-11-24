CREATE TABLE pipeline_writer_owner (
    tenant_id UUID NOT NULL,
    pipeline_name TEXT NOT NULL,
    pipeline_id UUID NOT NULL,
    pipeline_version BIGINT NOT NULL,
    claim_uid TEXT NOT NULL,
    scope_sha256 TEXT NOT NULL,
    reservation_id TEXT NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    nonce TEXT NOT NULL,
    phase TEXT NOT NULL CHECK (phase IN ('prepared', 'starting', 'bound')),
    identity JSONB,
    identity_sha256 TEXT,
    deployment_id UUID,
    fence_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, pipeline_name),
    UNIQUE (pipeline_id)
);
