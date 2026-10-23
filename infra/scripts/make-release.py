import json
import os
import pathlib
import re
import sys

# 문서 전용 판정은 CI(ci.yml changes·publish)와 같은 규칙을 쓴다
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / '.github' / 'scripts'))
from e2e_policy import documentation_only_since  # noqa: E402

commit = os.environ['FGC_COMMIT']
assert re.fullmatch(r'[a-f0-9]{40}', commit)
images = {}
revisions = set()
for name in ('api', 'web'):
    info = json.loads(pathlib.Path(f'{name}-inspect.json').read_text())
    digest = info['Digest']
    assert re.fullmatch(r'sha256:[a-f0-9]{64}', digest)
    labels = info.get('Labels') or {}
    revision = labels.get('org.opencontainers.image.revision')
    # 문서 전용 push 는 CI 가 직전 커밋 이미지에 태그만 더한다(digest·라벨 유지). 그 경우 라벨은 HEAD 의 문서 전용 조상이다.
    assert revision == commit or documentation_only_since(revision, commit), name
    revisions.add(revision)
    images[name] = f'ghcr.io/feegachu/fgc/{name}@{digest}'
assert len(revisions) == 1, revisions  # api·web 은 같은 커밋에서 함께 빌드된다
manifest = {
    'schema': 1,
    'commit': commit,
    'image_revision': revisions.pop(),
    'api': images['api'],
    'web': images['web'],
    'ci_build_url': os.environ.get('BUILD_URL', ''),
}
pathlib.Path('release.json').write_text(json.dumps(manifest, indent=2) + '\n')