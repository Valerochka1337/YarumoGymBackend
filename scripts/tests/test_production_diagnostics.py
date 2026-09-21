import datetime as dt
import gzip
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('production', ROOT / '.github/diagnostics/production.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class ProductionDiagnosticsTest(unittest.TestCase):
    def test_access_groups_ai_statuses_and_times_without_identifiers(self):
        since = dt.datetime(2026, 9, 21, 19, tzinfo=dt.timezone.utc)
        lines = [
            'secret-ip - - [21/Sep/2026:22:01:00 +0300] "POST /v1/ai/calendar-draft-jobs?token=secret HTTP/1.1" 502 0 "secret-ref" "secret-agent"',
            'secret-ip - - [21/Sep/2026:22:02:00 +0300] "GET /v1/ai/calendar-draft-jobs/11111111-1111-1111-1111-111111111111 HTTP/1.1" 200 0',
            'secret-ip - - [21/Sep/2026:21:59:00 +0300] "POST /v1/ai/calendar-draft-jobs HTTP/1.1" 504 0',
        ]
        summary = p.access_summary(lines, since)
        self.assertEqual(summary['statuses'], [{'route': 'calendar_jobs', 'status': '200', 'count': 1}, {'route': 'calendar_jobs', 'status': '502', 'count': 1}])
        self.assertEqual(summary['errors_by_minute'][0]['minute'], '2026-09-21T19:01Z')
        self.assertNotIn('secret', json.dumps(summary))
        self.assertNotIn('11111111', json.dumps(summary))

    def test_structured_events_are_allowlisted_and_bounded(self):
        raw = {'runId': '00000000-0000-4000-8000-000000000001', 'outcome': 'FAILURE', 'category': 'AI_INVALID_RESPONSE',
               'model': 'secret', 'ownerId': 'secret', 'revision': 'secret',
               'events': [{'site': 'PLAN_VALIDATION', 'reason': 'DURATION_TOO_SHORT', 'tool': 'VALIDATE_AND_FINALIZE_PLAN', 'actual': 45, 'minimum': 2160, 'arguments': 'secret'},
                          {'site': 'PLAN_VALIDATION', 'reason': 'secret'},
                          {'site': [], 'reason': []}]}
        rows = p.diagnostic_records(('log AI_DIAGNOSTIC ' + json.dumps(raw) + '\n') * 25)
        self.assertEqual(len(rows), 20)
        self.assertEqual(len(rows[0]['events']), 1)
        self.assertEqual(rows[0]['events'][0]['actual'], 45)
        self.assertNotIn('secret', json.dumps(rows))
        self.assertEqual(p.diagnostic_records('AI_DIAGNOSTIC []\nAI_DIAGNOSTIC not-json'), [])
        self.assertIsNone(p.number(True))
        self.assertIsNone(p.number(-1))

    def test_allowlists_match_backend_events(self):
        source = (ROOT / 'src/main/kotlin/tech/valerochkagym/service/ai/AiDiagnostics.kt').read_text()
        for enum_name, expected in [('AiDiagnosticSite', p.SITES), ('AiDiagnosticReason', p.REASONS)]:
            body = re.search(r'enum class ' + enum_name + r'\s*\{([^}]+)\}', source).group(1)
            self.assertEqual(set(re.findall(r'\b[A-Z][A-Z_]+\b', body)), expected)

    def test_rotated_logs_report_missing_and_truncation(self):
        with tempfile.TemporaryDirectory() as folder:
            base = str(Path(folder) / 'access.log')
            Path(base).write_text('old line\n' * 10 + 'latest\n')
            with gzip.open(base + '.2.gz', 'wb') as f:
                f.write(b'compressed\n')
            with patch.object(p, 'MAX_FILE_BYTES', 30):
                rows, sources = p.read_rotated(base)
            self.assertIn('latest', rows)
            self.assertIn('compressed', rows)
            self.assertTrue(sources[0]['truncated'])
            self.assertFalse(sources[1]['available'])

    def test_container_projection_omits_health_messages_and_environment(self):
        result = p.container_summary(json.dumps({'status': 'running', 'oom': False, 'restarts': 2,
                                                'started': '2026-09-21T21:10:00Z', 'Env': ['secret'], 'health_log': 'secret'}))
        self.assertTrue(result['query_ok'])
        self.assertEqual(result['restart_count'], 2)
        self.assertNotIn('secret', json.dumps(result))
        self.assertFalse(p.container_summary('[]')['query_ok'])
        self.assertFalse(p.container_summary(None)['query_ok'])

    def test_nginx_error_summary_omits_raw_request(self):
        since = dt.datetime(2026, 9, 21, tzinfo=dt.timezone.utc)
        report = p.error_summary(['2026/09/22 00:01:00 [error] upstream timed out secret-token secret-ip'], since)
        self.assertEqual(report, {'upstream_timeout': 1})
        self.assertNotIn('secret', json.dumps(report))

    def test_log_summary_omits_exception_messages(self):
        report = p.summarize('ERROR secret-secret java.lang.OutOfMemoryError\nSQLState: 08006 ai_invalid_response')
        self.assertEqual(report['oom_mentions'], 1)
        self.assertEqual(report['sql_states'], {'08006': 1})
        self.assertNotIn('secret', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
