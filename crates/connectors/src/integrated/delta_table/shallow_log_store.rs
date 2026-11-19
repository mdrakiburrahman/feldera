use std::{fmt, fs, io, ops::Range, sync::Arc};

use async_trait::async_trait;
use bytes::Bytes;
use deltalake::logstore::object_store::{
    CopyOptions, Error, GetOptions, GetResult, ListResult, MultipartUpload, ObjectMeta,
    ObjectStore, PutMultipartOptions, PutOptions, PutPayload, PutResult, RenameOptions, Result,
    local::LocalFileSystem, path::Path,
};
use deltalake::{
    DeltaResult, DeltaTableBuilder,
    logstore::{DeltaIOStorageBackend, IORuntime},
};
use futures::{StreamExt, TryStreamExt, stream, stream::BoxStream};
use tokio::runtime::Handle;
use url::Url;

pub(super) fn table_builder(url: &Url, io: &Handle) -> DeltaResult<DeltaTableBuilder> {
    let runtime = IORuntime::RT(io.clone());
    let builder = DeltaTableBuilder::from_url(url.clone())?.with_io_runtime(runtime.clone());
    // Leave UNC URLs to delta-rs, which applies its own root and URL rewrite.
    if url.scheme() != "file" || url.has_host() {
        return Ok(builder);
    }

    let log_prefix = Path::from_url_path(url.path())?.join("_delta_log");
    // The kernel uses the root store; delta-rs adds a PrefixStore for table-relative operations.
    Ok(builder.with_storage_backend(
        Arc::new(DeltaIOStorageBackend::new(
            Arc::new(LocalDeltaLog {
                inner: Arc::new(LocalFileSystem::new()),
                log_prefix,
                io: io.clone(),
            }),
            runtime,
        )),
        url.clone(),
    ))
}

#[derive(Debug)]
struct LocalDeltaLog {
    inner: Arc<LocalFileSystem>,
    log_prefix: Path,
    io: Handle,
}

impl fmt::Display for LocalDeltaLog {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("LocalDeltaLog")
    }
}

impl LocalDeltaLog {
    fn list_log(&self, offset: Option<&Path>) -> BoxStream<'static, Result<ObjectMeta>> {
        let inner = self.inner.clone();
        let prefix = self.log_prefix.clone();
        let offset = offset.cloned();
        let io = self.io.clone();
        stream::once(async move {
            io.spawn_blocking(move || {
                let dir = inner.path_to_filesystem(&prefix)?;
                let mut objects = Vec::new();
                for entry in fs::read_dir(&dir).map_err(|e| listing_error(&dir, e))? {
                    let entry = entry.map_err(|e| listing_error(&dir, e))?;
                    let file = entry.path();
                    if !entry
                        .file_type()
                        .map_err(|e| listing_error(&file, e))?
                        .is_file()
                    {
                        continue;
                    }
                    let location = Path::from_absolute_path(&file)?;
                    if offset.as_ref().is_some_and(|offset| &location <= offset) {
                        continue;
                    }
                    // ObjectStore local multipart upload staging files are not visible in list().
                    if location
                        .filename()
                        .and_then(|name| name.split_once('#'))
                        .is_some_and(|(_, suffix)| {
                            !suffix.is_empty() && suffix.bytes().all(|byte| byte.is_ascii_digit())
                        })
                    {
                        continue;
                    }
                    let metadata = entry.metadata().map_err(|e| listing_error(&file, e))?;
                    objects.push(ObjectMeta {
                        location,
                        last_modified: metadata
                            .modified()
                            .map_err(|e| listing_error(&file, e))?
                            .into(),
                        size: metadata.len(),
                        e_tag: None,
                        version: None,
                    });
                }
                // The kernel sorts `file://` listings itself.
                Ok::<_, Error>(stream::iter(objects.into_iter().map(Ok)))
            })
            .await
            .map_err(|source| Error::Generic {
                store: "LocalDeltaLog",
                source: source.into(),
            })?
        })
        .try_flatten()
        .boxed()
    }
}

