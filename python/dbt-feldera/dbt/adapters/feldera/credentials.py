import logging
from dataclasses import dataclass
from typing import Optional

from dbt.adapters.contracts.connection import Credentials

logger = logging.getLogger(__name__)


@dataclass
class FelderaCredentials(Credentials):
    """
    Connection credentials for the Feldera dbt adapter.

    Configured via ``profiles.yml``:

    .. code-block:: yaml

        my_project:
          target: dev
          outputs:
            dev:
              type: feldera
              host: "http://localhost:8080"
              api_key: "apikey:..."
              schema: "my_pipeline"
              compilation_profile: dev
              workers: 4
              timeout: 300
              max_rss_mb: 98304
              dev_tweaks:
                adaptive_joins: true
    """

    host: str = "http://localhost:8080"
    api_key: Optional[str] = None
    pipeline_name: Optional[str] = None
    compilation_profile: str = "dev"
    workers: int = 4
    timeout: int = 300
    max_rss_mb: Optional[int] = None
    """Pipeline-wide resident-set-size cap in megabytes.

    Mirrors Feldera's ``runtime_config.max_rss_mb`` setting (see
    https://docs.feldera.com/operations/memory). When set, the pipeline
    applies backpressure to keep total memory usage at or below this
    value, spilling state to storage as needed. Leave unset (or zero) to
    let Feldera auto-derive the cap from container resources.
    """
    dev_tweaks: Optional[dict] = None
    """Free-form ``runtime_config.dev_tweaks`` overrides.

    Forwarded verbatim to ``runtime_config.dev_tweaks`` when set. Useful
    for opt-in features that aren't first-class fields on the Python
    ``RuntimeConfig`` dataclass — e.g. ``{"adaptive_joins": true}`` to
    enable the adaptive-join operator on skewed workloads. See
    https://docs.feldera.com/pipelines/configuration/ for the full list
    of recognised keys.
    """

    @property
    def type(self) -> str:
        return "feldera"

    @property
    def unique_field(self) -> str:
        return self.host

    def _connection_keys(self):
        return (
            "host",
            "pipeline_name",
            "database",
            "schema",
            "compilation_profile",
            "workers",
            "max_rss_mb",
            "dev_tweaks",
        )
