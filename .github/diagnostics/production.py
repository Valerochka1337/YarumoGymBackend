"""Read-only, bounded production diagnostics. Raw logs and errors stay on the server."""
import collections
import datetime as dt
import gzip
import json
import os
import re
import subprocess
import sys

UTC = dt.timezone.utc
ROOT = '/opt/valerochkagym'
NGINX = '/var/log/nginx/api.valerochkagym.tech'
MAX_LINES = 5000
MAX_FILE_BYTES = 32 * 1024 * 1024

# Deliberately duplicated allowlists: never copy arbitrary JSON logged by another subsystem.
SITES = set('AGENT_LOOP TOOL_PROTOCOL PROVIDER_RESPONSE PLAN_SCHEMA PLAN_VALIDATION FINALIZATION'.split())
REASONS = set('ROUND_BUDGET TOOL_CALL_BUDGET TOOL_STARTED TOOL_COMPLETED PLAN_ACCEPTED PLAN_REJECTED ROUND_LIMIT TOOL_CALL_LIMIT TOOL_RESULT_SIZE TRANSCRIPT_SIZE EMPTY_TURN FINAL_WITH_TOOLS DUPLICATE_TOOL_CALL INVALID_TOOL_ARGUMENTS INVALID_TOOL_ID UNKNOWN_TOOL TOOL_ARGUMENT_SIZE INVALID_CANDIDATE_IDS UNKNOWN_CANDIDATE UNKNOWN_PATTERN INVALID_PROVIDER_RESPONSE SCHEMA_MISMATCH PATTERN_MISSING FINALIZATION_MISSING FINAL_PLAN_MISMATCH REFINEMENT_UNCHANGED INVALID_PLAN_SHAPE UNKNOWN_EXERCISE DUPLICATE_EXERCISE INVALID_REST INVALID_SET_COUNT INVALID_SET_VALUES DURATION_TOO_SHORT DURATION_TOO_LONG'.split())
TOOLS = set('GET_STRENGTH_SKELETON GET_CANDIDATE_DETAILS_AND_HISTORY VALIDATE_AND_FINALIZE_PLAN UNKNOWN'.split())
OUTCOMES = set('RUNNING SUCCESS FAILURE CANCELLED'.split())
CATEGORIES = set('NONE AI_UNAVAILABLE AI_TIMEOUT AI_BUSY AI_INTERRUPTED AI_INVALID_RESPONSE AI_CONTEXT_STALE AI_CONTEXT_TOO_LARGE UPSTREAM_REJECTED UPSTREAM_UNAVAILABLE DATABASE TRANSPORT VALIDATION PROVIDER_UNCONFIGURED INTERNAL'.split())
SEGMENT = r'(?:result|name|exercises|exerciseId|restSeconds|plannedSets|reps|durationSec)'
FIELD = re.compile(SEGMENT + r'(?:\.' + SEGMENT + r'|\[[0-9]{1,3}\])*')
UUID = re.compile(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}')



def safe_field(value):
    return value if isinstance(value, str) and len(value) <= 200 and FIELD.fullmatch(value) else None


def enum(value, allowed, fallback=None):
    return value if isinstance(value, str) and value in allowed else fallback


def number(value, maximum=86_400_000):
    return value if type(value) is int and 0 <= value <= maximum else None


def timestamp(value):
    if not isinstance(value, str) or len(value) > 40:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
        return parsed.astimezone(UTC).isoformat() if parsed.tzinfo else None
    except ValueError:
        return None


def diagnostic_records(logs):
    records = []
    for line in logs.splitlines():
        marker = 'AI_DIAGNOSTIC '
        if marker not in line:
            continue
        try:
            raw = json.loads(line.split(marker, 1)[1])
        except (ValueError, TypeError):
            continue
        if not isinstance(raw, dict) or not UUID.fullmatch(str(raw.get('runId', ''))):
            continue
        events = []
        for event in (raw.get('events') if isinstance(raw.get('events'), list) else [])[-64:]:
            if not isinstance(event, dict) or enum(event.get('site'), SITES) is None or enum(event.get('reason'), REASONS) is None:
                continue
            events.append({
                'site': event['site'], 'reason': event['reason'],
                'round': number(event.get('round'), 100),
                'field': safe_field(event.get('field')),
                'tool': enum(event.get('tool'), TOOLS),
                **{key: number(event.get(key)) for key in ('actual', 'minimum', 'maximum')},
            })
        records.append({
            'runId': raw['runId'],
            'processId': raw.get('processId') if isinstance(raw.get('processId'), str) and UUID.fullmatch(raw['processId']) else None,
            'startedAt': timestamp(raw.get('startedAt')),
            'revision': raw.get('revision') if re.fullmatch('[a-f0-9]{40}', str(raw.get('revision', ''))) else 'unknown',
            'outcome': enum(raw.get('outcome'), OUTCOMES, 'UNKNOWN'),
            'category': enum(raw.get('category'), CATEGORIES, 'UNKNOWN'),
            'rounds': number(raw.get('rounds'), 100), 'toolCalls': number(raw.get('toolCalls'), 100),
            'durationMs': number(raw.get('durationMs')), 'droppedEvents': number(raw.get('droppedEvents')),
            'events': events,
        })
    return records[-20:]


