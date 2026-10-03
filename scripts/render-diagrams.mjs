/** Render every Markdown Mermaid block and record the source hash for MkDocs. */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const toolsRoot = path.join(root, '.tmp_logs/diagram-tools/node_modules');
const { renderMermaid } = await import(pathToFileURL(path.join(toolsRoot, '@mermaid-js/mermaid-cli/src/index.js')));
const resolveTool = createRequire(path.join(toolsRoot, 'package.json'));
const { default: puppeteer } = await import(pathToFileURL(resolveTool.resolve('puppeteer')));
const outputRoot = path.join(root, 'docs/assets/diagrams');
await mkdir(outputRoot, { recursive: true });

function markdownFiles() {
  return execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: root })
    .toString('utf8').split('\0')
    .filter(file => file === 'README.md' || (file.startsWith('docs/') && file.endsWith('.md')))
    .map(file => path.join(root, file));
}

const config = {
  theme: 'base',
  fontFamily: 'Segoe UI, Helvetica Neue, sans-serif',
  themeVariables: {
    fontSize: '16px', primaryColor: '#edf5fa', primaryTextColor: '#17324d',
    primaryBorderColor: '#55849d', lineColor: '#395a72', textColor: '#17324d',
    secondaryColor: '#f3f8fb', tertiaryColor: '#ffffff',
    noteBkgColor: '#f3f8fb', noteTextColor: '#17324d',
    actorBkg: '#edf5fa', actorTextColor: '#17324d', actorBorder: '#55849d',
    signalColor: '#395a72', signalTextColor: '#17324d',
  },
  flowchart: { htmlLabels: false, curve: 'linear', nodeSpacing: 28, rankSpacing: 38 },
  sequence: { actorMargin: 28, messageFontSize: 15, noteFontSize: 14, wrap: true, width: 140 },
};
const browser = await puppeteer.launch({
  headless: true,
  ...(process.env.OMNIFLUX_DOCS_CHROME ? { executablePath: process.env.OMNIFLUX_DOCS_CHROME } : {}),
});
const manifest = {};
const failures = [];
let rendered = 0;
try {
  const files = markdownFiles();
  for (const file of files) {
    const original = await readFile(file, 'utf8');
    let updated = original;
    let number = 0;
    for (const match of original.matchAll(/```mermaid\s*\n([\s\S]*?)```/g)) {
      number++;
      const definition = match[1].replaceAll('\r\n', '\n').trim();
      const relative = path.relative(root, file).split(path.sep).join('/');
      const name = relative.replace(/\.md$/, '').replaceAll('/', '-') + '-' + String(number).padStart(2, '0') + '.svg';
      const asset = 'docs/assets/diagrams/' + name;
      try {
        const { data } = await renderMermaid(browser, definition, 'svg', {
          mermaidConfig: config, backgroundColor: 'white', viewport: { width: 1000, height: 800, deviceScaleFactor: 1 },
        });
        let svg = Buffer.from(data).toString('utf8');
        const bounds = svg.match(/viewBox="[^"]*?\s([\d.]+)\s([\d.]+)"/);
        if (bounds) svg = svg.replace('width="100%"', `width="${bounds[1]}" height="${bounds[2]}"`);
        await writeFile(path.join(root, asset), svg);
        manifest[relative + '#' + number] = {
          asset, sha256: createHash('sha256').update(definition).digest('hex'),
        };
        const linkPath = path.relative(path.dirname(file), path.join(root, asset)).split(path.sep).join('/');
        const link = `[Open full-size diagram](${linkPath})`;
        // Preserve native GitHub Mermaid while adding a zoomable SVG fallback.
        if (!original.includes(link)) updated = updated.replace(match[0], match[0] + '\n\n' + link);
        rendered++;
        console.log('Rendered ' + relative + ' #' + number);
      } catch (error) {
        failures.push(relative + ' #' + number + ': ' + error.message);
      }
    }
    if (updated !== original) await writeFile(file, updated);
  }
} finally {
  await browser.close();
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
} else {
  await writeFile(path.join(outputRoot, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Validated and rendered ${rendered} diagrams.`);
}
