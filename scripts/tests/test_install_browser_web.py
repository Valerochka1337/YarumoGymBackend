import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "install-browser-web.sh"


class InstallBrowserWebTest(unittest.TestCase):
    def test_atomically_installs_release_on_existing_web_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            archive = root / "browser-web.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                for name, body in {
                    "index.html": "<title>Yarumo coach</title>",
                    "sw.js": "self.addEventListener('fetch', () => undefined);",
                    "manifest.webmanifest": '{"name":"Yarumo coach"}',
                }.items():
                    payload = body.encode()
                    info = tarfile.TarInfo(name)
                    info.size = len(payload)
                    output.addfile(info, io.BytesIO(payload))
            identity = bin_dir / "id"
            identity.write_text('#!/usr/bin/env bash\n[[ "$1" == "-u" ]] && echo 0\n')
            identity.chmod(0o755)
            environment = os.environ | {
                "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                "GYM_BROWSER_WEB_ROOT": str(root / "web"),
            }
            result = subprocess.run(
                ["bash", str(SCRIPT), str(archive)],
                env=environment,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            current = root / "web" / "current"
            self.assertTrue(current.is_symlink())
            self.assertIn("Yarumo", (current / "index.html").read_text())
            self.assertTrue((current / "sw.js").is_file())
            self.assertTrue((current / "manifest.webmanifest").is_file())

    def test_workflow_delivers_the_verified_web_artifact(self):
        workflow = (ROOT / ".github" / "workflows" / "backend.yml").read_text()
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        self.assertIn("repository: Valerochka1337/YarumoGymWeb", workflow)
        self.assertIn(
            "npx playwright install --with-deps chromium firefox webkit", workflow
        )
        self.assertIn("npm run test:e2e", workflow)
        self.assertIn("name: browser-web-release", workflow)
        self.assertIn("browser-web-release/browser-web.tar.gz", workflow)
        self.assertEqual(
            2,
            workflow.count(
                "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"
            ),
        )
        self.assertIn("scripts/install-browser-web.sh", workflow)
        self.assertNotIn("browser-web-nginx.conf", workflow)
        self.assertIn("restore_browser_web", deploy)
        self.assertLess(deploy.index("install_browser_web"), deploy.index('set_image "$new_image"'))


if __name__ == "__main__":
    unittest.main()
