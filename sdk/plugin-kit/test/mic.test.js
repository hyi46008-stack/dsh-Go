// v1.4 `mic` in the author kit: the declaration, the onMic registration, frame delivery through the
// test host and the same effect validator the bridge uses.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkEffects } from '../lib/effect-check.js'
import { createTestHost } from '../lib/testing.js'
import { parseManifest } from '../lib/bridge/manifest.js'

const pkg = { name: 'mic-plugin', dshGo: { v: 1, capabilities: ['mic'], contributes: {
  panels: [{ id: 'panel', title: 'Panel' }],
} } }

test('mic is a known capability in the manifest', () => {
  const parsed = parseManifest(pkg, process.cwd())
  assert.deepEqual(parsed.capabilities, ['mic'])
  assert.equal(parsed.issues.length, 0)
  const unknown = parseManifest({ name: 'x', dshGo: { v: 1, capabilities: ['sound'] } }, process.cwd())
  assert.deepEqual(unknown.capabilities ?? [], [])
  assert.ok(Array.isArray(unknown.issues))
})

test('the bridge validator accepts mic start/stop and rejects other ops', () => {
  const opts = { capabilities: { declared: ['mic'], features: ['mic'], media: [], overlays: [] } }
  assert.equal(checkEffects({ type: 'mic', op: 'start', instance: 'capture' }, opts).effects.length, 1)
  assert.equal(checkEffects({ type: 'mic', op: 'stop', instance: 'capture' }, opts).effects.length, 1)
  for (const bad of [
    { type: 'mic', op: 'play', instance: 'capture' },
    { type: 'mic', op: 'start' },
    { type: 'mic', op: 'start', instance: 'capture', rate: 44100 },
  ]) {
    const result = checkEffects(bad, opts)
    assert.equal(result.effects.length, 0)
    assert.equal(result.violation, true)
  }
  // Declared but not negotiated: dropped, as every v1.2 capability is.
  const off = checkEffects({ type: 'mic', op: 'start', instance: 'capture' }, { capabilities: { declared: ['mic'], features: [] } })
  assert.equal(off.effects.length, 0)
  assert.equal(off.violation, false)
})

test('onMic receives frames from the test host, and is refused without the capability', async () => {
  const h = createTestHost({ pkg })
  const frames = []
  h.dshGo.onMic((scope, frame) => { frames.push({ scope, frame }); return { command: 'ignored' } })
  await h.micFrame({ sessionId: 's1' }, { frame: new Uint8Array(640).fill(7), seq: 3, rate: 16000, channels: 1, bitsPerSample: 16 })
  assert.equal(frames.length, 1)
  assert.equal(frames[0].frame.frame.length, 640)
  assert.equal(frames[0].frame.seq, 3)
  assert.equal(frames[0].scope.sessionId, 's1')
  h.dispose()

  const bare = createTestHost({ pkg: { name: 'no-mic', dshGo: { v: 1 } } })
  bare.dshGo.onMic(() => { throw new Error('must not be called') })
  assert.ok(bare.contributions().issues.some((i) => i.code === 'undeclared' && i.message.includes('mic')))
  await bare.micFrame({}, { frame: new Uint8Array(2), seq: 1 })
  bare.dispose()
})
