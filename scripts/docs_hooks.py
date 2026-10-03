"""Build readable diagrams and route source links without changing Markdown."""

import hashlib
import json
import posixpath
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIAGRAM = re.compile(r"```mermaid\s*\n(.*?)```", re.DOTALL)
SOURCE_BASE = "https://github.com/KannanThiraviam/OmniFlux-Exchange/blob/main/"


def archived(path):
    in_archive = path.startswith(("docs/superpowers/", "docs/evidence/", "docs/reviews/"))
    in_archive |= path in {"docs/superpowers", "docs/evidence", "docs/reviews"}
    return in_archive or path == "docs/architecture/decision-log.md"


def on_page_markdown(markdown, page, config, **kwargs):
    manifest = json.loads((ROOT / "docs/assets/diagrams/manifest.json").read_text(encoding="utf-8"))
    number = 0
    source_uri = page.file.src_uri

    def diagram(match):
        nonlocal number
        number += 1
        key = f"docs/{source_uri}#{number}"
        record = manifest[key]
        definition = match.group(1).strip()
        if hashlib.sha256(definition.encode()).hexdigest() != record["sha256"]:
            raise ValueError(f"Diagram changed: {key}. Run node scripts/render-diagrams.mjs.")
        target = record["asset"].removeprefix("docs/")
        relative = posixpath.relpath(target, posixpath.dirname(source_uri) or ".")
        return (
            '<figure class="diagram" markdown="1">\n\n'
            f'![Diagram {number}: {page.title}]({relative})\n\n'
            '<figcaption markdown="1">\n\n'
            f'[Open full-size diagram]({relative}){{target="_blank" rel="noopener"}}'
            '\n\n</figcaption>\n\n</figure>'
        )

    markdown = re.sub(r"\n\n\[Open full-size diagram\]\([^)]+\)", "", markdown)
    markdown = DIAGRAM.sub(diagram, markdown)
    # GitHub links to implementation and archived evidence remain useful on Pages.
    def source_link(match):
        target = match.group(1)
        if target.startswith(("http:", "https:", "#", "mailto:", "data:")):
            return match.group(0)
        normalized = posixpath.normpath(posixpath.join("docs", posixpath.dirname(source_uri), target))
        if normalized == "docs/README.md":
            home = posixpath.relpath("index.md", posixpath.dirname(source_uri) or ".")
            return f"]({home})"
        if not normalized.startswith("docs/") or archived(normalized):
            return f"]({SOURCE_BASE}{normalized})"
        return match.group(0)

    return re.sub(r"\]\(([^\s)]+)\)", source_link, markdown)
