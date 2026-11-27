use crate::{InputBuffer, InputConsumer, InputReader, InputReaderCommand, Parser};
use anyhow::{Context, Result, anyhow, bail, ensure};
use chrono::{DateTime, Utc};
use deltalake::logstore::object_store::{
    GetOptions, ObjectMeta, ObjectStore, azure::MicrosoftAzureBuilder, path::Path,
};
use feldera_adapterlib::{
    format::{BufferSize, StreamSplitter},
    transport::{InputCommandReceiver, Resume, Watermark},
};
use feldera_storage::tokio::TOKIO;
use futures::TryStreamExt;
use glob::{MatchOptions, Pattern};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashSet, VecDeque},
    hash::Hasher,
    sync::Arc,
    thread,
    time::Duration,
};
use tokio::{
    select,
    sync::mpsc::{UnboundedReceiver, UnboundedSender, unbounded_channel},
    time::{Instant, sleep_until},
};
use url::Url;
use xxhash_rust::xxh3::Xxh3Default;

const READ_SIZE: u64 = 64 * 1024;

struct Config {
    host: String,
    workspace: String,
    prefix: Path,
    glob: Option<Pattern>,
    modified_since: Option<DateTime<Utc>>,
    follow: bool,
    poll_interval: Duration,
    client_id: Option<String>,
}

impl Config {
    fn parse(path: &str) -> Result<Self> {
        let url = Url::parse(path).context("invalid OneLake Files URI")?;
        let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
        ensure!(
            url.scheme() == "abfss"
                && matches!(
                    host.as_str(),
                    "onelake.dfs.fabric.microsoft.com" | "msit-onelake.dfs.fabric.microsoft.com"
                ),
            "OneLake Files requires abfss://<workspace>@<OneLake DFS host>/<lakehouse>/Files/<path>"
        );
        ensure!(
            !url.username().is_empty()
                && url.password().is_none()
                && url.port().is_none()
                && url.fragment().is_none(),
            "OneLake Files URI requires a workspace and must not contain a password, port or fragment"
        );
        let prefix = Path::from_url_path(url.path())?;
        let parts: Vec<_> = prefix.as_ref().split('/').collect();
        ensure!(
            parts.len() >= 2 && !parts[0].is_empty() && parts[1] == "Files",
            "OneLake Files path must be beneath <lakehouse>/Files"
        );
        let mut config = Self {
            host,
            workspace: urlencoding::decode(url.username())?.into_owned(),
            prefix,
            glob: None,
            modified_since: None,
            follow: false,
            poll_interval: Duration::from_secs(10),
            client_id: None,
        };
        let mut keys = HashSet::new();
        for (key, value) in url.query_pairs() {
            ensure!(
                keys.insert(key.to_string()),
                "duplicate OneLake option '{key}'"
            );
            match key.as_ref() {
                "glob" => config.glob = Some(Pattern::new(&value).context("invalid OneLake glob")?),
                "modified_since" => {
                    config.modified_since = Some(
                        DateTime::parse_from_rfc3339(&value)
                            .context("modified_since must be an RFC 3339 timestamp")?
                            .with_timezone(&Utc),
                    );
                }
                "follow" => {
                    config.follow = value.parse().context("follow must be true or false")?
                }
                "poll_interval_secs" => {
                    let seconds: u64 = value.parse().context("invalid poll_interval_secs")?;
                    ensure!(
                        (1..=86400).contains(&seconds),
                        "poll_interval_secs must be 1..=86400"
                    );
                    config.poll_interval = Duration::from_secs(seconds);
                }
                "client_id" => {
                    ensure!(!value.is_empty(), "client_id must not be empty");
                    config.client_id = Some(value.into_owned());
                }
                _ => bail!("unknown OneLake Files option '{key}'"),
            }
        }
        Ok(config)
    }

