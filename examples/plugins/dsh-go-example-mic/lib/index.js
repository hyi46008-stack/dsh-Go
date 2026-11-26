// 麦克风测试：最小的 `mic` 能力样本（协议 v1.4）。
//
// 只统计帧的数量、序号、字节数和估算帧率；不保存、不上传、不播放任何音频。
// 面板上的「开始」返回 `{ type: 'mic', op: 'start' }`，App 先问授权、再开系统麦克风，
// 然后把 16 kHz 单声道 PCM16 的 20 ms 帧交给下面的 onMic。
import { micView, words } from './view.js'

/** Longest window the rate estimate looks at. */
const WINDOW_MS = 1000

export const name = 'dsh-go-example-mic'

export function apply(ctx) {
  const state = {
    active: false,
    frames: 0,
    bytes: 0,
    lastSeq: 0,
    lastBytes: 0,
    times: [],
    terminal: undefined,
    error: undefined,
  }

  const snapshot = () => ({
    active: state.active,
    frames: state.frames,
    bytes: state.bytes,
    lastSeq: state.lastSeq,
    lastBytes: state.lastBytes,
    // Frames per second over the last second, one decimal.
    fps: Math.round(rate() * 10) / 10,
    terminal: state.terminal,
    error: state.error,
  })

  const rate = () => {
    const now = Date.now()
    while (state.times.length > 0 && now - state.times[0] > WINDOW_MS) state.times.shift()
    return state.times.length
  }

  ctx.inject(['dshGo'], (ctx) => {
    ctx.dshGo.panel('mic', {
      view: (_scope, caps) => micView(snapshot(), caps),
      data: (_scope, emit) => {
        emit(snapshot())
        // 4 Hz is the panel's ceiling; the app diffs, so an unchanged snapshot is free.
        const timer = setInterval(() => emit(snapshot()), 500)
        return () => clearInterval(timer)
      },
      actions: {
        start: (_scope, event) => {
          if (state.active) return [{ type: 'toast', text: words(event?.locale).already }]
          state.error = undefined
          state.terminal = undefined
          return [{ type: 'mic', op: 'start', instance: 'test' }]
        },
        stop: () => (state.active ? [{ type: 'mic', op: 'stop', instance: 'test' }] : []),
        reset: () => {
          state.frames = 0
          state.bytes = 0
          state.lastSeq = 0
          state.lastBytes = 0
          state.times = []
          state.terminal = undefined
          state.error = undefined
          return [{ type: 'refresh' }]
        },
      },
    })

    // Frames: count them. The bytes are looked at and dropped, never written anywhere.
    ctx.dshGo.onMic((_scope, frame) => {
      if (!state.active) state.active = true
      state.frames++
      state.bytes += frame.frame.byteLength
      state.lastSeq = frame.seq
      state.lastBytes = frame.frame.byteLength
      state.times.push(Date.now())
      if (state.times.length > 200) state.times.shift()
    })

    // Terminals: one per role; a second one would be a bridge bug.
    ctx.dshGo.onCapability((_scope, event) => {
      if (event.event === 'mic.stopped' || event.event === 'mic.error') {
        state.active = false
        state.terminal = event.event
        if (event.event === 'mic.error') state.error = event.reason ?? 'error'
      }
    })
  })
}
