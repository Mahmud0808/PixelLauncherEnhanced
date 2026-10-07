import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
README = ROOT / "README.md"
PAGE = ROOT / "docs" / "index.html"

CATEGORIES = {
    "Home screen": ("home", "Grid size, labels, the status bar and the dock: the parts you see every time you unlock."),
    "App drawer": ("drawer", "Tabs, hidden apps and quick launch, in a layout sized for the apps you actually have."),
    "Icons": ("icons", "Icon packs with a fallback order, per-app overrides, custom shapes and themed colours."),
    "Gestures": ("gestures", "Double tap and pinch to do something useful, and take back the space under the gesture bar."),
    "Recents": ("recents", "Kill, uninstall or lock apps, check memory at a glance and drop the buttons you never tap."),
    "Backup & restore": ("backup", "Save your home screen, app drawer and settings, then bring them back after a reset or a new phone."),
    "Advanced": ("advanced", "Launcher-level switches, and quicker ways back into the module from the launcher itself."),
}
SUPPORTED = "✅"
PARTIAL = "⚠️"


def parse_readme():
    text = README.read_text(encoding="utf-8")
    categories = []
    for block in re.finditer(r"<details>\s*<summary>(.*?)</summary>(.*?)</details>", text, re.S):
        name = block.group(1).strip()
        features = []
        for line in block.group(2).splitlines():
            line = line.strip()
            if not line.startswith("|"):
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            if len(cells) < 3 or cells[0].lower() == "feature" or set(cells[0]) <= set("-: "):
                continue
            features.append(cells[:3])
        if features:
            categories.append((name, features))
    if not categories:
        sys.exit("No feature tables found in README.md")
    unknown = [name for name, _ in categories if name not in CATEGORIES]
    if unknown:
        sys.exit(f"Add these categories to CATEGORIES in {Path(__file__).name}: {', '.join(unknown)}")
    return categories


def tag_for(pixel, launcher3):
    if pixel == SUPPORTED and launcher3 == SUPPORTED:
        return ""
    if pixel == SUPPORTED and launcher3 == PARTIAL:
        return ' <span class="tag">Partial</span>'
    if pixel == SUPPORTED:
        return ' <span class="tag">Pixel only</span>'
    if launcher3 == SUPPORTED:
        return ' <span class="tag">Launcher3 only</span>'
    return ' <span class="tag">Partial</span>'


def render(categories):
    index_items = []
    articles = []
    for name, features in categories:
        slug, desc = CATEGORIES[name]
        label = html.escape(name)
        index_items.append(
            f'              <li><a class="index__link" href="#f-{slug}" style="--tone: var(--tone-{slug})">'
            f'<span>{label}</span><span class="index__n">{len(features)}</span></a></li>'
        )
        rows = "\n".join(
            f"                <li>{html.escape(feature)}{tag_for(pixel, launcher3)}</li>"
            for feature, pixel, launcher3 in features
        )
        articles.append(
            f'            <article class="cat reveal" id="f-{slug}" data-cat style="--tone: var(--tone-{slug})">\n'
            f'              <header class="cat__head">\n'
            f'                <h3 class="cat__title"><span class="cat__icon" aria-hidden="true"><svg><use href="#c-{slug}"/></svg></span>{label}</h3>\n'
            f'                <p class="cat__desc">{html.escape(desc)}</p>\n'
            f"              </header>\n"
            f'              <ul class="feats">\n{rows}\n              </ul>\n'
            f"            </article>"
        )
    return "\n".join(index_items), "\n\n".join(articles)


def replace_block(page, opening, closing, body):
    start = page.find(opening)
    if start < 0:
        sys.exit(f"Marker not found in index.html: {opening}")
    inner = start + len(opening)
    end = page.find(closing, inner)
    if end < 0:
        sys.exit(f"Closing tag not found after: {opening}")
    indent = page[page.rfind("\n", 0, end) + 1:end]
    return page[:inner] + "\n" + body + "\n" + indent + page[end:]


def main():
    categories = parse_readme()
    index_html, articles_html = render(categories)
    total = sum(len(features) for _, features in categories)

    page = PAGE.read_text(encoding="utf-8")
    page = replace_block(page, '<ol class="index" data-index>', "</ol>", index_html)
    page = replace_block(page, '<div class="stack__body" data-features>', "</div>", articles_html)
    page = re.sub(r"\b\d+ settings\b", f"{total} settings", page)
    PAGE.write_text(page, encoding="utf-8", newline="\n")

    print(f"{total} features in {len(categories)} categories")
    for name, features in categories:
        print(f"  {name}: {len(features)}")


if __name__ == "__main__":
    main()
