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
    args = parser.parse_args()
    if args.command == "origin":
        print("\n".join(public_origin(args.url)))
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
