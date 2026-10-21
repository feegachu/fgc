"""Validate pulled image revisions/digests and adapt their own Nginx template."""
import json
import pathlib
import re
import sys
import urllib.parse

REGISTRY = "ghcr.io/feegachu/fgc"


def image_reference(info, name, commit):
    labels = info.get("Config", {}).get("Labels") or {}
    if labels.get("org.opencontainers.image.revision") != commit:
        raise ValueError(f"{name}: image revision does not match successful CI")
    pattern = re.escape(f"{REGISTRY}/{name}@sha256:") + r"[0-9a-f]{64}"
    refs = [ref for ref in info.get("RepoDigests", []) if re.fullmatch(pattern, ref)]
    if len(set(refs)) != 1:
        raise ValueError(f"{name}: missing or ambiguous registry digest")
    return refs[0]


def adapt_nginx(template):
    for header, value in (("Proto", "https"), ("Port", "443")):
        pattern = rf"proxy_set_header X-Forwarded-{header}\s+[^;]+;"
        template, count = re.subn(pattern, f"proxy_set_header X-Forwarded-{header} {value};", template)
        if count != 1:
            raise ValueError(f"Unexpected Nginx X-Forwarded-{header} layout")
    if "include /etc/nginx/fgc.d/*.conf;" not in template:
        raise ValueError("Nginx guard include missing")
    return template


def public_origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname
            or parsed.username or parsed.password or parsed.port not in (None, 443)
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment
            or "\n" in value or "\r" in value):
        raise ValueError("SHINHAN_PUBLIC_URL must be an HTTPS origin without credentials or a path")
    host = parsed.hostname.encode("idna").decode("ascii").lower()
    if not re.fullmatch(r"[a-z0-9.-]+", host):
        raise ValueError("Invalid public hostname")
    return "https://" + host, host


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--origin":
        print("\n".join(public_origin(sys.argv[2])))
        return
    commit, run_url, directory = sys.argv[1:]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid commit")
    path = pathlib.Path(directory)
    manifest = {"schema": 1, "commit": commit, "ci_build_url": run_url}
    for name in ("api", "web"):
        info = json.loads((path / f"{name}-inspect.json").read_text())[0]
        manifest[name] = image_reference(info, name, commit)
    (path / "release.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (path / "release.env").write_text(
        f"API_IMAGE={manifest['api']}\nWEB_IMAGE={manifest['web']}\n")
    nginx = path / "nginx/default.conf.template"
    nginx.write_text(adapt_nginx(nginx.read_text()))


if __name__ == "__main__":
    main()
