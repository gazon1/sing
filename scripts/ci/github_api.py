#!/usr/bin/env python3
"""github_api.py — the GitHub API client the CI helpers talk through.

`gh api` is the client: the workflow already authenticates with the GitHub
CLI's own token, and a second client library would be a second
authentication path to keep. What this module owns is the part the
helpers share — how a failure is reported and how a response body is read.

Two failures are told apart, because the helpers need to tell them apart:
a resource that does not exist (`NotFound`) and an API that did not answer
(`LinkError`). A CNAME that is missing from the branch is a fact about the
branch; an API that does not answer is a fact about the network, and the
second must not be read as the first.
"""

from __future__ import annotations

import json
import subprocess
from typing import Any, Callable

#: The runner that talks to GitHub: a list of `gh api` arguments, and the
#: response body. The tests replace it with a fake.
Runner = Callable[[list[str]], str]


class LinkError(Exception):
    """A failure to read from or write to GitHub."""


class NotFound(LinkError):
    """The resource does not exist (HTTP 404)."""


def gh_api(args: list[str]) -> str:
    """Run `gh api` and return its response body."""
    result = subprocess.run(
        ["gh", "api", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        message = (result.stderr or "").strip() or f"gh api {' '.join(args)} failed"
        if "HTTP 404" in message:
            raise NotFound(message)
        raise LinkError(message)
    return result.stdout


def documents(text: str) -> list[Any]:
    """Every JSON document in a response body.

    `gh api --paginate` answers with one JSON array per page, concatenated.
    """
    decoder = json.JSONDecoder()
    found: list[Any] = []
    index = 0
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        try:
            document, index = decoder.raw_decode(text, index)
        except json.JSONDecodeError as exc:
            raise LinkError(f"gh api answered with invalid JSON: {exc}") from exc
        found.append(document)
    return found


def json_document(runner: Runner, args: list[str]) -> Any:
    """Run the runner and read one JSON document from its answer."""
    found = documents(runner(args))
    return found[0] if found else None
