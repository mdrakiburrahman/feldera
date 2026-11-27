import json
import subprocess
import sys
from decimal import Decimal
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pyarrow as pa
import pytest

from dbt.adapters.feldera import seed_evidence
from dbt.adapters.feldera.impl import FelderaAdapter


def test_v1_digest_matches_independent_process_and_binds_multiplicity():
    schema = pa.schema([("name", pa.string()), ("amount", pa.float64()), ("n", pa.int64())])
    fields = [
        {"name": "name", "columntype": {"type": "VARCHAR", "nullable": True}},
        {"name": "amount", "columntype": {"type": "DOUBLE", "nullable": True}},
        {"name": "n", "columntype": {"type": "BIGINT", "nullable": True}},
    ]
    batch = pa.record_batch(
        [pa.array(["red", "red", None]), pa.array([0.1, 0.1, 2.0]), pa.array([7, 7, None])], schema=schema
    )
    rows = (tuple(column[i] for column in batch.columns) for i in range(batch.num_rows))
    digest = seed_evidence._digest(schema, fields, rows)
    script = """
import hashlib, json, struct
def encode(x): return json.dumps(x,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def sha(x): return hashlib.sha256(encode(x)).hexdigest()
schema = [
  {'name':'name','feldera_name':'name','arrow_type':'string','feldera_type':{'type':'VARCHAR','nullable':True}},
  {'name':'amount','feldera_name':'amount','arrow_type':'double','feldera_type':{'type':'DOUBLE','nullable':True}},
  {'name':'n','feldera_name':'n','arrow_type':'int64','feldera_type':{'type':'BIGINT','nullable':True}}
]
def number(x): return ['float64',struct.pack('>d',x).hex()]
a = sha([['string','red'],number(0.1),['int','7']])
b = sha([None,number(2.0),None])
rows = [{'sha256':h,'count':c} for h,c in sorted([(a,2),(b,1)])]
print(sha({'version':1,'schema':schema,'rows':rows}))
"""
    independent = subprocess.check_output([sys.executable, "-c", script], text=True).strip()
    assert digest["multiset_sha256"] == independent
    assert digest["row_count"] == 3
    assert sorted(item["count"] for item in digest["row_sha256_multiplicities"]) == [1, 2]
    assert seed_evidence.verify_seed_digest(schema, fields, [batch], digest)
    assert not seed_evidence.verify_seed_digest(schema, fields, [batch.slice(0, 2)], digest)
    assert "red" not in json.dumps(digest)
    altered_fields = [fields[0], fields[1], {"name": "n", "columntype": {"type": "INTEGER", "nullable": True}}]
    assert not seed_evidence.verify_seed_digest(schema, altered_fields, [batch], digest)


def test_digest_rejects_nonfinite_and_unsupported_types():
    schema = pa.schema([("n", pa.float64())])
    fields = [{"name": "n", "columntype": {"type": "DOUBLE"}}]
    with pytest.raises(ValueError, match="Non-finite"):
        seed_evidence._digest(schema, fields, [(pa.scalar(float("nan")),)])
    nested = pa.schema([("n", pa.list_(pa.int64()))])
    with pytest.raises(ValueError, match="Unsupported"):
        seed_evidence._digest(nested, fields, [])


def test_decimal_and_timezone_timestamp_cross_process_fixture():
    schema = pa.schema([("ts", pa.timestamp("us", tz="UTC")), ("value", pa.decimal128(20, 6))])
    fields = [
        {"name": "ts", "columntype": {"type": "TIMESTAMP WITH TIME ZONE"}},
        {"name": "value", "columntype": {"type": "DECIMAL", "precision": 20, "scale": 6}},
    ]
    rows = [(pa.scalar(0, type=schema[0].type), pa.scalar(Decimal("0.100000"), type=schema[1].type))]
    result = seed_evidence._digest(schema, fields, rows)
    script = """
import hashlib,json
def sha(x):
    data = json.dumps(x,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
    return hashlib.sha256(data).hexdigest()
schema = [
  {'name':'ts','feldera_name':'ts','arrow_type':'timestamp[us, tz=UTC]',
   'feldera_type':{'type':'TIMESTAMP WITH TIME ZONE'}},
  {'name':'value','feldera_name':'value','arrow_type':'decimal128(20, 6)',
   'feldera_type':{'type':'DECIMAL','precision':20,'scale':6}}
]
row = sha([['temporal','0'],['decimal','0.100000']])
print(sha({'version':1,'schema':schema,'rows':[{'sha256':row,'count':1}]}))
"""
    independent = subprocess.check_output([sys.executable, "-c", script], text=True).strip()
    assert result["multiset_sha256"] == independent


def test_empty_seed_digest_includes_types_and_detects_mismatch():
    schema = pa.schema([("n", pa.int64())])
    fields = [{"name": "n", "columntype": {"type": "BIGINT", "nullable": True}}]
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, schema):
        pass
    response = Mock(raw=pa.BufferReader(sink.getvalue()))
    pipeline = Mock(name="pipeline")
    pipeline.name = "pipeline"
    pipeline.client.http.get.return_value = response
    descriptor = {"program_info": {"schema": {"inputs": [{"name": "seed", "fields": fields}]}}}
    result = seed_evidence.verify_and_digest(pipeline, "seed", [], descriptor)
    assert result["expected"] == result["observed"]
    assert result["observed"]["row_count"] == 0
    assert result["observed"]["schema"][0]["feldera_type"]["type"] == "BIGINT"
    response.close.assert_called_once()


