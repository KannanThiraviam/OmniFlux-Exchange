import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

// Execute the dashboard's actual validation helper without a DOM or network.
const source = readFileSync(new URL('../src/main/resources/static/app.js', import.meta.url), 'utf8');
const start = source.indexOf('  function downloadTarget(');
const end = source.indexOf('  /* View routing', start);
assert.ok(start >= 0 && end > start, 'dashboard download validation must be present');
const validate = vm.runInNewContext(source.slice(start, end) + '\ndownloadTarget;', { URL, Set });
const config = {
  'omniflux.storage.public-endpoint': 'https://storage.example:9443',
  'omniflux.storage.path-style': true,
  'omniflux.storage.bucket': 'exports'
};
const signed = 'https://storage.example:9443/exports/file.csv?X-Amz-Signature=abc%2F123';
assert.equal(validate(signed, config), signed, 'presigned paths and queries must remain intact');
assert.equal(validate('http://localhost:9005/exports/file.xlsx', {
  ...config, 'omniflux.storage.public-endpoint': 'http://localhost:9005'
}), 'http://localhost:9005/exports/file.xlsx', 'local storage must remain supported');
for (const target of [
  'https://evil.example/file.csv',
  'https://storage.example.evil.example:9443/file.csv',
  'https://storage.example:9444/file.csv',
  'http://storage.example:9443/file.csv',
  'https://storage.example:9443@evil.example/file.csv',
  'https://user:password@storage.example:9443/file.csv',
  'javascript:alert(1)',
  'data:text/html,hello',
  '//storage.example:9443/file.csv',
  '/exports/file.csv',
  'https://exports.storage.example:9443/file.csv'
]) {
  assert.throws(() => validate(target, config), undefined, `must reject ${target}`);
}
const virtual = { ...config, 'omniflux.storage.path-style': false };
assert.equal(validate('https://exports.storage.example:9443/file.csv', virtual),
  'https://exports.storage.example:9443/file.csv', 'virtual-hosted storage must remain supported');
assert.throws(() => validate('https://other.storage.example:9443/file.csv', virtual));
assert.throws(() => validate(signed, { ...config, 'omniflux.storage.public-endpoint': '' }));
assert.throws(() => validate(signed, { ...config, 'omniflux.storage.public-endpoint': 'file:///tmp' }));
assert.throws(() => validate(signed, { ...config, 'omniflux.storage.public-endpoint': 'https://user@storage.example:9443' }));
assert.throws(() => validate(signed, undefined));
console.log('All dashboard download-origin regression checks passed.');
