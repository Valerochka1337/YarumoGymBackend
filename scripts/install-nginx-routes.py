#!/usr/bin/env python3
"""Merge backend-owned public routes into the installed API server block.

The production host also serves the web client. Replacing its complete server block would erase
the web root and SPA fallback, so deployment owns only the marked route block from infra/nginx.conf.
"""

from __future__ import annotations

import argparse
import re
import textwrap
from pathlib import Path


BEGIN = "# BEGIN MANAGED ROUTINE SHARE ROUTES"
END = "# END MANAGED ROUTINE SHARE ROUTES"
SERVER_NAME = "server_name api.valerochkagym.tech;"


def marked_block(source: str) -> str:
    begin = source.find(BEGIN)
    end = source.find(END, begin + len(BEGIN))
    if begin < 0 or end < 0 or source.find(BEGIN, begin + len(BEGIN)) >= 0:
        raise ValueError("infra/nginx.conf must contain one managed routine-share block")
    start = source.rfind("\n", 0, begin) + 1
    line_end = source.find("\n", end + len(END))
    if line_end < 0:
        line_end = len(source)
    block = textwrap.dedent(source[start:line_end]).rstrip()
    if "location = /.well-known/assetlinks.json" not in block:
        raise ValueError("managed block is missing assetlinks.json")
    if "location ^~ /r/" not in block:
        raise ValueError("managed block is missing the routine-share prefix route")
    return block


def merge(current: str, source: str) -> str:
    block = marked_block(source)
    current_begin = current.find(BEGIN)
    current_end = current.find(END, current_begin + len(BEGIN))
    if current_begin >= 0 or current_end >= 0:
        if current_begin < 0 or current_end < 0:
            raise ValueError("installed nginx config has an incomplete managed block")
        current_start = current.rfind("\n", 0, current_begin) + 1
        indent = current[current_start:current_begin]
        current_line_end = current.find("\n", current_end + len(END))
        if current_line_end < 0:
            current_line_end = len(current)
        return (
            current[:current_start]
            + textwrap.indent(block, indent)
            + current[current_line_end:]
        )

    if "location = /.well-known/assetlinks.json" in current:
        raise ValueError("installed nginx config has an unmanaged assetlinks route")
    if re.search(r"location\s+(?:\^~\s+)?/r/", current) or re.search(
        r"location\s+~[^\n]*\^/r/", current
    ):
        raise ValueError("installed nginx config has an unmanaged routine-share route")

    server = current.find(SERVER_NAME)
    if server < 0:
        raise ValueError("api.valerochkagym.tech server block was not found")
    fallback = re.search(r"(?m)^(?P<indent>\s*)location\s+/\s*\{", current[server:])
    if fallback is None:
        raise ValueError("SPA fallback location was not found")
    insert_at = server + fallback.start()
    indent = fallback.group("indent")
    indented_block = textwrap.indent(block, indent)
    return current[:insert_at] + indented_block + "\n" + current[insert_at:]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("installed", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_text(
        merge(args.installed.read_text(), args.source.read_text()),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