def summarize(logs):
    # No class names supplied by arbitrary messages, SQL, URLs or exception text are exported.
    return {
        'error_lines': len(re.findall(r'\bERROR\b', logs)),
        'oom_mentions': len(re.findall(r'\bOutOfMemoryError\b|\bout of memory\b', logs, re.I)),
        'sql_states': dict(collections.Counter(re.findall(r'SQLState:\s*([0-9A-Z]{5})\b', logs))),
        'planner_error_codes': dict(collections.Counter(re.findall(
            r'\b(ai_(?:unavailable|timeout|busy|interrupted|invalid_response|context_stale|context_too_large))\b', logs))),
        'ai_runs': diagnostic_records(logs),
    }


def route(path):
    path = path.split('?', 1)[0]
    if path == '/v1/sync':
        return 'sync'
    # Return only fixed route families; resource IDs and queries never leave the server.
    if re.fullmatch(r'/v1/ai/calendar-draft-jobs(?:/[a-fA-F0-9-]+)?', path):
        return 'calendar_jobs'
    if path.startswith('/v1/ai/'):
        return 'ai'
    if path.startswith('/v1/training-proposals/'):
        return 'training_proposals'
    return None


def access_summary(lines, since):
    counts, minutes = collections.Counter(), collections.Counter()
    considered = 0
    for line in lines:
        match = re.search(r'\[([^\]]+)\] "([A-Z]+) ([^ ]+) HTTP/[^\"]+" ([0-9]{3})\b', line)
        if not match:
            continue
        try:
            when = dt.datetime.strptime(match[1], '%d/%b/%Y:%H:%M:%S %z')
        except ValueError:
            continue
        if when < since:
            continue
        considered += 1
        family = route(match[3])
        if family:
            counts[(family, match[4])] += 1
            if int(match[4]) >= 400:
                minutes[(when.astimezone(UTC).strftime('%Y-%m-%dT%H:%MZ'), family, match[4])] += 1
    return {'window_lines': considered,
            'statuses': [{'route': k[0], 'status': k[1], 'count': v} for k, v in sorted(counts.items())],
            'errors_by_minute': [{'minute': k[0], 'route': k[1], 'status': k[2], 'count': v}
                                 for k, v in sorted(minutes.items())[-100:]]}


def error_summary(lines, since):
    counts = collections.Counter()
    for line in lines:
        try:
            when = dt.datetime.strptime(line[:19], '%Y/%m/%d %H:%M:%S').astimezone(UTC)
        except ValueError:
            continue
        if when < since:
            continue
        for name, pattern in {
            'upstream_timeout': 'upstream timed out',
            'upstream_refused': 'connect() failed',
            'upstream_closed': 'upstream prematurely closed',
            'no_live_upstream': 'no live upstreams',
        }.items():
            if pattern in line:
                counts[name] += 1
    return dict(counts)


def read_rotated(base):
    # Fixed paths only. Bound decompression and retained output even for compressed rotations.
    lines, sources = [], []
    for suffix in ('', '.1', '.2.gz', '.3.gz'):
        path = base + suffix
        try:
            opener = gzip.open if suffix.endswith('.gz') else open
            with opener(path, 'rb') as stream:
                skipped = False
                if not suffix.endswith('.gz'):
                    size = os.fstat(stream.fileno()).st_size
                    if size > MAX_FILE_BYTES:
                        stream.seek(size - MAX_FILE_BYTES)
                        stream.readline()
                        skipped = True
                data = stream.read(MAX_FILE_BYTES + 1)
            truncated = skipped or len(data) > MAX_FILE_BYTES
            rows = data[:MAX_FILE_BYTES].decode('utf-8', 'replace').splitlines()
            sources.append({'rotation': suffix or 'current', 'available': True,
                            'truncated': truncated or len(rows) > MAX_LINES})
            lines.extend(rows[-MAX_LINES:])
        except (OSError, EOFError):
            sources.append({'rotation': suffix or 'current', 'available': False})
    return lines, sources


