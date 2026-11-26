// The mic test plugin, against the SDK's test host: what a tap returns, what a frame counts as,
// and that a terminal stops the panel. No bridge, no app, no microphone.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createTestHost } from '../../../../sdk/plugin-kit/lib/testing.js'
import { apply } from '../lib/index.js'

const frame = (seq, bytes = 640) => ({
  frame: new Uint8Array(bytes).fill(1),
  seq,
  rate: 16000,
  channels: 1,
  bitsPerSample: 16,
  signal: new AbortController().signal,
})

function host() {
  const h = createTestHost({ dir: new URL('..', import.meta.url).pathname, caps: { features: ['mic'] } })
  apply(h.ctx)
  return h
}

test('the start tap asks for a mic lease; stop only when one is running', async () => {
  const h = host()
  const started = await h.invoke('mic', { surface: 'panel', action: 'start' })
  assert.equal(started.effects.length, 1)
  assert.deepEqual(
    { type: started.effects[0].type, op: started.effects[0].op, instance: started.effects[0].instance },
    { type: 'mic', op: 'start', instance: 'test' },
  )
  // No frames yet: the panel still thinks nothing is running.
  const idle = h.subscribe('mic')
  assert.equal(idle.data.active, false)
  idle.stop()
  h.dispose()
})

test('frames are counted, never kept', () => {
  const h = host()
  const feed = h.subscribe('mic')
  return (async () => {
    await h.micFrame({}, frame(1))
    await h.micFrame({}, frame(2))
    await h.micFrame({}, frame(3, 512))
    const fresh = h.subscribe('mic')
    assert.equal(fresh.data.frames, 3)
    assert.equal(fresh.data.lastSeq, 3)
    assert.equal(fresh.data.lastBytes, 512)
    assert.equal(fresh.data.bytes, 640 + 640 + 512)
    assert.equal(fresh.data.active, true)
    fresh.stop()
    feed.stop()
    h.dispose()
  })()
})

test('a terminal event clears the running state and shows the code', async () => {
  const h = host()
  await h.micFrame({}, frame(1))
  await h.capabilityEvent({}, { instance: 'test', event: 'mic.error', seq: 1, reason: 'busy' })
  const feed = h.subscribe('mic')
  assert.equal(feed.data.active, false)
  assert.equal(feed.data.terminal, 'mic.error')
  assert.equal(feed.data.error, 'busy')
  feed.stop()
  h.dispose()
})

test('the plugin declares mic and one panel, and passes the kit checks', async () => {
  const h = host()
  assert.deepEqual(h.contributions().capabilities, ['mic'])
  assert.ok(h.contributions().contributes.panels.some((p) => p.id === 'mic'))
  assert.equal((await h.view('mic')).view.type, 'stack')
  h.dispose()
})