    fn builder(&self, identity_endpoint: Option<String>) -> MicrosoftAzureBuilder {
        // Deliberately use MSI, not from_env's secret/key/CLI credential fallbacks.
        let mut builder = MicrosoftAzureBuilder::new()
            .with_account(self.host.split('.').next().unwrap())
            .with_container_name(&self.workspace)
            .with_endpoint(format!("https://{}", self.host));
        if let Some(client_id) = &self.client_id {
            builder = builder.with_client_id(client_id);
        }
        if let Some(endpoint) = identity_endpoint {
            builder = builder.with_msi_endpoint(endpoint);
        }
        builder
    }

    fn matches(&self, object: &ObjectMeta) -> bool {
        let Some(relative) = object
            .location
            .as_ref()
            .strip_prefix(self.prefix.as_ref())
            .and_then(|p| p.strip_prefix('/'))
        else {
            return false;
        };
        !relative.is_empty()
            && !relative
                .split('/')
                .any(|part| part.starts_with(['_', '.']) || part.ends_with(".tmp"))
            && self
                .modified_since
                .is_none_or(|since| object.last_modified >= since)
            && self.glob.as_ref().is_none_or(|glob| {
                glob.matches_with(
                    relative,
                    MatchOptions {
                        case_sensitive: true,
                        require_literal_separator: true,
                        require_literal_leading_dot: true,
                    },
                )
            })
    }
}

pub(super) fn open(
    path: &str,
    consumer: Box<dyn InputConsumer>,
    parser: Box<dyn Parser>,
    seek: Option<serde_json::Value>,
) -> Result<Box<dyn InputReader>> {
    let config = Config::parse(path)?;
    let identity_endpoint = [
        "AZURE_MSI_ENDPOINT",
        "AZURE_IDENTITY_ENDPOINT",
        "IDENTITY_ENDPOINT",
    ]
    .iter()
    .find_map(|name| std::env::var(name).ok().filter(|value| !value.is_empty()));
    let store = Arc::new(config.builder(identity_endpoint).build()?);
    Ok(Box::new(Reader::new(
        config, store, consumer, parser, seek,
    )?))
}

#[derive(Clone, Serialize, Deserialize)]
struct FileVersion {
    size: u64,
    e_tag: String,
}

#[derive(Default, Serialize, Deserialize)]
struct State {
    // Keep the initial manifest across recovery, even when follow is disabled.
    files: Option<BTreeMap<String, FileVersion>>,
    offsets: BTreeMap<String, u64>,
}

#[derive(Serialize, Deserialize)]
struct Metadata {
    state: State,
    chunks: Vec<Chunk>,
}

#[derive(Serialize, Deserialize)]
struct Chunk {
    path: String,
    start: u64,
    end: u64,
}

async fn read(
    store: &dyn ObjectStore,
    version: &FileVersion,
    chunk: &Chunk,
) -> Result<bytes::Bytes> {
    if chunk.start == chunk.end {
        return Ok(bytes::Bytes::new());
    }
    let result = store
        .get_opts(
            &Path::parse(&chunk.path)?,
            GetOptions {
                if_match: Some(version.e_tag.clone()),
                range: Some((chunk.start..chunk.end).into()),
                ..Default::default()
            },
        )
        .await
        .with_context(|| format!("reading immutable OneLake file '{}'", chunk.path))?;
    ensure!(
        result.meta.size == version.size,
        "OneLake file size changed: {}",
        chunk.path
    );
    let bytes = result.bytes().await?;
    ensure!(
        bytes.len() as u64 == chunk.end - chunk.start,
        "short read from OneLake file '{}'",
        chunk.path
    );
    Ok(bytes)
}

struct Source {
    config: Config,
    store: Arc<dyn ObjectStore>,
    state: State,
    pending: VecDeque<String>,
    current: Option<String>,
    read_offset: u64,
    splitter: StreamSplitter,
    next_poll: Instant,
}

