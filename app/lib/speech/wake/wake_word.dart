import 'dart:async';

import 'package:flutter/services.dart';

/// 小黄鱼语音唤醒：Dart 侧的开关与事件入口。
///
/// 原生侧（voice/WakeWordService.kt）只在 App 退到后台时听唤醒词 —— 前台时麦克风要留给
/// 输入框的听写和语音通话。喊中之后原生把 App 拉回前台，这里收到 `wake` 事件，
/// 交给当前打开的对话去开始语音通话。
///
/// 通道：方法 `dsh/wake`（start/stop/pause/resume/status），事件 `dsh/wake/events`。
class WakeWordService {
  WakeWordService._();

  static const MethodChannel _methods = MethodChannel('dsh/wake');
  static const EventChannel _events = EventChannel('dsh/wake/events');

  static const String defaultKeyword = '小黄鱼';

  static final List<bool Function(String heard)> _handlers = [];
  static StreamSubscription<dynamic>? _subscription;
  static bool _listening = false;
  static bool _started = false;

  /// 是否已经让原生开始听（不代表此刻一定在听：前台时会暂停）。
  static bool get started => _started;
  static bool get listening => _listening;

  /// 注册一个「唤醒之后干什么」的处理者；返回 true 表示这次唤醒有人接。
  /// 多个界面同时注册时按注册顺序问，第一个接住为止。
  static void addHandler(bool Function(String heard) handler) => _handlers.add(handler);

  static void removeHandler(bool Function(String heard) handler) => _handlers.remove(handler);

  /// 开始听唤醒词。App 启动时调一次即可；失败不抛给界面。
  static Future<void> start({String keyword = defaultKeyword}) async {
    if (_started) return;
    try {
      await _listen();
      await _ensureMicrophone();
      await _methods.invokeMethod<void>('start', {'keyword': keyword});
      _started = true;
    } catch (_) {
      // 没有原生通道（电脑上的 DSH、或旧版 App）：静默跳过，面板会说明。
    }
  }

  /// App 在前台：把麦克风让给听写与通话。
  static Future<void> pause() async {
    if (!_started) return;
    _listening = false;
    try {
      await _methods.invokeMethod<void>('pause');
    } catch (_) {}
  }

  /// App 退到后台：接着听。
  static Future<void> resume() async {
    if (!_started) return;
    _listening = true;
    try {
      await _methods.invokeMethod<void>('resume');
    } catch (_) {}
  }

  /// 用户关掉唤醒：彻底停止。
  static Future<void> stop() async {
    if (!_started) return;
    _started = false;
    _listening = false;
    try {
      await _methods.invokeMethod<void>('stop');
    } catch (_) {}
  }

  /// 没有麦克风权限时问一次（系统弹窗）；用户拒绝就静默跳过。
  static Future<void> _ensureMicrophone() async {
    try {
      final voice = const MethodChannel('dsh/voice');
      final state = await voice.invokeMethod<String>('permission');
      if (state == 'granted') return;
      await voice.invokeMethod<String>('requestPermission');
    } catch (_) {
      // 老版本 App 没有这个通道：交给服务自己判断。
    }
  }

  static Future<void> _listen() async {
    if (_subscription != null) return;
    _subscription = _events.receiveBroadcastStream().listen((dynamic event) {
      if (event is! Map) return;
      final type = event['type'];
      if (type == 'wake') {
        final heard = '${event['text'] ?? ''}';
        for (final handler in [..._handlers]) {
          try {
            if (handler(heard)) break;
          } catch (_) {
            // 一个界面的处理出了错，不该影响别的界面接这次唤醒。
          }
        }
      } else if (type == 'state') {
        _listening = event['running'] == true;
      }
    }, onError: (Object _) {});
  }
}
