// v1.4 `mic` capability tests for dsh-go-bridge (docs/design/native-plugins-protocol.md §11.6):
// manifest declaration, effect validation, lease lifetime and PCM frame delivery. Unit-level: a
// fake core and connection, no DSH, no app, no microphone.
//   node --test runtime/tests/native-mic.test.mjs
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Buffer } from 'node:buffer';
import { CAP_EVENTS, FEATURES, TERMINAL_EVENTS, capabilityEffect, parseCapabilities } from '../bridge/dsh-go-bridge/lib/native/capabilities.js';
import { CapabilityRuntime } from '../bridge/dsh-go-bridge/lib/native/capability-runtime.js';

const manifestOf = (caps) => {
  const out = { issues: [], ids: new Map(), items: { panels: [] }, rawIndex: new Map() };
  parseCapabilities({ capabilities: caps, contributes: {} }, out);
  return out;
};

const effect = (e, features = ['mic'], declared = ['mic']) =>
  capabilityEffect(e, { capabilities: { declared, features, media: [], overlays: [] } });

/** A plugin record plus the smallest core CapabilityRuntime needs. */
function fixture({ handler } = {}) {
  const parsed = manifestOf(['mic']);
  const rec = { pkg: 'mic-test', uid: 1, stamp: 1, parsed };
  const calls = [];
  const def = handler === undefined ? ((scope, frame) => { calls.push({ scope, frame }); }) : handler;
  const core = {
    plugin: (pkg) => (pkg === 'mic-test' ? rec : undefined),
    stateOf: () => 'ok',
    registration: (pkg, kind, id) => (kind === 'mic' && id === 'frames' && pkg === 'mic-test' ? { def, token: {} } : undefined),
    health: new Map(),
    onChanged: () => () => {},
    capabilityChanged: undefined,
    violation: () => {},
  };
  const runtime = new CapabilityRuntime(core, () => {});
  const sent = [];
  const conn = { helloed: true, ws: { closed: false, bufferedAmount: 0 }, features: ['mic'], send: (m) => sent.push(m) };
  const scope = { scope: { sessionId: 's1' }, key: 's1' };
  const issues = [];
  const start = () => {
    const effects = runtime.bind([{ type: 'mic', op: 'start', instance: 'capture' }], rec, conn, scope, '', issues);
    return effects[0]?.lease;
  };
  return { runtime, rec, conn, scope, issues, start, calls, sent };
}

const pcm = (bytes = 640) => Buffer.alloc(bytes, 0x11);
const frame = (lease, seq, body = pcm()) => ({ t: 'mic.frame', lease, seq, pcm: body.toString('base64') });

test('mic is a declared capability and a validated effect', () => {
  assert.ok(FEATURES.includes('mic'));
  const parsed = manifestOf(['mic']);
  assert.deepEqual(parsed.capabilities, ['mic']);
  assert.equal(parsed.issues.length, 0);

  const ok = effect({ type: 'mic', op: 'start', instance: 'capture' });
  assert.equal(ok.effect.type, 'mic');
  assert.equal(ok.effect.op, 'start');
  assert.equal(ok.effect.instance, 'capture');

  // Not negotiated by the app: dropped, not a violation (the v1.2 rule).
  const absent = capabilityEffect({ type: 'mic', op: 'start', instance: 'capture' }, { capabilities: { declared: ['mic'], features: [] } });
  assert.equal(absent.effect, undefined);
  assert.equal(absent.issue.violation, false);

  for (const bad of [
    { type: 'mic', op: 'play', instance: 'capture' },
    { type: 'mic', op: 'start' },
    { type: 'mic', op: 'start', instance: 'capture', rate: 48000 },
    { type: 'mic', op: 'start', instance: 'capture', channels: 2 },
  ]) {
    const r = effect(bad);
    assert.equal(r.effect, undefined, JSON.stringify(bad));
    assert.equal(r.issue.code, 'bad-effect');
    assert.equal(r.issue.violation, true);
  }
});

test('mic events are terminal and belong to the mic role', () => {
  assert.equal(CAP_EVENTS['mic.stopped'], 'mic');
  assert.equal(CAP_EVENTS['mic.error'], 'mic');
  assert.ok(TERMINAL_EVENTS.has('mic.stopped'));
  assert.ok(TERMINAL_EVENTS.has('mic.error'));
});

test('a mic start opens one lease and frames reach the handler', () => {
  const f = fixture();
  const lease = f.start();
  assert.equal(typeof lease, 'string');
  assert.equal(f.issues.length, 0);

  f.runtime.message(f.conn, frame(lease, 1));
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].frame.frame.byteLength, 640);
  assert.equal(f.calls[0].frame.rate, 16000);
  assert.equal(f.calls[0].frame.channels, 1);
  assert.equal(f.calls[0].frame.bitsPerSample, 16);
  assert.equal(f.calls[0].scope.sessionId, 's1');

  // Duplicate and backwards seq values are ignored.
  f.runtime.message(f.conn, frame(lease, 1));
  assert.equal(f.calls.length, 1);
});

