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
API_HOST = "api.valerochkagym.tech"
IPV4_HTTPS_LISTEN = re.compile(
    r"(?m)^[ \t]*listen\s+(?:(?P<address>(?:(?:\d{1,3}\.){3}\d{1,3}|\*)):)?443(?:\s|;)"
)


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


def matching_brace(text: str, opening: int) -> int:
    """Return the matching closing brace, ignoring comments and quoted strings."""
    depth = 0
    quote: str | None = None
    escaped = False
    in_comment = False
    for index in range(opening, len(text)):
        char = text[index]
        if in_comment:
            if char == "\n":
                in_comment = False
            continue
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char == "#":
            in_comment = True
        elif char in {'"', "'"}:
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("nginx server block has unbalanced braces")


def server_blocks(current: str) -> list[tuple[int, int]]:
    blocks: list[tuple[int, int]] = []
    for match in re.finditer(r"(?m)^[ \t]*server\s*\{", current):
        opening = current.find("{", match.start(), match.end())
        closing = matching_brace(current, opening)
        blocks.append((match.start(), closing + 1))
    return blocks


def unique_server(matches: list[tuple[int, int]], description: str) -> tuple[int, int]:
    if not matches:
        raise ValueError(f"{description} was not found")
    if len(matches) > 1:
        raise ValueError(f"multiple {description}s were found")
    return matches[0]


def https_api_server(current: str) -> tuple[int, int]:
    host = re.compile(
        rf"(?m)^[ \t]*server_name\s+[^;]*\b{re.escape(API_HOST)}\b[^;]*;"
    )
    matches = [
        (start, end)
        for start, end in server_blocks(current)
        if host.search(current[start:end]) and IPV4_HTTPS_LISTEN.search(current[start:end])
    ]
    return unique_server(matches, "api.valerochkagym.tech HTTPS server block")


def https_spa_server(current: str) -> tuple[int, int]:
    spa = re.compile(r"\btry_files\s+[^;]*/index\.html[^;]*;")
    matches = [
        (start, end)
        for start, end in server_blocks(current)
        if IPV4_HTTPS_LISTEN.search(current[start:end]) and spa.search(current[start:end])
    ]
    return unique_server(matches, "IPv4 HTTPS SPA server block")


def target_server(current: str, strategy: str) -> tuple[int, int]:
    if strategy == "api":
        return https_api_server(current)
    if strategy == "spa":
        return https_spa_server(current)
    raise ValueError(f"unsupported server selection strategy: {strategy}")


def https_listen_address(current: str, strategy: str = "api") -> str:
    """Return an address that reaches the selected IPv4 HTTPS virtual host locally."""
    server_start, server_end = target_server(current, strategy)
    listeners = list(IPV4_HTTPS_LISTEN.finditer(current[server_start:server_end]))
    if any(match.group("address") in {None, "*"} for match in listeners):
        return "127.0.0.1"
    addresses = {match.group("address") for match in listeners}
    if len(addresses) != 1:
        raise ValueError("HTTPS server block has multiple explicit IPv4 listen addresses")
    return addresses.pop()


def remove_managed_blocks(current: str) -> str:
    begin_count = current.count(BEGIN)
    end_count = current.count(END)
    if begin_count != end_count:
        raise ValueError("installed nginx config has an incomplete managed block")
    pattern = re.compile(
        rf"(?ms)^[ \t]*{re.escape(BEGIN)}[ \t]*\n"
        rf".*?^[ \t]*{re.escape(END)}[ \t]*(?:\n|$)"
    )
    cleaned, removed = pattern.subn("", current)
    if removed != begin_count:
        raise ValueError("installed nginx config has an invalid managed block")
    return cleaned


def merge(current: str, source: str, strategy: str = "api") -> str:
    block = marked_block(source)
    current = remove_managed_blocks(current)
    server_start, server_end = target_server(current, strategy)
    selected = current[server_start:server_end]

    if "location = /.well-known/assetlinks.json" in selected:
        raise ValueError("installed nginx config has an unmanaged assetlinks route")
    if re.search(r"location\s+(?:\^~\s+)?/r/", selected) or re.search(
        r"location\s+~[^\n]*\^/r/", selected
    ):
        raise ValueError("installed nginx config has an unmanaged routine-share route")

    fallback = re.search(
        r"(?m)^(?P<indent>[ \t]*)location\s+(?:\^~\s+)?/\s*\{", selected
    )
    if fallback is None:
        raise ValueError("SPA fallback location was not found in the HTTPS server block")
    insert_at = server_start + fallback.start()
    indent = fallback.group("indent")
    indented_block = textwrap.indent(block, indent)
    return current[:insert_at] + indented_block + "\n" + current[insert_at:]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-address-output", type=Path)
    parser.add_argument(
        "--server-strategy", choices=("api", "spa"), default="api"
    )
    parser.add_argument("installed", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_text(
        merge(
            args.installed.read_text(),
            args.source.read_text(),
            strategy=args.server_strategy,
        ),
        encoding="utf-8",
    )
    if args.listen_address_output is not None:
        args.listen_address_output.write_text(
            https_listen_address(args.installed.read_text(), args.server_strategy),
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
