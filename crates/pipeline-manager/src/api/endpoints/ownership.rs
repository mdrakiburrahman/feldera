use crate::api::main::ServerState;
use crate::config::BasePath;
use crate::db::types::tenant::TenantId;
use crate::db::types::role::Role;
use actix_web::{
    body::{BoxBody, MessageBody},
    dev::{ServiceRequest, ServiceResponse},
    get, middleware::Next, post, put, web, HttpMessage, HttpResponse,
};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fmt::Write;
use uuid::Uuid;

#[derive(Deserialize)]
pub(crate) struct Prepare {
    claim_uid: String,
    scope_sha256: String,
    reservation_id: String,
    generation: i64,
    nonce: String,
    pipeline_id: Uuid,
    pipeline_version: i64,
}

#[derive(Deserialize)]
pub(crate) struct Transition {
    claim_uid: String,
    scope_sha256: String,
    reservation_id: String,
    identity: BTreeMap<String, Value>,
    current_generation: i64,
    current_nonce: String,
    next_generation: Option<i64>,
    next_nonce: Option<String>,
}

#[derive(Clone)]
pub(crate) struct WriterStart {
    pub generation: i64,
    pub nonce: String,
}

fn conflict(message: &str) -> HttpResponse {
    HttpResponse::Conflict().json(json!({
        "message": message,
        "error_code": "WriterOwnershipConflict",
        "details": {}
    }))
}

fn valid_text(value: &str) -> bool {
    !value.is_empty() && !value.chars().any(|c| matches!(c, '\r' | '\n' | '\u{0085}' | '\u{2028}' | '\u{2029}'))
}

fn valid_hash(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

fn identity_hash(identity: &BTreeMap<String, Value>) -> Result<String, &'static str> {
    const TEXT: &[&str] = &[
        "cluster_uid", "namespace_uid", "pipeline_name", "pipeline_id", "deployment_name",
        "deployment_uid", "incarnation", "runtime_pod_uid", "runtime_container_id",
        "runtime_image_id", "program_sha256", "frozen_admission_sha256",
        "destination_inventory_sha256",
    ];
    if identity.len() != TEXT.len() + 2 {
        return Err("invalid identity fields");
    }
    for field in TEXT {
        let value = identity.get(*field).and_then(Value::as_str).ok_or("invalid identity field")?;
        if !valid_text(value) {
            return Err("identity text must be nonempty and single-line");
        }
    }
    for field in ["program_sha256", "frozen_admission_sha256", "destination_inventory_sha256"] {
        if !valid_hash(identity[field].as_str().unwrap()) {
            return Err("invalid identity digest");
        }
    }
    for field in ["program_version", "deployment_generation"] {
        if identity.get(field).and_then(Value::as_i64).is_none_or(|v| v <= 0) {
            return Err("invalid identity version");
        }
    }
    let json = serde_json::to_string(identity).map_err(|_| "cannot encode identity")?;
    let mut ascii = String::new();
    for ch in json.chars() {
        if ch <= '\u{007e}' {
            ascii.push(ch);
        } else {
            for unit in ch.encode_utf16(&mut [0; 2]) {
                write!(ascii, "\\u{unit:04x}").map_err(|_| "cannot encode identity")?;
            }
        }
    }
    Ok(hex::encode(Sha256::digest(ascii.as_bytes())))
}

async fn client(state: &web::Data<ServerState>) -> Result<deadpool_postgres::Client, actix_web::Error> {
    let pool = state.db.lock().await.pool.clone();
    pool.get().await.map_err(actix_web::error::ErrorServiceUnavailable)
}

pub(crate) async fn middleware(
    req: ServiceRequest,
    next: Next<impl MessageBody + 'static>,
) -> Result<ServiceResponse<BoxBody>, actix_web::Error> {
    let path = BasePath::strip_from(&req, req.match_info().as_str()).to_string();
    let method = req.method();
    let Some((pattern, role)) = crate::api::rbac::classify(method.as_str(), &path) else {
        return Ok(req.into_response(HttpResponse::Forbidden().finish()));
    };
    if pattern == "/v0/pipelines/{pipeline_name}/writer-owner" && *method == actix_web::http::Method::GET {
        return Ok(next.call(req).await?.map_into_boxed_body());
    }
    if *method != actix_web::http::Method::GET || role == Role::Write {
        return guard(req, next, pattern, &path).await;
    }
    Ok(next.call(req).await?.map_into_boxed_body())
}