test('frames without a lease, a role or the handler are dropped', () => {
  const f = fixture();
  const lease = f.start();

  f.runtime.message(f.conn, frame('not-a-lease', 1));
  const other = { ...f.conn, features: ['mic'] };
  f.runtime.message(other, frame(lease, 1));
  assert.equal(f.calls.length, 0, 'another connection is not the lease owner');

  // Oversized frame: dropped before the handler.
  f.runtime.message(f.conn, frame(lease, 1, pcm(1025)));
  assert.equal(f.calls.length, 0);

  // Not base64 / empty payload.
  f.runtime.message(f.conn, { t: 'mic.frame', lease, seq: 1, pcm: 'not base64!!' });
  f.runtime.message(f.conn, { t: 'mic.frame', lease, seq: 2, pcm: '' });
  assert.equal(f.calls.length, 0);

  // A plugin without the mic handler gets nothing, but the lease still exists.
  const bare = fixture({ handler: null });
  const bareLease = bare.start();
  assert.equal(typeof bareLease, 'string');
  bare.runtime.message(bare.conn, frame(bareLease, 1));
  assert.equal(bare.calls.length, 0);
  assert.equal(bare.runtime.leases.size, 1);
});

test('frame rate is capped and overflow is dropped, not queued', () => {
  const f = fixture();
  const lease = f.start();
  let seq = 0;
  for (let i = 0; i < 40; i++) f.runtime.message(f.conn, frame(lease, ++seq));
  // Burst is 10: the rest of the same tick cannot pass.
  assert.ok(f.calls.length <= 10, `delivered ${f.calls.length} frames in one burst`);
  assert.ok(f.calls.length > 0);
});

test('mic.stopped ends the role and revokes the lease', () => {
  const f = fixture();
  const lease = f.start();
  f.runtime.message(f.conn, frame(lease, 1));
  assert.equal(f.calls.length, 1);

  f.runtime.message(f.conn, { t: 'cap.event', lease, seq: 1, event: 'mic.stopped' });
  assert.equal(f.runtime.leases.size, 0, 'the lease is gone');
  f.runtime.message(f.conn, frame(lease, 2));
  assert.equal(f.calls.length, 1, 'no frames after the lease ended');
});

test('mic stop is accepted for the lease and ends with the app\'s terminal event', () => {
  const f = fixture();
  const lease = f.start();
  // The app stops the capture; the bridge keeps the lease until the app reports it stopped.
  const accepted = f.runtime.bind([{ type: 'mic', op: 'stop', instance: 'capture' }], f.rec, f.conn, f.scope, '', f.issues);
  assert.equal(accepted[0]?.lease, lease);
  f.runtime.message(f.conn, { t: 'cap.event', lease, seq: 1, event: 'mic.stopped' });
  assert.equal(f.runtime.leases.size, 0);
  // A stop effect for a lease that never started a role is rejected.
  const fresh = fixture();
  const late = fresh.runtime.bind([{ type: 'mic', op: 'stop', instance: 'capture' }], fresh.rec, fresh.conn, fresh.scope, '', fresh.issues);
  assert.equal(late.length, 0);
});

test('a slow plugin keeps at most the pending cap in flight', () => {
  let release, started = 0;
  const gate = new Promise((resolve) => { release = resolve; });
  const f = fixture({ handler: () => { started++; return gate; } });
  const lease = f.start();
  for (let seq = 1; seq <= 40; seq++) f.runtime.message(f.conn, frame(lease, seq));
  assert.equal(started, 8, 'only micPending handlers start');
  assert.ok(f.runtime.leases.get(lease).micDropped >= 1, 'the rest were dropped, not queued');
  release();
});

test('the bridge tells the app when it revokes a mic lease itself', () => {
  const f = fixture();
  const lease = f.start();
  // A plugin reload: the record the lease was bound to is replaced.
  f.runtime.core.plugin = (pkg) => (pkg === 'mic-test' ? { ...f.rec, stamp: 99 } : undefined);
  f.runtime.sweep();
  assert.equal(f.runtime.leases.size, 0);
  assert.ok(f.sent.some((m) => m.t === 'mic.end' && m.lease === lease), 'mic.end went out');
  f.runtime.message(f.conn, frame(lease, 1));
  assert.equal(f.calls.length, 0, 'a dead lease gets no frames');
});

test('an app-ended lease sends no mic.end and one terminal is enough', () => {
  const f = fixture();
  const lease = f.start();
  f.runtime.message(f.conn, { t: 'cap.event', lease, seq: 1, event: 'mic.stopped' });
  f.runtime.message(f.conn, { t: 'cap.event', lease, seq: 2, event: 'mic.stopped' });
  assert.equal(f.sent.filter((m) => m.t === 'mic.end').length, 0);
  assert.equal(f.runtime.leases.size, 0);
});

test('the bridge reports what it accepted while a lease is open', async () => {
  const f = fixture();
  const lease = f.start();
  for (let seq = 1; seq <= 3; seq++) f.runtime.message(f.conn, frame(lease, seq));
  await new Promise((resolve) => setTimeout(resolve, 300));
  const status = f.sent.filter((m) => m.t === 'mic.status' && m.lease === lease);
  assert.ok(status.length >= 1, 'a status went out');
  assert.equal(status.at(-1).got, 3);
  assert.equal(status.at(-1).seq, 3);
  assert.equal(status.at(-1).dropped, 0);
  // Revoking stops the heartbeat.
  f.runtime.message(f.conn, { t: 'cap.event', lease, seq: 1, event: 'mic.stopped' });
  const before = f.sent.length;
  await new Promise((resolve) => setTimeout(resolve, 300));
  assert.equal(f.sent.filter((m) => m.t === 'mic.status').length, 1, 'no status after the lease ended');
  assert.ok(f.sent.length >= before);
});
