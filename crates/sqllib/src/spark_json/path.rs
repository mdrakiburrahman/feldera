use super::json_float;
use crate::SqlString;
use serde::de::{Deserialize, Deserializer, MapAccess, Visitor};
use serde_json::value::RawValue;
use std::fmt;

enum Step<'a> {
    Field(&'a str),
    Index(usize),
    All,
}

fn steps(path: &str) -> Option<Vec<Step<'_>>> {
    let mut path = path.strip_prefix('$')?;
    let mut steps = Vec::new();
    while !path.is_empty() {
        let step = if let Some(tail) = path.strip_prefix("[*]") {
            path = tail;
            Step::All
        } else if let Some(tail) = path.strip_prefix("['") {
            let (field, tail) = tail.split_once("']")?;
            if field.is_empty() || field == "*" || field.contains(['\'', '?']) {
                return None;
            }
            path = tail;
            Step::Field(field)
        } else if let Some(tail) = path.strip_prefix('.') {
            let end = tail.find(['.', '[']).unwrap_or(tail.len());
            let (field, tail) = tail.split_at(end);
            if field.is_empty() || field.starts_with('*') {
                return None;
            }
            path = tail;
            Step::Field(field)
        } else {
            let (index, tail) = path.strip_prefix('[')?.split_once(']')?;
            if !index.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            path = tail;
            Step::Index(index.parse().ok()?)
        };
        steps.push(step);
    }
    Some(steps)
}

// A map would sort its keys and discard earlier duplicates. Jackson's path
// evaluator checks them in source order, stopping at the first successful match.
struct Members<'a>(Vec<(String, &'a RawValue)>);

impl<'de> Deserialize<'de> for Members<'de> {
    fn deserialize<D: Deserializer<'de>>(de: D) -> Result<Self, D::Error> {
        struct ObjectVisitor;
        impl<'de> Visitor<'de> for ObjectVisitor {
            type Value = Members<'de>;
            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("a JSON object")
            }
            fn visit_map<M: MapAccess<'de>>(self, mut map: M) -> Result<Self::Value, M::Error> {
                let mut fields = Vec::new();
                while let Some(entry) = map.next_entry()? {
                    fields.push(entry);
                }
                Ok(Members(fields))
            }
        }
        de.deserialize_map(ObjectVisitor)
    }
}

#[derive(Clone, Copy, PartialEq)]
enum Style {
    Raw,
    Quoted,
    Flat,
}

fn select(
    json: &RawValue,
    path: &[Step<'_>],
    style: Style,
    out: &mut Vec<String>,
    depth: usize,
) -> Option<bool> {
    if depth > 256 {
        return None;
    }
    let raw = json.get();
    let child = depth + 1;
    match (raw.as_bytes()[0], path) {
        (b'{', [Step::Field(key), rest @ ..]) => {
            for (name, value) in serde_json::from_str::<Members>(raw).ok()?.0 {
                if name == *key && value.get() != "null" && select(value, rest, style, out, child)?
                {
                    return Some(true);
                }
            }
        }
        (b'[', [Step::Index(index), rest @ ..]) => {
            let array: Vec<&RawValue> = serde_json::from_str(raw).ok()?;
            if let Some(value) = array.get(*index) {
                let style = if matches!(rest.first(), Some(Step::All)) {
                    Style::Quoted
                } else {
                    style
                };
                return select(value, rest, style, out, child);
            }
        }
        (b'[', [Step::All, rest @ ..]) => {
            let array: Vec<&RawValue> = serde_json::from_str(raw).ok()?;
            let (rest, child_style, wrap) = match rest {
                [Step::All, tail @ ..] => (tail, Style::Flat, true),
                _ => (
                    rest,
                    if style == Style::Flat {
                        Style::Flat
                    } else {
                        Style::Quoted
                    },
                    style == Style::Quoted,
                ),
            };
            let mut selected = Vec::new();
            let mut matches = 0;
            for value in array {
                matches += usize::from(select(value, rest, child_style, &mut selected, child)?);
            }
            if wrap || matches > 1 {
                out.push(format!("[{}]", selected.join(",")));
            } else if matches == 1 {
                out.extend(selected);
            }
            return Some(matches != 0);
        }
        (b'[', []) => {
            let array: Vec<&RawValue> = serde_json::from_str(raw).ok()?;
            let mut values = Vec::new();
            for v in array {
                select(
                    v,
                    &[],
                    if style == Style::Flat {
                        Style::Flat
                    } else {
                        Style::Quoted
                    },
                    &mut values,
                    child,
                )?;
            }
            if style == Style::Flat {
                let matched = !values.is_empty();
                out.extend(values);
                return Some(matched);
            }
            out.push(format!("[{}]", values.join(",")));
            return Some(true);
        }
        (b'{', []) => {
            let mut fields = Vec::new();
            for (key, value) in serde_json::from_str::<Members>(raw).ok()?.0 {
                let mut val = Vec::new();
                select(value, &[], Style::Quoted, &mut val, child)?;
                fields.push(format!(
                    "{}:{}",
                    serde_json::to_string(&key).ok()?,
                    val.first()?
                ));
            }
            out.push(format!("{{{}}}", fields.join(",")));
            return Some(true);
        }
        (b'"', []) => {
            let s: String = serde_json::from_str(raw).ok()?;
            out.push(if style == Style::Raw {
                s
            } else {
                serde_json::to_string(&s).ok()?
            });
            return Some(true);
        }
        (_, []) => {
            out.push(
                if raw.contains(['.', 'e', 'E']) && !matches!(raw, "false" | "true") {
                    json_float(raw.parse::<f64>().ok()?).to_string()
                } else {
                    raw.to_owned()
                },
            );
            return Some(true);
        }
        _ => {}
    }
    Some(false)
}

// The path extractor permits unescaped controls, while FROM_JSON does not.
fn relax_quotes(input: &str, allow_controls: bool) -> String {
    let mut out = String::with_capacity(input.len());
    let mut quote = None;
    let mut chars = input.chars();
    while let Some(c) = chars.next() {
        if quote == Some(c) {
            quote = None;
            out.push('"');
        } else if quote.is_none() && matches!(c, '\'' | '"') {
            quote = Some(c);
            out.push('"');
        } else if quote.is_some() && c == '\\' {
            if let Some(escaped) = chars.next() {
                if escaped != '\'' || quote != Some('\'') {
                    out.push('\\');
                }
                out.push(escaped);
            } else {
                out.push('\\');
            }
        } else if quote.is_some() && c == '"' {
            out.push_str("\\\"");
        } else if allow_controls && quote.is_some() && c < ' ' {
            use std::fmt::Write;
            write!(out, "\\u{:04x}", c as u32).unwrap();
        } else {
            out.push(c);
        }
    }
    out
}

fn first_value(text: &str) -> Option<&RawValue> {
    serde_json::Deserializer::from_str(text)
        .into_iter::<&RawValue>()
        .next()?
        .ok()
}

pub(super) fn with_json<T>(
    text: &str,
    allow_controls: bool,
    read: impl FnOnce(Option<&RawValue>) -> T,
) -> T {
    match first_value(text) {
        Some(value) => read(Some(value)),
        None => read(first_value(&relax_quotes(text, allow_controls))),
    }
}

pub fn spark_get_json_object(
    text: Option<SqlString>,
    path: Option<SqlString>,
) -> Option<SqlString> {
    let (text, path) = (text?, path?);
    let path = steps(path.str())?;
    with_json(text.str(), true, |json| {
        let mut result = Vec::new();
        select(json?, &path, Style::Raw, &mut result, 0)?.then(|| result.join(",").into())
    })
}
