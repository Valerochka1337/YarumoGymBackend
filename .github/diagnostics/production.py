"""Read-only diagnostics. Raw logs and command errors never leave the server."""

import collections
import json
import re
import subprocess


def summarize(logs):
    # Emit only structural error identifiers, never exception messages or SQL text.
    exceptions = collections.Counter(re.findall(
        r"\b((?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9]*(?:Exception|Error))\b", logs
    ))
    sql_states = collections.Counter(re.findall(r"SQLState:\s*([0-9A-Z]{5})\b", logs))
    codes = collections.Counter(re.findall(
        r"\b(ai_(?:unavailable|timeout|busy|interrupted|invalid_response|context_stale|context_too_large))\b", logs
    ))
    return {
        "exception_classes": dict(exceptions.most_common(20)),
        "sql_states": dict(sql_states.most_common(20)),
        "planner_error_codes": dict(codes),
        "error_lines": len(re.findall(r"\bERROR\b", logs)),
    }


def run(args):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=40)
        return result.stdout if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def main():
    compose = ["docker", "compose", "--project-directory", "/opt/valerochkagym",
               "--env-file", "/opt/valerochkagym/.env", "-f",
               "/opt/valerochkagym/compose.production.yaml"]
    services = run(compose + ["ps", "--status", "running", "--services"])
    logs = run(compose + ["logs", "--no-color", "--since=2h", "--tail=2000", "backend"])
    health = run(["curl", "--fail", "--silent", "--max-time", "10",
                  "http://127.0.0.1:18080/actuator/health/readiness"])
    try:
        status = json.loads(health or "{}").get("status")
    except (ValueError, AttributeError):
        status = None
    print(json.dumps({
        "window": "last 2 hours, at most 2000 backend log lines",
        "running_services": [s for s in (services or "").splitlines()
                             if s in {"backend", "postgres"}],
        "service_query_ok": services is not None,
        "health": status if status in {"UP", "DOWN", "OUT_OF_SERVICE"} else "unknown",
        "log_query_ok": logs is not None,
        "log_summary": summarize(logs or ""),
        "note": "Caught exceptions may not be logged; empty summary does not prove absence of errors.",
    }, indent=2))
    if services is None or logs is None:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
