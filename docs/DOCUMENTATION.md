# Documentation maintenance

## Published site

The documentation is published at [kannanthiraviam.github.io/OmniFlux-Exchange](https://kannanthiraviam.github.io/OmniFlux-Exchange/). GitHub Actions builds Material for MkDocs and deploys a Pages artifact on pushes to `main`. No `gh-pages` branch is created.

`mkdocs.yml` defines the sidebar and reading order. Current guides appear in the primary navigation. Historical proof evidence and the decision log remain available through source links. Agent specs, plans, and review working files are ignored by Git and excluded from the site. Current scope and open issues belong in Implementation status.

## Typography

The site uses system fonts: Segoe UI on Windows, Helvetica Neue where installed, and the browser's sans-serif fallback elsewhere. Code uses Cascadia Code, Consolas, or monospace. No external font download is needed. Body text is 17 px at the standard desktop scale, with a 1.65 line height; navigation and tables use smaller reference text. Diagrams use the same font stack and provide full-size links.

## Preview locally

Install the small documentation dependency set in a checkout-local environment:

```powershell
$env:UV_CACHE_DIR = "$PWD/.tmp_logs/uv-cache"
uv venv .tmp_logs/docs-venv
uv pip install --python .tmp_logs/docs-venv/Scripts/python.exe -r docs-requirements.txt
.tmp_logs/docs-venv/Scripts/mkdocs.exe serve
```

Open `http://127.0.0.1:8000/OmniFlux-Exchange/`. Stop the preview with Ctrl+C.

To validate the static build:

```powershell
.tmp_logs/docs-venv/Scripts/mkdocs.exe build --strict
pwsh -File scripts/quality-gate.ps1 -RepositoryOnly
```

On Unix, use `.tmp_logs/docs-venv/bin/python` and `.tmp_logs/docs-venv/bin/mkdocs`. Generated output stays under `.tmp_logs/` and is ignored by Git.

## Writing conventions

Use plain ASCII punctuation and descriptive link labels in maintained guides. Prefer named sections to numeric references, which become stale when the reading order changes. Define unfamiliar terms at first use and use examples for ownership and recovery behavior. The Key concepts page is the beginner reference. Heading permalink markers are disabled; heading IDs and table-of-contents links still work. Keep historical evidence unchanged.

## Edit diagrams

Mermaid blocks in Markdown are the source of truth. GitHub renders those blocks natively. The Pages build substitutes checked-in SVGs for predictable, high-contrast labels and full-size viewing. The build checks source hashes and fails if an SVG needs regeneration.

Install the renderer locally and regenerate after editing any Mermaid block:

```powershell
npm.cmd install --prefix .tmp_logs/diagram-tools --cache .tmp_logs/npm-cache --no-audit --no-fund @mermaid-js/mermaid-cli@11.17.0
node scripts/render-diagrams.mjs
```

The install supplies Puppeteer's browser. To use an existing Chrome installation instead, set `PUPPETEER_SKIP_DOWNLOAD=true` before installation and `OMNIFLUX_DOCS_CHROME` to its executable path before rendering.

Review the SVGs, commit `docs/assets/diagrams/` with the Markdown changes, and run the strict build. Keep sequences to one phase and a small number of participants. Put detailed conditions in prose or tables. Avoid semicolons in sequence message/note text because Mermaid treats them as statement separators.

## GitHub Pages settings

The repository's Pages source is **GitHub Actions**. The workflow uses Pages artifacts and deployment permissions, keeping local and remote history on `main` only. See [GitHub's custom workflow guide](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).