impl Source {
    fn new(
        config: Config,
        store: Arc<dyn ObjectStore>,
        state: State,
        parser: &dyn Parser,
    ) -> Result<Self> {
        let mut pending = VecDeque::new();
        if let Some(files) = &state.files {
            for (path, version) in files {
                match state.offsets.get(path) {
                    Some(offset) if *offset > version.size => {
                        bail!("checkpoint offset exceeds size: {path}")
                    }
                    Some(offset) if *offset == version.size => {}
                    _ => pending.push_back(path.clone()),
                }
            }
        }
        Ok(Self {
            config,
            store,
            state,
            pending,
            current: None,
            read_offset: 0,
            splitter: StreamSplitter::new(parser.splitter()),
            next_poll: Instant::now(),
        })
    }

    async fn list(&mut self) -> Result<()> {
        let mut stream = self.store.list(Some(&self.config.prefix));
        let mut discovered = BTreeMap::new();
        while let Some(object) = stream.try_next().await.context("listing OneLake Files")? {
            if self.config.matches(&object) {
                let path = object.location.to_string();
                if self
                    .state
                    .files
                    .as_ref()
                    .is_some_and(|files| files.contains_key(&path))
                {
                    continue;
                }
                discovered.insert(
                    path,
                    FileVersion {
                        size: object.size,
                        e_tag: object.e_tag.ok_or_else(|| {
                            anyhow!("OneLake file has no ETag: {}", object.location)
                        })?,
                    },
                );
            }
        }
        self.pending.extend(discovered.keys().cloned());
        self.state.files.get_or_insert_default().extend(discovered);
        self.next_poll = Instant::now() + self.config.poll_interval;
        Ok(())
    }

    async fn next(&mut self) -> Result<Option<(Chunk, Vec<u8>)>> {
        loop {
            if let Some(path) = &self.current {
                let version = &self.state.files.as_ref().unwrap()[path];
                let start = self.splitter.position();
                if let Some(bytes) = self.splitter.next(self.read_offset == version.size) {
                    let bytes = bytes.to_vec();
                    let end = self.splitter.position();
                    return Ok(Some((
                        Chunk {
                            path: path.clone(),
                            start,
                            end,
                        },
                        bytes,
                    )));
                }
                if self.read_offset == version.size {
                    let path = self.current.take().unwrap();
                    if version.size == 0 {
                        return Ok(Some((
                            Chunk {
                                path,
                                start: 0,
                                end: 0,
                            },
                            Vec::new(),
                        )));
                    }
                    continue;
                }
                let range = Chunk {
                    path: path.clone(),
                    start: self.read_offset,
                    end: self.read_offset.saturating_add(READ_SIZE).min(version.size),
                };
                let bytes = read(self.store.as_ref(), version, &range).await?;
                self.splitter.append(&bytes);
                self.read_offset = range.end;
            } else if let Some(path) = self.pending.pop_front() {
                self.read_offset = self.state.offsets.get(&path).copied().unwrap_or(0);
                self.splitter.seek(self.read_offset);
                self.current = Some(path);
            } else if self.state.files.is_none() {
                self.list().await?;
            } else if self.config.follow {
                sleep_until(self.next_poll).await;
                self.list().await?;
            } else {
                return Ok(None);
            }
        }
    }
}

struct Reader {
    sender: UnboundedSender<InputReaderCommand>,
}

impl Reader {
    fn new(
        config: Config,
        store: Arc<dyn ObjectStore>,
        consumer: Box<dyn InputConsumer>,
        parser: Box<dyn Parser>,
        seek: Option<serde_json::Value>,
    ) -> Result<Self> {
        let state = seek
            .map(serde_json::from_value::<Metadata>)
            .transpose()
            .context("invalid OneLake Files checkpoint")?
            .map(|metadata| metadata.state)
            .unwrap_or_default();
        let (sender, receiver) = unbounded_channel();
        thread::Builder::new()
            .name("onelake-files".into())
            .spawn(move || {
                if let Err(error) = TOKIO.block_on(Self::run(
                    config,
                    store,
                    consumer.as_ref(),
                    parser,
                    state,
                    receiver,
                )) {
                    consumer.error(true, error, None);
                }
            })?;
        Ok(Self { sender })
    }

