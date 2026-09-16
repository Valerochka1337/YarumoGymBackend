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
        self.assertIn("add_header X-Yarumo-Route routine-share always", merged)
        self.assertIn("proxy_intercept_errors off", merged)
        self.assertLess(merged.index("location ^~ /r/"), merged.index("location / {"))
        self.assertIn("try_files $uri $uri/ /index.html", merged)

    def test_merge_is_idempotent(self):
        once = MODULE.merge(self.current, self.source)
        twice = MODULE.merge(once, self.source)

        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count(MODULE.BEGIN))

    def test_routes_are_inserted_into_https_server_when_http_server_comes_first(self):
        current = """server {
    listen 80;
    server_name api.valerochkagym.tech;
    location / { return 301 https://$host$request_uri; }
}
server {
    listen 443 ssl;
    server_name api.valerochkagym.tech;
    location ~ "^/preview/[A-Za-z0-9_-]{43}$" { return 404; }
    location ^~ / { try_files $uri $uri/ /index.html; }
}
"""

        merged = MODULE.merge(current, self.source)
        http, https = MODULE.server_blocks(merged)

        self.assertNotIn(MODULE.BEGIN, merged[slice(*http)])
        self.assertIn(MODULE.BEGIN, merged[slice(*https)])
        self.assertLess(
            merged[slice(*https)].index("location ^~ /r/"),
            merged[slice(*https)].index("location ^~ / {"),
        )

    def test_managed_routes_are_relocated_from_http_to_https_server(self):
        block = MODULE.marked_block(self.source)
        current = f"""server {{
    listen 80;
    server_name api.valerochkagym.tech;
{block}
    location / {{ return 301 https://$host$request_uri; }}
}}
server {{
    listen 443 ssl;
    server_name api.valerochkagym.tech;
    location ^~ / {{ try_files $uri $uri/ /index.html; }}
}}
"""

        merged = MODULE.merge(current, self.source)
        http, https = MODULE.server_blocks(merged)

        self.assertEqual(1, merged.count(MODULE.BEGIN))
        self.assertNotIn(MODULE.BEGIN, merged[slice(*http)])
        self.assertIn(MODULE.BEGIN, merged[slice(*https)])

    def test_ipv4_https_server_is_selected_instead_of_ipv6_only_server(self):
        current = """server {
    listen [::]:443 ssl;
    server_name api.valerochkagym.tech;
    location / { return 418; }
}
server {
    listen 127.0.0.1:443 ssl;
    server_name api.valerochkagym.tech;
    location ^~ / { try_files $uri $uri/ /index.html; }
}
"""

        merged = MODULE.merge(current, self.source)
        ipv6, ipv4 = MODULE.server_blocks(merged)

        self.assertNotIn(MODULE.BEGIN, merged[slice(*ipv6)])
        self.assertIn(MODULE.BEGIN, merged[slice(*ipv4)])

    def test_wildcard_https_listener_uses_loopback_for_smoke_check(self):
        self.assertEqual("127.0.0.1", MODULE.https_listen_address(self.current))

    def test_explicit_https_listener_is_used_for_smoke_check(self):
        current = self.current.replace("listen 443 ssl;", "listen 62.84.122.55:443 ssl;")

        self.assertEqual("62.84.122.55", MODULE.https_listen_address(current))

    def test_spa_strategy_selects_runtime_fallback_server(self):
        current = """server {
    listen 443 ssl;
    server_name api.valerochkagym.tech;
    location / { proxy_pass http://127.0.0.1:18080; }
}
server {
    listen 443 ssl default_server;
    server_name _;
    location / { try_files $uri $uri/ /index.html; }
}
"""

        merged = MODULE.merge(current, self.source, strategy="spa")
        api, fallback = MODULE.server_blocks(merged)

        self.assertNotIn(MODULE.BEGIN, merged[slice(*api)])
        self.assertIn(MODULE.BEGIN, merged[slice(*fallback)])

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
        self.assertIn('--listen-address-output "$listen_address_file"', deploy)
        self.assertIn('for strategy in spa api', deploy)
        self.assertIn('--server-strategy "$selected_strategy"', deploy)
        self.assertIn('--resolve "$host:443:$smoke_address"', deploy)
        self.assertIn("Nginx smoke: assetlinks=%s share=%s root=%s", deploy)
        self.assertIn("^x-yarumo-route:[[:space:]]*routine-share", deploy)
        self.assertIn('nginx -T > "$effective_config"', deploy)
        self.assertIn("Expected one active IPv4 HTTPS config", deploy)
        self.assertIn("Loaded Nginx route counts", deploy)
        self.assertNotIn(
            "readlink -f /etc/nginx/sites-enabled/api.valerochkagym.tech", deploy
        )


if __name__ == "__main__":
    unittest.main()