fn listing_error(path: &std::path::Path, source: io::Error) -> Error {
    Error::Generic {
        store: "LocalDeltaLog",
        source: io::Error::new(source.kind(), format!("{}: {source}", path.display())).into(),
    }
}

#[async_trait]
impl ObjectStore for LocalDeltaLog {
    async fn put_opts(
        &self,
        location: &Path,
        payload: PutPayload,
        options: PutOptions,
    ) -> Result<PutResult> {
        self.inner.put_opts(location, payload, options).await
    }

    async fn put_multipart_opts(
        &self,
        location: &Path,
        options: PutMultipartOptions,
    ) -> Result<Box<dyn MultipartUpload>> {
        self.inner.put_multipart_opts(location, options).await
    }

    async fn get_opts(&self, location: &Path, options: GetOptions) -> Result<GetResult> {
        self.inner.get_opts(location, options).await
    }

    async fn get_ranges(&self, location: &Path, ranges: &[Range<u64>]) -> Result<Vec<Bytes>> {
        self.inner.get_ranges(location, ranges).await
    }

    fn delete_stream(
        &self,
        locations: BoxStream<'static, Result<Path>>,
    ) -> BoxStream<'static, Result<Path>> {
        self.inner.delete_stream(locations)
    }

    fn list(&self, prefix: Option<&Path>) -> BoxStream<'static, Result<ObjectMeta>> {
        if prefix == Some(&self.log_prefix) {
            self.list_log(None)
        } else {
            self.inner.list(prefix)
        }
    }

    fn list_with_offset(
        &self,
        prefix: Option<&Path>,
        offset: &Path,
    ) -> BoxStream<'static, Result<ObjectMeta>> {
        if prefix == Some(&self.log_prefix) {
            self.list_log(Some(offset))
        } else {
            self.inner.list_with_offset(prefix, offset)
        }
    }

    async fn list_with_delimiter(&self, prefix: Option<&Path>) -> Result<ListResult> {
        self.inner.list_with_delimiter(prefix).await
    }

    async fn copy_opts(&self, from: &Path, to: &Path, options: CopyOptions) -> Result<()> {
        self.inner.copy_opts(from, to, options).await
    }

    async fn rename_opts(&self, from: &Path, to: &Path, options: RenameOptions) -> Result<()> {
        self.inner.rename_opts(from, to, options).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use deltalake::logstore::object_store::ObjectStoreExt;

    #[tokio::test]
    async fn local_delta_log_lists_from_exclusive_offset() {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("_delta_log");
        let sidecars = log.join("_sidecars");
        fs::create_dir_all(&sidecars).unwrap();
        let first = log.join("00000000000000000001.json");
        let second = log.join("00000000000000000002.json");
        let sidecar = sidecars.join("fixture.parquet");
        for file in [&first, &second, &sidecar] {
            fs::write(file, b"fixture").unwrap();
        }
        let table = table_builder(
            &Url::from_directory_path(dir.path()).unwrap(),
            &Handle::current(),
        )
        .unwrap()
        .build()
        .unwrap();
        let store = table.log_store().object_store();
        let prefix = Path::from("_delta_log");
        let offset = Path::from("_delta_log/00000000000000000001.json");
        let listed: Vec<_> = store
            .list_with_offset(Some(&prefix), &offset)
            .map_ok(|meta| meta.location)
            .try_collect()
            .await
            .unwrap();
        assert_eq!(listed, [Path::from("_delta_log/00000000000000000002.json")]);

        assert_eq!(
            store
                .get(&Path::from("_delta_log/_sidecars/fixture.parquet"))
                .await
                .unwrap()
                .bytes()
                .await
                .unwrap()
                .as_ref(),
            b"fixture",
        );
        fs::rename(&log, dir.path().join("retired")).unwrap();
        assert!(
            store
                .list(Some(&prefix))
                .try_collect::<Vec<_>>()
                .await
                .is_err()
        );
    }
}