pub(crate) async fn guard(
    req: ServiceRequest,
    next: Next<impl MessageBody + 'static>,
    pattern: &str,
    path: &str,
) -> Result<ServiceResponse<BoxBody>, actix_web::Error> {
    let Some(name) = path.strip_prefix("/v0/pipelines/").and_then(|p| p.split('/').next()) else {
        return Ok(next.call(req).await?.map_into_boxed_body());
    };
    let principal = req.extensions().get::<crate::auth::AuthenticatedPrincipal>().cloned()
        .ok_or_else(|| actix_web::error::ErrorForbidden("missing tenant"))?;
    let state = req.app_data::<web::Data<ServerState>>().cloned()
        .ok_or_else(|| actix_web::error::ErrorServiceUnavailable("manager state unavailable"))?;
    let pool = state.db.lock().await.writer_owner_pool.clone();
    let mut db = pool.get().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let tx = db.transaction().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let lock_key = format!("feldera-writer-owner:{}:{name}", principal.acting_tenant.0);
    tx.query_one("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", &[&lock_key])
        .await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let row = tx.query_opt(
        "SELECT phase,generation,nonce FROM pipeline_writer_owner WHERE tenant_id=$1 AND pipeline_name=$2",
        &[&principal.acting_tenant.0, &name],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let owner_route = pattern.starts_with("/v0/pipelines/{pipeline_name}/writer-owner");
    if let Some(row) = row && !owner_route {
        let phase: String = row.get(0);
        let generation: i64 = row.get(1);
        let nonce: String = row.get(2);
        let header_generation = req.headers().get("x-feldera-owner-generation")
            .and_then(|h| h.to_str().ok()).and_then(|h| h.parse::<i64>().ok());
        let header_nonce = req.headers().get("x-feldera-owner-nonce").and_then(|h| h.to_str().ok());
        if pattern == "/v0/pipelines/{pipeline_name}/start-if-current"
            && phase == "prepared" && header_generation == Some(generation)
            && header_nonce == Some(nonce.as_str())
        {
            req.extensions_mut().insert(WriterStart { generation, nonce });
        } else {
            return Ok(req.into_response(conflict("protected pipeline is read-only")));
        }
    }
    let result = next.call(req).await;
    tx.rollback().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    Ok(result?.map_into_boxed_body())
}

/// Register protection before a newly compiled pipeline's first start.
#[put("/pipelines/{pipeline_name}/writer-owner")]
pub(crate) async fn prepare(
    state: web::Data<ServerState>,
    tenant: actix_web::web::ReqData<TenantId>,
    name: web::Path<String>,
    body: web::Json<Prepare>,
) -> Result<HttpResponse, actix_web::Error> {
    let name = name.into_inner();
    let b = body.into_inner();
    if !valid_text(&b.claim_uid) || !valid_hash(&b.scope_sha256)
        || !valid_text(&b.reservation_id) || !valid_text(&b.nonce) || b.generation <= 0
    {
        return Ok(conflict("invalid ownership binding"));
    }
    let mut client = client(&state).await?;
    let tx = client.transaction().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let row = tx.query_opt(
        "SELECT id, version, runtime_config, program_status, deployment_resources_status, \
         deployment_resources_desired_status, deployment_config, storage_status FROM pipeline \
         WHERE tenant_id=$1 AND name=$2 FOR UPDATE", &[&tenant.0, &name],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let Some(row) = row else { return Ok(conflict("pipeline not found")); };
    let id: Uuid = row.get(0);
    let version: i64 = row.get(1);
    let config: String = row.get(2);
    let program: String = row.get(3);
    let status: String = row.get(4);
    let desired: String = row.get(5);
    let prior_deployment: Option<String> = row.get(6);
    let storage_status: String = row.get(7);
    let config: Value = serde_json::from_str(&config).map_err(actix_web::error::ErrorInternalServerError)?;
    let fence = config.get("writer_incarnation_fence").and_then(Value::as_str);
    let valid_fence = fence.is_some_and(|s| std::path::Path::new(s).is_absolute());
    if id != b.pipeline_id || version != b.pipeline_version || program != "success"
        || status != "stopped" || desired != "stopped" || storage_status != "cleared"
        || prior_deployment.is_some()
        || !valid_fence || config.get("hosts").and_then(Value::as_u64).unwrap_or(1) != 1
    {
        return Ok(conflict("pipeline is not a fresh stopped compiled single-host guarded pipeline"));
    }
    let fence_id = Uuid::now_v7();
    let inserted = tx.execute(
        "INSERT INTO pipeline_writer_owner (tenant_id,pipeline_name,pipeline_id,pipeline_version,\
         claim_uid,scope_sha256,reservation_id,generation,nonce,phase,fence_id) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,'prepared',$10) ON CONFLICT DO NOTHING",
        &[&tenant.0,&name,&id,&version,&b.claim_uid,&b.scope_sha256,&b.reservation_id,
          &b.generation,&b.nonce,&fence_id],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    if inserted != 1 { return Ok(conflict("pipeline is already protected")); }
    tx.commit().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    Ok(HttpResponse::Created().json(json!({"phase":"prepared","fence_id":fence_id})))
}

#[get("/pipelines/{pipeline_name}/writer-owner")]
pub(crate) async fn read(
    state: web::Data<ServerState>,
    tenant: web::ReqData<TenantId>,
    name: web::Path<String>,
) -> Result<HttpResponse, actix_web::Error> {
    let client = client(&state).await?;
    let row = client.query_opt(
        "SELECT claim_uid,scope_sha256,reservation_id,generation,nonce,phase,identity_sha256,\
         fence_id,pipeline_id,deployment_id FROM pipeline_writer_owner WHERE tenant_id=$1 AND pipeline_name=$2",
        &[&tenant.0, &name.into_inner()],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let Some(r) = row else { return Ok(HttpResponse::NotFound().finish()); };
    let phase: String = r.get(5);
    let nonce = (phase == "bound").then(|| r.get::<_, String>(4));
    Ok(HttpResponse::Ok().json(json!({
        "claim_uid":r.get::<_,String>(0), "scope_sha256":r.get::<_,String>(1),
        "reservation_id":r.get::<_,String>(2), "generation":r.get::<_,i64>(3),
        "nonce":nonce, "phase":phase,
        "identity_sha256":r.get::<_,Option<String>>(6), "fence_id":r.get::<_,Uuid>(7),
        "pipeline_id":r.get::<_,Uuid>(8), "deployment_id":r.get::<_,Option<Uuid>>(9)
    })))
}

async fn transition(
    state: web::Data<ServerState>,
    http: web::Data<awc::Client>,
    tenant: web::ReqData<TenantId>,
    name: web::Path<String>,
    body: web::Json<Transition>,
    is_advance: bool,
) -> Result<HttpResponse, actix_web::Error> {
    let name = name.into_inner();
    let b = body.into_inner();
    let hash = match identity_hash(&b.identity) {
        Ok(hash) => hash,
        Err(message) => return Ok(conflict(message)),
    };
    if b.identity["pipeline_name"].as_str() != Some(&name)
        || !valid_text(&b.claim_uid) || !valid_hash(&b.scope_sha256)
        || !valid_text(&b.reservation_id) || !valid_text(&b.current_nonce)
    {
        return Ok(conflict("invalid ownership binding"));
    }
    let mut db = client(&state).await?;
    let tx = db.transaction().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let row = tx.query_opt(
        "SELECT claim_uid,scope_sha256,reservation_id,generation,nonce,phase,identity_sha256,\
         pipeline_id,pipeline_version,deployment_id FROM pipeline_writer_owner \
         WHERE tenant_id=$1 AND pipeline_name=$2 FOR UPDATE",
        &[&tenant.0, &name],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let Some(row) = row else { return Ok(conflict("pipeline is not protected")); };
    let pipeline_id: Uuid = row.get(7);
    let pipeline_version: i64 = row.get(8);
    let old_deployment: Option<Uuid> = row.get(9);
    let prior_hash: Option<String> = row.get(6);
    if row.get::<_, String>(0) != b.claim_uid || row.get::<_, String>(1) != b.scope_sha256
        || row.get::<_, String>(2) != b.reservation_id || row.get::<_, i64>(3) != b.current_generation
        || row.get::<_, String>(4) != b.current_nonce
        || row.get::<_, String>(5) != if is_advance { "bound" } else { "starting" }
        || (is_advance && prior_hash.as_deref() != Some(hash.as_str()))
        || b.identity["pipeline_id"].as_str().and_then(|v| Uuid::parse_str(v).ok()) != Some(pipeline_id)
    {
        return Ok(conflict("ownership or pipeline identity mismatch"));
    }
    let pipeline = tx.query_opt(
        "SELECT id,version,program_version,deployment_id,deployment_resources_status,\
         deployment_runtime_status,program_code FROM pipeline WHERE tenant_id=$1 AND name=$2",
        &[&tenant.0, &name],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    let Some(p) = pipeline else { return Ok(conflict("pipeline missing")); };
    let deployment: Option<Uuid> = p.get(3);
    if p.get::<_,Uuid>(0) != pipeline_id || p.get::<_,i64>(1) != pipeline_version
        || b.identity["program_version"].as_i64() != Some(p.get(2))
        || deployment.is_none() || (is_advance && deployment != old_deployment)
        || p.get::<_,String>(4) != "provisioned"
        || p.get::<_,Option<String>>(5).as_deref() != Some("running")
        || b.identity["program_sha256"].as_str()
            != Some(hex::encode(Sha256::digest(p.get::<_, String>(6).as_bytes())).as_str())
    {
        return Ok(conflict("pipeline deployment is not the bound running deployment"));
    }
    let response = state.runner.forward_http_request_to_pipeline_by_name(
        http.as_ref(), *tenant, &name, actix_web::http::Method::GET, "stats", "", None, None,
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    if !response.status().is_success() {
        return Ok(conflict("unable to attest runtime incarnation"));
    }
    let bytes = actix_web::body::to_bytes(response.into_body()).await
        .map_err(actix_web::error::ErrorServiceUnavailable)?;
    let stats: Value = serde_json::from_slice(&bytes).map_err(actix_web::error::ErrorServiceUnavailable)?;
    if stats.pointer("/global_metrics/incarnation_uuid").and_then(Value::as_str)
        != b.identity["incarnation"].as_str()
    {
        return Ok(conflict("runtime incarnation mismatch"));
    }
    let unchanged = tx.query_opt(
        "SELECT id FROM pipeline WHERE tenant_id=$1 AND name=$2 AND id=$3 AND version=$4 \
         AND program_version=$5 AND deployment_id=$6 AND deployment_resources_status='provisioned' \
         AND deployment_runtime_status='running'",
        &[&tenant.0, &name, &pipeline_id, &pipeline_version,
          &b.identity["program_version"].as_i64().unwrap(), &deployment],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    if unchanged.is_none() {
        return Ok(conflict("pipeline changed while attesting incarnation"));
    }
    let (generation, nonce) = if is_advance {
        let Some(generation) = b.next_generation else { return Ok(conflict("missing next generation")); };
        let Some(nonce) = b.next_nonce else { return Ok(conflict("missing next nonce")); };
        if b.current_generation.checked_add(1) != Some(generation)
            || !valid_text(&nonce) || nonce == b.current_nonce
        {
            return Ok(conflict("invalid successor"));
        }
        (generation, nonce)
    } else {
        if b.next_generation.is_some() || b.next_nonce.is_some() {
            return Ok(conflict("unexpected successor"));
        }
        (b.current_generation, b.current_nonce)
    };
    let fence_id = Uuid::now_v7();
    tx.execute(
        "UPDATE pipeline_writer_owner SET phase='bound',identity=$3::text::jsonb,identity_sha256=$4,\
         deployment_id=$5,generation=$6,nonce=$7,fence_id=$8 WHERE tenant_id=$1 AND pipeline_name=$2",
        &[&tenant.0, &name, &serde_json::to_string(&b.identity).unwrap(), &hash,
          &deployment, &generation, &nonce, &fence_id],
    ).await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    tx.commit().await.map_err(actix_web::error::ErrorServiceUnavailable)?;
    Ok(HttpResponse::Ok().json(json!({
        "claim_uid": b.claim_uid, "scope_sha256": b.scope_sha256, "reservation_id": b.reservation_id,
        "identity_sha256": hash, "generation": generation, "nonce": nonce, "fence_id": fence_id,
    })))
}

#[post("/pipelines/{pipeline_name}/writer-owner/bind")]
pub(crate) async fn bind(
    state: web::Data<ServerState>, http: web::Data<awc::Client>, tenant: web::ReqData<TenantId>,
    name: web::Path<String>, body: web::Json<Transition>,
) -> Result<HttpResponse, actix_web::Error> {
    transition(state, http, tenant, name, body, false).await
}

#[post("/pipelines/{pipeline_name}/writer-owner/advance")]
pub(crate) async fn advance(
    state: web::Data<ServerState>, http: web::Data<awc::Client>, tenant: web::ReqData<TenantId>,
    name: web::Path<String>, body: web::Json<Transition>,
) -> Result<HttpResponse, actix_web::Error> {
    transition(state, http, tenant, name, body, true).await
}
