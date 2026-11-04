"""Promote an archived AWS staging release without rebuilding or resolving tags."""
import argparse
import hashlib
import json
import pathlib
import re
import tarfile
import urllib.parse

REGISTRY = "ghcr.io/feegachu/fgc"
MAX_MANIFEST = 64 * 1024
# Demo accounts whose seed passwords are published in this repository (seed spec section 5, SRC-065).
SEED_ACCOUNTS = ("admin", "gaadmin", "settle01", "audit01", "agent01")
SEED_HASH = re.compile(r"'(\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53})'")
# The Flyway checksum freezes V3, so its comment stays the record of the published password.
SEED_PASSWORD = re.compile(r"비밀번호 \d+개 모두 (\S+)")


def validate_release(value):
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise ValueError("Unsupported release manifest")
    if not re.fullmatch(r"[0-9a-f]{40}", str(value.get("commit", ""))):
        raise ValueError("Invalid release commit")
    for name in ("api", "web"):
        pattern = re.escape(f"{REGISTRY}/{name}@sha256:") + r"[0-9a-f]{64}"
        if not re.fullmatch(pattern, str(value.get(name, ""))):
            raise ValueError(f"{name}: staging release must specify an immutable GHCR digest")
    return value


def read_release(path):
    path = pathlib.Path(path)
    if path.stat().st_size > MAX_MANIFEST:
        raise ValueError("Release manifest too large")
    return validate_release(json.loads(path.read_text(encoding="utf-8")))


def stage_promotion(bundle, archived_release, build, jenkins_url):
    if not re.fullmatch(r"[1-9][0-9]*", str(build)):
        raise ValueError("Select a stage-fgc build number")
    release = read_release(archived_release)
    bundle = pathlib.Path(bundle)
    if bundle.stat().st_size > 10 * 1024 * 1024:
        raise ValueError("Unexpected staging bundle size")
    # Read only the manifest; never extract or execute scripts from this archive.
    with tarfile.open(bundle, "r:gz") as archive:
        members = [m for m in archive.getmembers() if m.name == "release.json"]
        if len(members) != 1 or not members[0].isfile() or members[0].size > MAX_MANIFEST:
            raise ValueError("Staging bundle requires one regular release.json")
        with archive.extractfile(members[0]) as source:
            bundled = validate_release(json.load(source))
    if bundled != release:
        raise ValueError("Archived release.json does not match staging bundle")
    return {"schema": 1, "source_job": "stage-fgc", "source_build": int(build),
            "source_build_url": jenkins_url.rstrip("/") + f"/job/stage-fgc/{build}/",
            "bundle_sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
            "release": release}


def read_promotion(path):
    value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    if (not isinstance(value, dict) or value.get("schema") != 1
            or value.get("source_job") != "stage-fgc"
            or type(value.get("source_build")) is not int or value["source_build"] < 1
            or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("bundle_sha256", "")))):
        raise ValueError("Verified stage-fgc promotion required")
    validate_release(value.get("release"))
    return value


def verify_image(info, name, release):
    labels = (info.get("Config") or {}).get("Labels") or {}
    if labels.get("org.opencontainers.image.revision") != release["commit"]:
        raise ValueError(f"{name}: pulled image revision differs from staging")
    if release[name] not in info.get("RepoDigests", []):
        raise ValueError(f"{name}: pulled image digest differs from staging")


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
        raise ValueError("PRD_URL must be an HTTPS origin without credentials or a path")
    host = parsed.hostname.encode("idna").decode("ascii").lower()
    if not re.fullmatch(r"[a-z0-9.-]+", host):
        raise ValueError("Invalid public hostname")
    if host == "xn--v42bn2m21nmmd.shinhanacademy.co.kr":
        raise ValueError("Replace the example hostname with the actual assigned domain")
    return "https://" + host, host


def seed_hashes(root):
    """BCrypt literals in the demo seed SQL. An account still holding one accepts the published demo password."""
    found = set()
    for folder in ("demo", "demo-agent"):
        for path in sorted((pathlib.Path(root) / "src/main/resources/db" / folder).glob("*.sql")):
            found.update(SEED_HASH.findall(path.read_text(encoding="utf-8")))
    if not found:
        raise ValueError("No published demo seed hashes found")
    return sorted(found)


def unprotected_sql(root):
    """Login IDs that are ACTIVE and still hold a published seed hash; empty output means protected."""
    accounts = ", ".join(f"'{name}'" for name in SEED_ACCOUNTS)
    hashes = ", ".join(f"'{value}'" for value in seed_hashes(root))
    return ("SELECT coalesce(string_agg(login_id, ',' ORDER BY login_id), '') FROM fgc.app_user "
            f"WHERE login_id IN ({accounts}) AND account_status = 'ACTIVE' AND password_hash IN ({hashes});")


def published_logins(root):
    """(account, login body with the published demo password). The deploy sends each and requires a refusal."""
    seed = pathlib.Path(root) / "src/main/resources/db/demo/V3__seed_demo_data.sql"
    passwords = SEED_PASSWORD.findall(seed.read_text(encoding="utf-8"))
    if len(passwords) != 1:
        raise ValueError("Published demo password not found in V3")
    return [(name, json.dumps({"loginId": name, "password": passwords[0]})) for name in SEED_ACCOUNTS]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("promote")
    for name in ("bundle", "release", "build", "jenkins_url", "output"):
        p.add_argument(name)
    for command in ("commit", "prepare"):
        p = commands.add_parser(command)
        p.add_argument("promotion")
        if command == "prepare":
            p.add_argument("directory")
    p = commands.add_parser("verify")
    p.add_argument("directory")
    p = commands.add_parser("origin")
    p.add_argument("url")
    p = commands.add_parser("unprotected-sql")
    p.add_argument("root")
    p = commands.add_parser("published-logins")
    p.add_argument("root")
    args = parser.parse_args()
    if args.command == "origin":
        print("\n".join(public_origin(args.url)))
    elif args.command == "unprotected-sql":
        print(unprotected_sql(args.root))
    elif args.command == "published-logins":
        print("\n".join(f"{name}\t{body}" for name, body in published_logins(args.root)))
    elif args.command == "promote":
        value = stage_promotion(args.bundle, args.release, args.build, args.jenkins_url)
        pathlib.Path(args.output).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    elif args.command == "commit":
        print(read_promotion(args.promotion)["release"]["commit"])
    elif args.command == "prepare":
        promotion = read_promotion(args.promotion)
        release = promotion["release"]
        path = pathlib.Path(args.directory)
        (path / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
        (path / "promotion.json").write_text(json.dumps(promotion, indent=2) + "\n", encoding="utf-8")
        (path / "release.env").write_text(f"API_IMAGE={release['api']}\nWEB_IMAGE={release['web']}\n", encoding="utf-8")
    elif args.command == "verify":
        path = pathlib.Path(args.directory)
        release = read_release(path / "release.json")
        for name in ("api", "web"):
            info = json.loads((path / f"{name}-inspect.json").read_text(encoding="utf-8"))[0]
            verify_image(info, name, release)
        nginx = path / "nginx/default.conf.template"
        nginx.write_text(adapt_nginx(nginx.read_text(encoding="utf-8")), encoding="utf-8")


if __name__ == "__main__":
    main()
