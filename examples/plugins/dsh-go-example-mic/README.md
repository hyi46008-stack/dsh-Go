# 麦克风测试（dsh-go-example-mic）

最小的 `mic` 能力样例（协议 v1.4）：点「开始采集」拿插件授权和系统录音权限，之后 App 每 20 ms
交一帧 16 kHz / 单声道 / PCM16 音频。本插件**只统计**帧数、序号、字节数和估算帧率；
不保存、不上传、不播放任何音频。

## 它做什么

- 面板 `mic`：状态、已收帧数、帧率、最近序号、最近帧大小、累计字节
- 「开始采集」→ `{ type: 'mic', op: 'start', instance: 'test' }`
- 「停止」→ `{ type: 'mic', op: 'stop', instance: 'test' }`
- 「清零」→ 只清计数
- `onMic` 收帧：计数后立刻丢弃字节
- `onCapability` 收 `mic.stopped` / `mic.error`：显示终态和错误码（`declined`、`permission`、
  `busy`、`capture`、`indicator`……）

## 怎么用

1. 把本目录装进引擎的 web profile（示例插件按 link 安装）：
   `dsh plugin --profile web add ./examples/plugins/dsh-go-example-mic`
2. 重载插件：插件列表里关一次再开一次。
3. 打开 App：「全部功能 → 插件 → 麦克风测试」，点「开始采集」。
4. 第一次会依次问：允许插件听你开口 → 系统录音权限；同意后顶部出现「麦克风测试」在听。
5. 正常时帧数约 50/s 增长，最近序号连续，最近帧 640 B。
6. 点「停止」或指示条上的停止：帧数立刻停住。

## 不做什么

不保存录音、不写文件、不上传、不播放；不读其他插件的音频（桥接按租约隔离）；不做唤醒词、
不做后台常驻、不做语音识别。

## 测试

```bash
cd examples/plugins/dsh-go-example-mic && node --test test/*.test.js
node ../../../sdk/plugin-kit/bin/check.mjs . --load
```