@pytest.mark.parametrize("failure", ["parse", "transport", "fatal", "incarnation"])
def test_snapshot_rejects_input_errors_and_missing_incarnation(failure):
    client = Mock()
    client.http.get.return_value = {
        "id": "p", "version": 1, "program_version": 2, "platform_version": "v",
        "deployment_id": "d", "deployment_status": "Running", "program_status": "Success",
    }
    stats = {
        "global_metrics": {"incarnation_uuid": "i", "start_time": 42},
        "inputs": [{
            "endpoint_name": "real.endpoint", "config": {"stream": "seed"},
            "metrics": {"total_records": 1, "num_parse_errors": 0, "num_transport_errors": 0},
            "fatal_error": None,
        }],
    }
    if failure == "parse":
        stats["inputs"][0]["metrics"]["num_parse_errors"] = 1
    elif failure == "transport":
        stats["inputs"][0]["metrics"]["num_transport_errors"] = 1
    elif failure == "fatal":
        stats["inputs"][0]["fatal_error"] = "failed"
    else:
        stats["global_metrics"]["incarnation_uuid"] = None
    client.get_pipeline_stats.return_value = stats
    with pytest.raises(RuntimeError):
        seed_evidence.snapshot(client, "pipeline", {"seed"})


@pytest.mark.parametrize("failure", ["changed_incarnation", "partial", "ambiguous_ingress"])
def test_finalizer_does_not_deliver_failed_evidence(failure):
    identity = {"id": "p", "version": 1, "program_version": 1, "deployment_id": "d", "incarnation_uuid": "i"}
    descriptor = {**identity, "deployment_status": "Running"}
    client = Mock()
    client.http.get.return_value = descriptor
    adapter = Mock()
    adapter._get_client.return_value = client
    adapter._read_udfs.return_value = (None, None, None)
    adapter._convert_agate_rows.return_value = [{"x": 1}]
    adapter._push_seed_data.return_value = "token"
    hook = Mock()
    initial = (identity, {}, descriptor)
    if failure == "changed_incarnation":
        after = ({**identity, "incarnation_uuid": "changed"}, {}, descriptor)
        snapshots = [initial, after]
    elif failure == "ambiguous_ingress":
        endpoints = {key: {"stream": "seed", "metrics": {"total_records": 1}} for key in ("one", "two")}
        snapshots = [initial, initial, (identity, endpoints, descriptor)]
    else:
        snapshots = [initial]
    verified = Mock(side_effect=RuntimeError("partial seed")) if failure == "partial" else Mock(
        return_value={"expected": {}, "observed": {"row_count": 1}}
    )
    existing = None if failure == "ambiguous_ingress" else Mock()
    with (
        patch("dbt.adapters.feldera.impl.Pipeline.get", return_value=existing),
        patch("dbt.adapters.feldera.impl._pipeline_state.get_pending_seed_pipelines", return_value=["p"]),
        patch("dbt.adapters.feldera.impl._pipeline_state.pop_pending_seeds", return_value=[("seed", None, None)]),
        patch("dbt.adapters.feldera.impl._pipeline_state.deploy"),
        patch.object(seed_evidence, "get_seed_verification_hook", return_value=hook),
        patch.object(seed_evidence, "snapshot", side_effect=snapshots),
        patch.object(seed_evidence, "verify_and_digest", verified),
        pytest.raises(RuntimeError),
    ):
        FelderaAdapter.finalize_seeds(adapter, safe_seed_reuse=True)
    hook.assert_not_called()


@pytest.mark.parametrize(("stored_values", "should_push"), [([], True), ([1], False)])
def test_finalizer_reingests_nonempty_seed_only_when_storage_is_empty(stored_values, should_push):
    descriptor = {
        "id": "p", "version": 1, "program_version": 2, "deployment_id": "d",
        "deployment_status": "Running",
    }
    existing = Mock()
    existing.query_arrow.return_value = [
        pa.record_batch([pa.array(stored_values, type=pa.int64())], names=["x"])
    ]
    client = Mock()
    client.http.get.return_value = descriptor
    adapter = Mock()
    adapter._get_client.return_value = client
    adapter._get_credentials.return_value = SimpleNamespace(
        compilation_profile="dev", workers=1, timeout=30, runtime_config={}
    )
    adapter._read_udfs.return_value = (None, None, None)
    adapter._convert_agate_rows.return_value = [{"x": 1}]
    adapter._seed_rows_present.side_effect = FelderaAdapter._seed_rows_present

    with (
        patch("dbt.adapters.feldera.impl.Pipeline.get", return_value=existing),
        patch("dbt.adapters.feldera.impl._pipeline_state.get_pending_seed_pipelines", return_value=["p"]),
        patch(
            "dbt.adapters.feldera.impl._pipeline_state.pop_pending_seeds",
            return_value=[("seed", None, None)],
        ),
        patch("dbt.adapters.feldera.impl._pipeline_state.deploy", return_value=existing),
        patch("dbt.adapters.feldera.impl._pipeline_state.get_pipelines_with_views", return_value=[]),
        patch.object(seed_evidence, "get_seed_verification_hook", return_value=None),
    ):
        FelderaAdapter.finalize_seeds(adapter, safe_seed_reuse=True)

    if should_push:
        adapter.push_seed_data.assert_called_once_with("p", "seed", [{"x": 1}])
    else:
        adapter.push_seed_data.assert_not_called()
    adapter._verify_seed_rows.assert_called_once_with(existing, "seed", [{"x": 1}])
