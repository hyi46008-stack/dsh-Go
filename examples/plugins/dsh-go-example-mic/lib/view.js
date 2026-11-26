// The mic test panel: is it running, how many frames, the last seq/size, the rate.
// Everything here is a count or a length — never audio.

const zh = {
  title: '麦克风测试',
  running: '采集进行中',
  idle: '未采集',
  frames: '已收帧数',
  rate: '帧率',
  lastSeq: '最近序号',
  lastBytes: '最近帧',
  total: '累计字节',
  start: '开始采集',
  stop: '停止',
  reset: '清零',
  already: '已经在采集了',
  hint: '只统计数量与序号，录音不保存、不上传',
  error: '上次采集出错：',
  stopped: '上次已正常停止',
}
const en = {
  title: 'Mic test',
  running: 'Capturing',
  idle: 'Not capturing',
  frames: 'Frames',
  rate: 'Rate',
  lastSeq: 'Last seq',
  lastBytes: 'Last frame',
  total: 'Bytes',
  start: 'Start',
  stop: 'Stop',
  reset: 'Reset',
  already: 'Already capturing',
  hint: 'Counts only — nothing is saved or uploaded',
  error: 'Last capture failed: ',
  stopped: 'Last capture stopped',
}

export const words = (locale) => (String(locale ?? '').toLowerCase().startsWith('en') ? en : zh)

const kb = (bytes) => `${(bytes / 1024).toFixed(1)} KB`

export function micView(s, caps) {
  const w = words(caps?.locale)
  const children = [
    {
      type: 'row',
      children: [
        { type: 'metric', label: w.frames, value: String(s.frames), tone: s.active ? 'good' : undefined },
        { type: 'metric', label: w.rate, value: `${s.fps} /s`, tone: s.active ? 'good' : undefined },
      ],
    },
    {
      type: 'row',
      children: [
        { type: 'metric', label: w.lastSeq, value: String(s.lastSeq) },
        { type: 'metric', label: w.lastBytes, value: `${s.lastBytes} B` },
      ],
    },
    { type: 'text', text: `${s.active ? w.running : w.idle} · ${w.total} ${kb(s.bytes)}` },
  ]
  if (s.error) children.push({ type: 'callout', tone: 'bad', text: w.error + s.error })
  else if (!s.active && s.terminal) children.push({ type: 'callout', tone: 'info', text: w.stopped })
  else children.push({ type: 'callout', tone: 'info', text: w.hint })
  children.push({
    type: 'actions',
    items: s.active
      ? [{ label: w.stop, icon: 'stop', action: 'stop', tone: 'bad' }, { label: w.reset, icon: 'refresh', action: 'reset' }]
      : [{ label: w.start, icon: 'mic', action: 'start' }, { label: w.reset, icon: 'refresh', action: 'reset' }],
  })
  return { v: 2, accent: 'app', type: 'stack', style: { gap: 'M' }, children }
}
