import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ai-encryption-config.py'
spec = importlib.util.spec_from_file_location('ai_encryption_config', SCRIPT)
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)
KEY = config.KEY
VALUE = base64.b64encode(bytes(range(32))).decode()


class AiEncryptionConfigTest(unittest.TestCase):
    def test_requires_exactly_one_canonical_256_bit_key(self):
        self.assertEqual({KEY: VALUE}, config.from_environment({KEY: VALUE}))
        for value in ('', 'secret', VALUE + '\n', '!' + VALUE, base64.b64encode(b'short').decode(), None):
            with self.assertRaises(config.ConfigError):
                config.validate({KEY: value})
        for value in ({}, {KEY: VALUE, 'AI_API_KEY': 'extra'}):
            with self.assertRaises(config.ConfigError):
                config.validate(value)

    def test_atomic_delivery_preserves_other_settings_and_rejects_rotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / '.env'
            original = 'DATABASE_PASSWORD="untouched"\nAI_API_KEY="legacy"\n'
            target.write_text(original)
            config.apply_config({KEY: VALUE}, target)
            saved = target.read_text()
            self.assertEqual(original + f'{KEY}="{VALUE}"\n', saved)
            self.assertEqual(0o600, target.stat().st_mode & 0o777)
            config.apply_config({KEY: VALUE}, target)
            self.assertEqual(saved, target.read_text())
            with self.assertRaises(config.ConfigError):
                config.apply_config({KEY: base64.b64encode(b'x' * 32).decode()}, target)
            self.assertEqual(saved, target.read_text())
            self.assertEqual([], list(Path(tmp).glob('.ai-key-env-*')))

    def test_existing_empty_and_quoted_dotenv_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / '.env'
            for value in ('', '""', "''", VALUE, f'"{VALUE}"', f"'{VALUE}' # comment"):
                target.write_text(f'export {KEY}={value}\n')
                config.apply_config({KEY: VALUE}, target)
                self.assertEqual(f'{KEY}="{VALUE}"\n', target.read_text())
            original = f'{KEY}=$(do-not-run)\n'
            target.write_text(original)
            with self.assertRaises(config.ConfigError):
                config.apply_config({KEY: VALUE}, target)
            self.assertEqual(original, target.read_text())

    def test_cli_never_echoes_invalid_payload_and_exports_to_private_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / '.env'; target.write_text('unchanged\n')
            payload = root / 'key.json'; payload.write_text('SECRET_INVALID_JSON')
            result = subprocess.run(['python3', str(SCRIPT), 'apply', str(payload), str(target)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(b'SECRET_INVALID_JSON', result.stdout + result.stderr)
            self.assertEqual('unchanged\n', target.read_text())
            result = subprocess.run(['python3', str(SCRIPT), 'export'], env=os.environ | {KEY: VALUE}, capture_output=True, check=True)
            payload.write_bytes(result.stdout)
            result = subprocess.run(['python3', str(SCRIPT), 'apply', str(payload), str(target)], capture_output=True, check=True)
            self.assertEqual(b'', result.stdout + result.stderr)

    def test_deploy_retains_key_even_when_application_rolls_back(self):
        for fail in (False, True):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); (root / 'incoming').mkdir(); (root / 'bin').mkdir()
                shutil.copyfile(SCRIPT, root / 'incoming/ai-encryption-config.py')
                (root / 'incoming/ai-encryption.json').write_text(json.dumps({KEY: VALUE}))
                original = 'BACKEND_IMAGE=old-image\nDATABASE_PASSWORD=untouched\nAI_ENABLED=true\n'
                (root / '.env').write_text(original)
                (root / 'backup.sh').write_text('#!/bin/sh\nexit 0\n'); (root / 'backup.sh').chmod(0o755)
                for name, body in {'flock': 'exit 0', 'curl': 'exit 0', 'docker': 'case "$*" in *" ps "*) echo postgres ;; *" up "*) if [ "$FAIL_UP" = 1 ] && [ ! -f failed-once ]; then touch failed-once; exit 1; fi ;; esac'}.items():
                    file = root / 'bin' / name; file.write_text('#!/bin/sh\n' + body + '\n'); file.chmod(0o755)
                result = subprocess.run(['bash', str(SCRIPT.parent / 'deploy.sh'), 'ghcr.io/valerochka1337/valerochkagymbackend@sha256:' + 'a' * 64], env=os.environ | {'GYM_DEPLOY_DIR': tmp, 'FAIL_UP': str(int(fail)), 'PATH': str(root / 'bin') + os.pathsep + os.environ['PATH']}, capture_output=True, text=True)
                self.assertEqual(1 if fail else 0, result.returncode, result.stderr)
                self.assertNotIn(VALUE, result.stdout + result.stderr)
                self.assertIn(f'{KEY}="{VALUE}"\n', (root / '.env').read_text())
                self.assertFalse((root / 'incoming/ai-encryption.json').exists())
                self.assertEqual([], list(root.glob('.env.rollback.*')))
                if fail:
                    self.assertEqual(original + f'{KEY}="{VALUE}"\n', (root / '.env').read_text())

    def test_workflow_delivers_production_secret_with_cleanup(self):
        workflow = (SCRIPT.parents[1] / '.github/workflows/backend.yml').read_text()
        self.assertIn('AI_SETTINGS_ENCRYPTION_KEY: ${{ secrets.AI_SETTINGS_ENCRYPTION_KEY }}', workflow)
        self.assertIn('python3 scripts/ai-encryption-config.py export > "$ai_key_payload"', workflow)
        self.assertIn('"$ai_key_payload" valerochka@', workflow)
        self.assertIn('"$ai_key_payload" ~/.ssh/gym-deploy', workflow)
        self.assertIn('/incoming/ai-encryption.json', workflow)
        self.assertIn("' || true", workflow)
        self.assertNotIn('set -x', workflow)