def run(args):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=15)
        return result.stdout if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def container_summary(raw):
    try:
        data = json.loads(raw or '{}')
        if not isinstance(data, dict):
            data = {}
    except ValueError:
        data = {}
    return {
        'query_ok': bool(data),
        'status': enum(data.get('status'), {'running', 'exited', 'restarting', 'created', 'paused', 'dead'}, 'unknown'),
        'oom_killed': data.get('oom') if type(data.get('oom')) is bool else None,
        'restart_count': number(data.get('restarts')),
        'health': enum(data.get('health'), {'healthy', 'unhealthy', 'starting'}, 'unknown'),
        'exit_code': number(data.get('exitCode'), 255),
        'started_at': timestamp(data.get('started')), 'finished_at': timestamp(data.get('finished')),
        'image_id': data.get('image') if re.fullmatch('sha256:[a-f0-9]{64}', str(data.get('image', ''))) else None,
        'revision': data.get('revision') if re.fullmatch('[a-f0-9]{40}', str(data.get('revision', ''))) else 'unknown',
    }


def host_resources():
    result = {}
    try:
        memory = dict(re.findall(r'^(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+) kB$', open('/proc/meminfo').read(), re.M))
        result['memory_kib'] = {k: int(v) for k, v in memory.items()}
        result['load_average'] = list(os.getloadavg())
        disk = os.statvfs(ROOT)
        result['disk_bytes'] = {'total': disk.f_blocks * disk.f_frsize, 'available': disk.f_bavail * disk.f_frsize}
    except OSError:
        result['query_incomplete'] = True
    return result


def main():
    hours = sys.argv[1] if len(sys.argv) == 2 else '6'
    if hours not in {'2', '6', '24'}:
        raise SystemExit('Window must be 2, 6 or 24 hours')
    now = dt.datetime.now(UTC)
    since = now - dt.timedelta(hours=int(hours))
    compose = ['docker', 'compose', '--project-directory', ROOT, '--env-file', ROOT + '/.env', '-f', ROOT + '/compose.production.yaml']
    services = run(compose + ['ps', '--status', 'running', '--services'])
    containers, logs = {}, {}
    template = '{"status":{{json .State.Status}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}},"oom":{{json .State.OOMKilled}},"restarts":{{json .RestartCount}},"exitCode":{{json .State.ExitCode}},"started":{{json .State.StartedAt}},"finished":{{json .State.FinishedAt}},"image":{{json .Image}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}}}'
    for service in ('backend', 'postgres'):
        ids = (run(compose + ['ps', '--all', '--quiet', service]) or '').splitlines()
        container_id = next((value for value in ids if re.fullmatch('[a-f0-9]{12,64}', value)), None)
        containers[service] = container_summary(run(['docker', 'inspect', '--format', template, container_id]) if container_id else None)
        output = run(compose + ['logs', '--no-color', '--since=' + since.isoformat(), '--tail=' + str(MAX_LINES), service])
        logs[service] = {'query_ok': output is not None, 'line_limit_reached': len((output or '').splitlines()) >= MAX_LINES,
                         'summary': summarize(output or '')}
    access, access_sources = read_rotated(NGINX + '.access.log')
    errors, error_sources = read_rotated(NGINX + '.error.log')
    kernel = run(['journalctl', '-k', '--since', since.strftime('%Y-%m-%d %H:%M:%S UTC'), '--no-pager', '-n', '2000', '-o', 'cat'])
    health = run(['curl', '--fail', '--silent', '--max-time', '10', 'http://127.0.0.1:18080/actuator/health/readiness'])
    try:
        status = json.loads(health or '{}').get('status')
    except (ValueError, AttributeError):
        status = None
    report = {
        'generated_at': now.isoformat(), 'since': since.isoformat(),
        'running_services': [s for s in (services or '').splitlines() if s in {'backend', 'postgres'}],
        'service_query_ok': services is not None, 'health': status if status in {'UP', 'DOWN', 'OUT_OF_SERVICE'} else 'unknown',
        'containers': containers, 'host': host_resources(), 'logs': logs,
        'nginx_access': {'sources': access_sources, **access_summary(access, since)},
        'nginx_errors': {'sources': error_sources, 'counts': error_summary(errors, since)},
        'kernel': {'query_ok': kernel is not None, 'line_limit_reached': len((kernel or '').splitlines()) >= 2000,
                   'oom_lines': sum(bool(re.search(r'out of memory|oom-kill|killed process', line, re.I)) for line in (kernel or '').splitlines())},
        'limitations': 'Bounded samples only. Current container logs/state do not cover removed containers. Missing or clean logs do not prove absence of historical errors. Nginx error timestamps use server local time.',
    }
    print(json.dumps(report, indent=2))
    if services is None or not all(value['query_ok'] for value in logs.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
