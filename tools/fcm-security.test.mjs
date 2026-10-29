import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import {mkdirSync, mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const project = fileURLToPath(new URL('..', import.meta.url));

test('FCM commands require an explicit private shared token before contacting Google', async t => {
  mkdirSync(join(project, '.tmp'), {recursive: true});
  const root = mkdtempSync(join(project, '.tmp', 'fcm-security-'));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const keys = generateKeyPairSync('rsa', {modulusLength: 2048});
  const credentials = join(root, 'service-account.json');
  writeFileSync(credentials, JSON.stringify({project_id: 'fixture-project', client_email: 'fixture@example.invalid',
    private_key: keys.privateKey.export({type: 'pkcs8', format: 'pem'})}), {mode: 0o600});
  const preload = join(root, 'preload.mjs');
  writeFileSync(preload, `
import assert from 'node:assert/strict';
import {verify} from 'node:crypto';
import {writeFileSync} from 'node:fs';
let requests = 0;
globalThis.fetch = async (input, options) => {
  requests++;
  writeFileSync(process.env.FIXTURE_TRACE, JSON.stringify({requests}));
  assert.equal(options.method, 'POST');
  const url = String(input);
  if (url === 'https://oauth2.googleapis.com/token') {
    const [header, payload, signature] = options.body.get('assertion').split('.');
    assert.equal(JSON.parse(Buffer.from(payload, 'base64url')).aud, url);
    assert.equal(verify('RSA-SHA256', Buffer.from(header + '.' + payload), process.env.FIXTURE_PUBLIC_KEY, Buffer.from(signature, 'base64url')), true);
    return new Response(JSON.stringify({access_token: 'fixture-access-token'}));
  }
  assert.equal(url, 'https://fcm.googleapis.com/v1/projects/fixture-project/messages:send');
  assert.equal(options.headers.Authorization, 'Bearer fixture-access-token');
  const body = JSON.parse(options.body);
  assert.equal(body.message.data.action, 'hangup');
  assert.equal(body.message.token, 'fixture-device-token');
  return new Response(JSON.stringify({name: 'fixture-accepted'}));
};
`);
  const run = (token, name) => {
    const trace = join(root, name + '.json');
    const args = ['--import', preload, 'tools/send-fcm-call.mjs', '--action', 'hangup', '--device-token', 'fixture-device-token'];
    if (token !== undefined) args.push('--call-token', token);
    const result = spawnSync(process.execPath, args, {cwd: project, encoding: 'utf8', timeout: 10000,
      env: {...process.env, NODE_OPTIONS: '', GOOGLE_APPLICATION_CREDENTIALS: credentials,
        CALL_TOKEN: '', DEVICE_TOKEN: '', ACTION: '', PROJECT_ID: '', NUMBER: '', PORT: '',
        FIXTURE_TRACE: trace, FIXTURE_PUBLIC_KEY: keys.publicKey.export({type: 'spki', format: 'pem'})}});
    assert.ifError(result.error);
    return {...result, requests: existsSync(trace) ? JSON.parse(readFileSync(trace)).requests : 0};
  };

  for (const [name, token] of [['missing', undefined], ['legacy', 'dev-token']]) {
    await t.test('rejects ' + name + ' token without a network attempt', () => {
      const result = run(token, name);
      assert.notEqual(result.status, 0);
      assert.equal(result.requests, 0);
      assert.match(result.stderr, /token/i);
    });
  }
  await t.test('explicit token preserves the signed OAuth and FCM request protocol', () => {
    const result = run('fixture-private-shared-token', 'explicit');
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.requests, 2);
    assert.equal(result.stdout.trim(), 'FCM message accepted: fixture-accepted');
  });
});
