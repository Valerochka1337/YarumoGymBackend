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
    def test_installs_release_certificate_nginx_and_smokes_routes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            archive = root / "browser-web.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                for name, body in {
                    "index.html": "<title>Yarumo — пробная тренировка</title>",
                    "sw.js": 'const shell = "yarumo-shell-v1";',
                }.items():
                    payload = body.encode()
                    info = tarfile.TarInfo(name)
                    info.size = len(payload)
                    output.addfile(info, io.BytesIO(payload))
            nginx_source = root / "browser-web-nginx.conf"
            nginx_source.write_text(
                "server {\n"
                "  server_name app.valerochkagym.tech;\n"
                "  root /srv/yarumo-web/current;\n"
                "  ssl_certificate /etc/letsencrypt/live/app.valerochkagym.tech/fullchain.pem;\n"
                "}\n"
            )
            scripts = {
                "id": '[[ "$1" == "-u" ]] && echo 0',
                "getent": 'echo "62.84.122.55 STREAM app.valerochkagym.tech"',
                "nginx": "exit 0",
                "systemctl": "exit 0",
                "certbot": 'mkdir -p "$GYM_LETSENCRYPT_ROOT/app.valerochkagym.tech"; touch "$GYM_LETSENCRYPT_ROOT/app.valerochkagym.tech/fullchain.pem"',
                "curl": '''
output=
url=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) output=$2; shift 2 ;;
    --write-out) shift 2 ;;
    http*) url=$1; shift ;;
    *) shift ;;
  esac
done
case "$url" in
  */sw.js) printf 'yarumo-shell-v1' > "$output" ;;
  */r/*) printf 'Yarumo — пробная тренировка' > "$output" ;;
  *) printf '{}' > "$output" ;;
esac
printf 200
''',
            }
            for name, body in scripts.items():
                target = bin_dir / name
                target.write_text("#!/usr/bin/env bash\nset -e\n" + body + "\n")
                target.chmod(0o755)
            environment = os.environ | {
                "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                "GYM_DEPLOY_DIR": str(root / "deploy"),
                "GYM_BROWSER_WEB_ROOT": str(root / "web"),
                "GYM_NGINX_AVAILABLE": str(root / "nginx-available"),
                "GYM_NGINX_ENABLED": str(root / "nginx-enabled"),
                "GYM_LETSENCRYPT_ROOT": str(root / "letsencrypt"),
            }
            result = subprocess.run(
                ["bash", str(SCRIPT), str(archive), str(nginx_source)],
                env=environment,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            current = root / "web" / "current"
            self.assertTrue(current.is_symlink())
            self.assertIn("Yarumo", (current / "index.html").read_text())
            installed = root / "nginx-available" / "app.valerochkagym.tech"
            self.assertEqual(nginx_source.read_text(), installed.read_text())
            self.assertEqual(installed.resolve(), (root / "nginx-enabled" / "app.valerochkagym.tech").resolve())
            self.assertTrue((root / "letsencrypt" / "app.valerochkagym.tech" / "fullchain.pem").exists())

    def test_workflow_delivers_the_verified_web_artifact(self):
        workflow = (ROOT / ".github" / "workflows" / "backend.yml").read_text()
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        self.assertIn("repository: Valerochka1337/YarumoGymWeb", workflow)
        self.assertIn("npm run test:e2e", workflow)
        self.assertIn("name: browser-web-release", workflow)
        self.assertIn("browser-web-release/browser-web.tar.gz", workflow)
        self.assertIn("scripts/install-browser-web.sh", workflow)
        self.assertLess(deploy.index("install_browser_web"), deploy.index('set_image "$new_image"'))


if __name__ == "__main__":
    unittest.main()
