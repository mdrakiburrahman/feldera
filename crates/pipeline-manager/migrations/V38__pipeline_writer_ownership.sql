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

CREATE FUNCTION protect_pipeline_writer() RETURNS trigger AS $$
DECLARE
    owner_phase TEXT;
BEGIN
    SELECT phase INTO owner_phase FROM pipeline_writer_owner
        WHERE tenant_id = OLD.tenant_id AND pipeline_id = OLD.id;
    IF owner_phase IS NULL THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'protected pipeline cannot be deleted';
    END IF;
    IF NEW.name IS DISTINCT FROM OLD.name
        OR NEW.runtime_config IS DISTINCT FROM OLD.runtime_config
        OR NEW.program_code IS DISTINCT FROM OLD.program_code
        OR NEW.program_config IS DISTINCT FROM OLD.program_config
        OR NEW.udf_rust IS DISTINCT FROM OLD.udf_rust
        OR NEW.udf_toml IS DISTINCT FROM OLD.udf_toml
        OR NEW.version IS DISTINCT FROM OLD.version
        OR NEW.program_version IS DISTINCT FROM OLD.program_version
    THEN
        RAISE EXCEPTION 'protected pipeline configuration cannot change';
    END IF;
    IF (NEW.deployment_resources_desired_status IS DISTINCT FROM OLD.deployment_resources_desired_status
        OR NEW.deployment_initial IS DISTINCT FROM OLD.deployment_initial
        OR NEW.bootstrap_policy IS DISTINCT FROM OLD.bootstrap_policy)
        AND NOT (
            owner_phase = 'prepared'
            AND COALESCE(current_setting('feldera.writer_start', true), '') = '1'
            AND OLD.deployment_resources_desired_status = 'stopped'
            AND NEW.deployment_resources_desired_status = 'provisioned'
        )
    THEN
        RAISE EXCEPTION 'protected pipeline lifecycle cannot change';
    END IF;
    IF NEW.deployment_config IS DISTINCT FROM OLD.deployment_config
        AND NOT (owner_phase = 'starting' AND OLD.deployment_config IS NULL
                 AND NEW.deployment_config IS NOT NULL)
    THEN
        RAISE EXCEPTION 'protected deployment configuration cannot change';
    END IF;
    IF NEW.storage_status = 'clearing' AND OLD.storage_status IS DISTINCT FROM 'clearing' THEN
        RAISE EXCEPTION 'protected storage cannot be cleared';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER protect_pipeline_writer
    BEFORE UPDATE OR DELETE ON pipeline
    FOR EACH ROW EXECUTE FUNCTION protect_pipeline_writer();
