from unittest.mock import MagicMock, patch

import pytest
import requests

from feldera.rest._httprequests import HttpRequests
from feldera.rest.config import Config
from feldera.rest.errors import FelderaAPIError
from feldera.rest.feldera_client import FelderaClient
from feldera.rest.retry import RetryConfig


def test_conditional_start_uses_dedicated_single_attempt_endpoint():
    client = FelderaClient.__new__(FelderaClient)
    client.http = MagicMock()
    client.start_pipeline(
        "p", wait=False, expected_pipeline_id="pipeline-id", expected_version=3
    )
    client.http.post.assert_called_once_with(
        path="/pipelines/p/start-if-current",
        params={
            "initial": "running",
            "dismiss_error": "true",
            "expected_pipeline_id": "pipeline-id",
            "expected_version": 3,
        },
        retry=False,
    )
    with pytest.raises(ValueError):
        client.start_pipeline("p", wait=False, expected_pipeline_id="pipeline-id")
    client.http.post.assert_called_once()


@pytest.mark.parametrize("status", [401, 429])
def test_guarded_transport_does_not_retry_even_not_applied_responses(status):
    client = HttpRequests(
        Config(
            url="http://example.test",
            api_key=lambda: "test-token",
            retry_config=RetryConfig(max_retries=3, initial_backoff=0),
        )
    )
    response = requests.Response()
    response.status_code = status
    response._content = b"{}"
    response.headers["content-type"] = "application/json"
    response.request = requests.Request("POST", "http://example.test/v0/x").prepare()
    with patch("requests.post") as post:
        post.__name__ = "post"
        post.return_value = response
        with pytest.raises(FelderaAPIError):
            client.post("/x", retry=False)
        post.assert_called_once()