    async fn run(
        config: Config,
        store: Arc<dyn ObjectStore>,
        consumer: &dyn InputConsumer,
        mut parser: Box<dyn Parser>,
        mut state: State,
        receiver: UnboundedReceiver<InputReaderCommand>,
    ) -> Result<()> {
        crate::ensure_default_crypto_provider();
        let mut commands = InputCommandReceiver::<Metadata, ()>::new(receiver);
        while let Some((metadata, ())) = commands.recv_replay().await? {
            let mut total = BufferSize::empty();
            let mut hasher = Xxh3Default::new();
            for chunk in &metadata.chunks {
                let version = metadata
                    .state
                    .files
                    .as_ref()
                    .and_then(|files| files.get(&chunk.path))
                    .ok_or_else(|| anyhow!("replay file is absent from OneLake manifest"))?;
                let bytes = read(store.as_ref(), version, chunk).await?;
                if !bytes.is_empty() {
                    let (mut buffer, errors) = parser.parse(&bytes, None);
                    consumer.parse_errors(errors);
                    consumer.buffered(buffer.len());
                    total += buffer.len();
                    buffer.hash(&mut hasher);
                    consumer.check_flush(buffer.flush());
                }
            }
            consumer.replayed(total, hasher.finish());
            state = metadata.state;
        }
        let mut source = Source::new(config, store, state, parser.as_ref())?;
        let mut queue = VecDeque::<(Chunk, Option<Box<dyn InputBuffer>>, DateTime<Utc>)>::new();
        let mut extending = false;
        let mut eof = false;
        loop {
            select! {
                command = commands.recv() => match command? {
                    InputReaderCommand::Extend => extending = true,
                    InputReaderCommand::Pause => extending = false,
                    InputReaderCommand::Disconnect => return Ok(()),
                    InputReaderCommand::Replay { .. } => bail!("OneLake replay must precede normal reads"),
                    InputReaderCommand::Queue { .. } => {
                        let mut total = BufferSize::empty();
                        let mut hasher = consumer.hasher();
                        let mut chunks = Vec::new();
                        let mut watermarks = Vec::new();
                        while let Some((chunk, mut buffer, timestamp)) = queue.pop_front() {
                            total += buffer.len();
                            if let Some(hasher) = hasher.as_mut() {
                                buffer.hash(hasher);
                            }
                            consumer.check_flush(buffer.flush());
                            source.state.offsets.insert(chunk.path.clone(), chunk.end);
                            chunks.push(chunk);
                            watermarks.push(Watermark::new(timestamp, None));
                            if total.records >= consumer.max_batch_size() {
                                break;
                            }
                        }
                        let metadata = serde_json::json!({ "state": source.state, "chunks": chunks });
                        consumer.extended(total, Some(Resume::new_metadata_only(metadata, hasher.map(|h| h.finish()))), watermarks);
                    }
                },
                next = source.next(), if extending && !eof && queue.len() < 8 => {
                    match next? {
                        Some((chunk, bytes)) => {
                            let buffer = if bytes.is_empty() {
                                None
                            } else {
                                let (buffer, errors) = parser.parse(&bytes, None);
                                consumer.buffered(buffer.len());
                                consumer.parse_errors(errors);
                                buffer
                            };
                            queue.push_back((chunk, buffer, Utc::now()));
                        }
                        None => {
                            eof = true;
                            consumer.eoi();
                        }
                    }
                }
            }
        }
    }
}

impl InputReader for Reader {
    fn as_any(self: Arc<Self>) -> Arc<dyn std::any::Any + Send + Sync> {
        self
    }

    fn request(&self, command: InputReaderCommand) {
        let _ = self.sender.send(command);
    }

    fn is_closed(&self) -> bool {
        self.sender.is_closed()
    }
}

impl Drop for Reader {
    fn drop(&mut self) {
        self.disconnect();
    }
}
