"""정적 폰트 경량화 (2026-09-15, 첫 화면 28MB → 1.6MB).

원본:
  - Material Symbols Rounded 가변 ttf 14.7MB  → 템플릿·JS 에서 쓰는 아이콘만 남긴 woff2 (~90KB)
  - Noto Sans KR 가변 ttf 10.2MB              → KS X 1001 한글 2,350자 + 라틴/구두점/기호 woff2 (~330KB)

새 아이콘을 템플릿에 추가하면 이 스크립트를 다시 돌린다. 원본 ttf 는 Google Fonts 에서 받아
아무 경로에 두고 인자로 준다 (저장소에는 원본을 넣지 않는다 — 크기 때문).

    pip install fonttools brotli
    python tools/subset_fonts.py <material-symbols-rounded.ttf> <noto-sans-kr-variable.ttf>

아이콘 목록은 templates/**/*.html 의 <span class="material-symbols-...">이름</span> 과
static/js 의 textContent 에서 자동 수집하고, 아래 EXTRA 를 더한다.
"""
import os
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
OUT = RES / "static/fonts"

# 템플릿에 없지만 JS 가 문자열로 만들 수 있는 아이콘. 넉넉히.
EXTRA = """check delete cancel done more_vert more_horiz filter_list upload print arrow_forward
arrow_upward arrow_downward check_circle schedule calendar_month notifications home list table_chart
sort content_copy link mail star unfold_more keyboard_arrow_down keyboard_arrow_up keyboard_arrow_left
keyboard_double_arrow_right first_page last_page sync cloud_upload attach_file task_alt block pending
history account_circle visibility_off arrow_drop_down expand_less remove group folder""".split()


def collect_icon_names() -> set[str]:
    names = set(EXTRA)
    pat = re.compile(r'material-symbols[^>]*>\s*([a-z_]+)\s*<')
    for html in (RES / "templates").rglob("*.html"):
        names.update(pat.findall(html.read_text(encoding="utf-8")))
    pat_js = re.compile(r'''textContent\s*=\s*['"]([a-z_]+)['"]''')
    for js in (RES / "static/js").rglob("*.js"):
        names.update(pat_js.findall(js.read_text(encoding="utf-8")))
    return names


def subset_material(src: Path) -> None:
    font = TTFont(src)
    glyphs = set(font.getGlyphOrder())
    names = collect_icon_names()
    keep = {n for n in names if n in glyphs} | {n + ".fill" for n in names if n + ".fill" in glyphs}
    missing = sorted(n for n in names if n not in glyphs)

    # 리가처 규칙을 먼저 잘라야 한다. 안 자르면 a-z 글리프를 통해 closure 가 아이콘 전부를 끌어온다.
    def prune(st):
        if st.LookupType == 4:
            for first, ligs in list(st.ligatures.items()):
                ligs[:] = [l for l in ligs if l.LigGlyph in keep]
                if not ligs:
                    del st.ligatures[first]

    for lk in font["GSUB"].table.LookupList.Lookup:
        for st in lk.SubTable:
            prune(st.ExtSubTable if lk.LookupType == 7 else st)

    opts = subset.Options()
    opts.flavor = "woff2"
    opts.layout_features = ["liga", "calt", "rlig", "rvrn"]
    opts.notdef_outline = True
    opts.name_IDs = ["*"]
    s = subset.Subsetter(opts)
    s.populate(glyphs=sorted(keep), text="abcdefghijklmnopqrstuvwxyz_0123456789")
    s.subset(font)
    out = OUT / "material-symbols-rounded.woff2"
    subset.save_font(font, str(out), opts)
    print(f"material: {len(keep)} glyphs -> {out.stat().st_size // 1024} KB; 원본에 없는 이름: {missing}")


def subset_noto(src: Path) -> None:
    def ksx1001(cp: int) -> bool:
        try:
            return len(chr(cp).encode("euc_kr")) == 2
        except UnicodeEncodeError:
            return False

    uni = {cp for cp in range(0xAC00, 0xD7A4) if ksx1001(cp)}
    for a, b in [(0x20, 0x7E), (0xA0, 0xFF), (0x2010, 0x2027), (0x2030, 0x205E), (0x20A9, 0x20A9),
                 (0x20AC, 0x20AC), (0x2100, 0x2122), (0x2190, 0x2199), (0x2200, 0x22FF), (0x2460, 0x24FF),
                 (0x2500, 0x257F), (0x25A0, 0x25FF), (0x2600, 0x26FF), (0x3000, 0x303F), (0x3131, 0x318E),
                 (0xFF01, 0xFF5E), (0xFFE6, 0xFFE6)]:
        uni.update(range(a, b + 1))
    opts = subset.Options()
    opts.flavor = "woff2"
    opts.layout_features = ["kern", "liga", "calt", "ccmp", "mark", "mkmk", "rvrn"]
    opts.notdef_outline = True
    opts.name_IDs = ["*"]
    font = subset.load_font(str(src), opts)
    s = subset.Subsetter(opts)
    s.populate(unicodes=sorted(uni))
    s.subset(font)
    out = OUT / "noto-sans-kr-variable.woff2"
    subset.save_font(font, str(out), opts)
    print(f"noto: {len(uni)} codepoints -> {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    subset_material(Path(sys.argv[1]))
    subset_noto(Path(sys.argv[2]))
