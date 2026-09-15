import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "install-nginx-routes.py"
SPEC = importlib.util.spec_from_file_location("install_nginx_routes", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class InstallNginxRoutesTest(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / "infra" / "nginx.conf").read_text()
        self.current = """server {
    listen 443 ssl;
    server_name api.valerochkagym.tech;
    location /v1/ { proxy_pass http://127.0.0.1:18080; }
    location / { try_files $uri $uri/ /index.html; }
}
"""

    def test_routes_are_inserted_before_spa_fallback(self):
        merged = MODULE.merge(self.current, self.source)

        self.assertLess(merged.index(MODULE.BEGIN), merged.index("location / {"))
        self.assertIn("location = /.well-known/assetlinks.json", merged)
        self.assertIn("location ^~ /r/", merged)
        self.assertLess(merged.index("location ^~ /r/"), merged.index("location / {"))
        self.assertIn("try_files $uri $uri/ /index.html", merged)

    def test_merge_is_idempotent(self):
        once = MODULE.merge(self.current, self.source)
        twice = MODULE.merge(once, self.source)

        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count(MODULE.BEGIN))

    def test_unmanaged_public_route_is_rejected(self):
        current = self.current.replace(
            "    location / {",
            "    location = /.well-known/assetlinks.json { return 200; }\n    location / {",
        )

        with self.assertRaisesRegex(ValueError, "unmanaged assetlinks"):
            MODULE.merge(current, self.source)

    def test_unmanaged_share_prefix_is_rejected(self):
        current = self.current.replace(
            "    location / {",
            "    location ^~ /r/ { return 200; }\n    location / {",
        )

        with self.assertRaisesRegex(ValueError, "unmanaged routine-share"):
            MODULE.merge(current, self.source)

    def test_cd_transfers_and_cleans_nginx_files(self):
        workflow = (ROOT / ".github" / "workflows" / "backend.yml").read_text()
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()

        self.assertIn("infra/nginx.conf", workflow)
        self.assertIn("scripts/install-nginx-routes.py", workflow)
        self.assertIn("incoming/nginx.conf", workflow)
        self.assertIn("install_nginx_routes", deploy)
        self.assertIn("--noproxy '*'", deploy)
        self.assertIn('--resolve "$host:443:127.0.0.1"', deploy)
        self.assertIn("Nginx smoke: assetlinks=%s share=%s root=%s", deploy)


if __name__ == "__main__":
    unittest.main()
