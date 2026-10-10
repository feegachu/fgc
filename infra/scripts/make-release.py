import json
import os
import pathlib
import re

commit = os.environ['FGC_COMMIT']
assert re.fullmatch(r'[a-f0-9]{40}', commit)
images = {}
for name in ('api', 'web'):
    info = json.loads(pathlib.Path(f'{name}-inspect.json').read_text())
    digest = info['Digest']
    assert re.fullmatch(r'sha256:[a-f0-9]{64}', digest)
    labels = info.get('Labels') or {}
    assert labels.get('org.opencontainers.image.revision') == commit, name
    images[name] = f'ghcr.io/feegachu/fgc/{name}@{digest}'
manifest = {
    'schema': 1,
    'commit': commit,
    'api': images['api'],
    'web': images['web'],
    'ci_build_url': os.environ.get('BUILD_URL', ''),
}
pathlib.Path('release.json').write_text(json.dumps(manifest, indent=2) + '\n')