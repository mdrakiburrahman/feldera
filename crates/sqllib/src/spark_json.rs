//! Runtime primitives for the compiler's type-directed Spark JSON expressions.
use crate::{Array, Date, F32, F64, Map, SqlDecimal, SqlString, Timestamp, TimestampTz};
use itertools::Itertools;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::value::RawValue;
use std::collections::BTreeMap;

type Object<'a> = BTreeMap<String, &'a RawValue>;

pub fn spark_from_json<T>(
    text: Option<SqlString>,
    read: impl FnOnce(Option<&RawValue>) -> Option<T>,
) -> Option<T> {
    let text = text?;
    if text.str().trim().is_empty() {
        return None;
    }
    path::with_json(text.str(), false, read)
}

pub fn spark_json_member<'a>(object: &Object<'a>, name: SqlString) -> Option<&'a RawValue> {
    object.get(name.str()).copied()
}

pub fn spark_json_read_object<T>(
    json: Option<&RawValue>,
    root: bool,
    read: impl FnOnce(&Object<'_>) -> T,
) -> Option<T> {
    match json.and_then(|v| serde_json::from_str(v.get()).ok()) {
        Some(object) => Some(read(&object)),
        // In PERMISSIVE mode a malformed top-level struct yields a row of nulls.
        None if root => Some(read(&BTreeMap::new())),
        None => None,
    }
}

pub fn spark_json_read_array<T>(
    json: Option<&RawValue>,
    singleton_object: bool,
    read: impl Fn(Option<&RawValue>) -> T,
) -> Option<Array<T>> {
    let raw = json?.get();
    if singleton_object && raw.starts_with('{') {
        return Some(vec![read(json)].into());
    }
    let array: Vec<&RawValue> = serde_json::from_str(raw).ok()?;
    Some(
        array
            .into_iter()
            .map(|v| read(Some(v)))
            .collect::<Vec<_>>()
            .into(),
    )
}

pub fn spark_json_read_map<T>(
    json: Option<&RawValue>,
    read: impl Fn(Option<&RawValue>) -> T,
) -> Option<Map<SqlString, T>> {
    let object: Object = serde_json::from_str(json?.get()).ok()?;
    Some(
        object
            .into_iter()
            .map(|(k, v)| (k.into(), read(Some(v))))
            .collect::<BTreeMap<_, _>>()
            .into(),
    )
}

pub fn spark_json_scalar<T: DeserializeOwned>(json: Option<&RawValue>) -> Option<T> {
    serde_json::from_str(json?.get()).ok()
}

pub fn spark_json_text(json: Option<&RawValue>) -> Option<SqlString> {
    let raw = json?.get();
    match raw.as_bytes().first()? {
        b'n' => None,
        b'"' => serde_json::from_str(raw).ok(),
        b'[' | b'{' => {
            // RawValue is validated JSON. Strip only insignificant whitespace, retaining object
            // member order, number precision and whitespace/escapes within quoted strings.
            let mut out = String::with_capacity(raw.len());
            let (mut quoted, mut escaped) = (false, false);
            for c in raw.chars() {
                if quoted || !c.is_ascii_whitespace() {
                    out.push(c);
                }
                if escaped {
                    escaped = false;
                } else if quoted && c == '\\' {
                    escaped = true;
                } else if c == '"' {
                    quoted = !quoted;
                }
            }
            Some(out.into())
        }
        _ => Some(SqlString::from_ref(raw)),
    }
}

mod path;
pub use path::spark_get_json_object;

pub fn spark_json_write_scalar<T: Serialize>(value: Option<T>) -> Option<SqlString> {
    Some(
        serde_json::to_string(&value?)
            .expect("SQL scalar is JSON-serializable")
            .into(),
    )
}

pub fn spark_json_write_date(value: Option<Date>) -> Option<SqlString> {
    Some(format!("\"{}\"", value?.to_date()).into())
}

fn utc_timestamp_json(value: chrono::DateTime<chrono::Utc>) -> SqlString {
    format!(
        "\"{}\"",
        value.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
    )
    .into()
}

pub fn spark_json_write_timestamp(value: Option<Timestamp>) -> Option<SqlString> {
    value.map(|v| utc_timestamp_json(v.to_dateTime()))
}

pub fn spark_json_write_timestamp_tz(value: Option<TimestampTz>) -> Option<SqlString> {
    value.map(|v| utc_timestamp_json(v.to_dateTime()))
}

pub fn spark_json_write_decimal<const P: usize, const S: usize>(
    value: Option<SqlDecimal<P, S>>,
) -> Option<SqlString> {
    let value = value?;
    // BigDecimal.toString preserves scale and switches to scientific notation below 1e-6.
    let mantissa = value.mantissa();
    let digits = mantissa.unsigned_abs().to_string();
    let exponent = digits.len() as i32 - S as i32 - 1;
    if exponent >= -6 {
        return Some(format!("{value:.S$}").into());
    }
    let sign = if mantissa < 0 { "-" } else { "" };
    let (first, rest) = digits.split_at(1);
    let dot = if rest.is_empty() { "" } else { "." };
    Some(format!("{sign}{first}{dot}{rest}E{exponent}").into())
}

fn json_float<T: num::Float + std::fmt::Display + std::fmt::LowerExp>(value: T) -> SqlString {
    if !value.is_finite() {
        return SqlString::from_ref(if value.is_nan() {
            "\"NaN\""
        } else if value.is_sign_negative() {
            "\"-Infinity\""
        } else {
            "\"Infinity\""
        });
    }
    let magnitude = value.abs().to_f64().unwrap();
    if magnitude != 0.0 && !(0.001..10_000_000.0).contains(&magnitude) {
        let scientific = format!("{value:e}");
        let (mantissa, exponent) = scientific.split_once('e').unwrap();
        let fraction = if mantissa.contains('.') { "" } else { ".0" };
        return format!("{mantissa}{fraction}E{exponent}").into();
    }
    let mut result = value.to_string();
    if !result.contains('.') {
        result.push_str(".0");
    }
    result.into()
}

pub fn spark_json_write_f32(value: Option<F32>) -> Option<SqlString> {
    value.map(|v| json_float(v.into_inner()))
}

pub fn spark_json_write_f64(value: Option<F64>) -> Option<SqlString> {
    value.map(|v| json_float(v.into_inner()))
}

pub fn spark_json_field(key: SqlString, json: Option<SqlString>) -> Option<SqlString> {
    Some(
        format!(
            "{}:{}",
            serde_json::to_string(key.str()).unwrap(),
            json?.str()
        )
        .into(),
    )
}

pub fn spark_json_write_object<T>(
    value: Option<T>,
    fields: impl FnOnce(T) -> Array<Option<SqlString>>,
) -> Option<SqlString> {
    let fields = fields(value?);
    let members = fields.iter().flatten().map(SqlString::str).join(",");
    Some(format!("{{{members}}}").into())
}

pub fn spark_json_write_array<T: Clone>(
    value: Option<Array<T>>,
    write: impl Fn(T) -> Option<SqlString>,
) -> Option<SqlString> {
    let array = value?;
    let items = array
        .iter()
        .cloned()
        .map(|v| write(v).unwrap_or_else(|| SqlString::from_ref("null")))
        .format_with(",", |s, f| f(&s.str()));
    Some(format!("[{items}]").into())
}

pub fn spark_json_write_map<T: Clone>(
    value: Option<Map<SqlString, T>>,
    write: impl Fn(T) -> Option<SqlString>,
) -> Option<SqlString> {
    let members = value?
        .iter()
        .map(|(k, v)| {
            let value = write(v.clone());
            format!(
                "{}:{}",
                serde_json::to_string(k.str()).unwrap(),
                value.as_ref().map_or("null", SqlString::str)
            )
        })
        .join(",");
    Some(format!("{{{members}}}").into())
}
