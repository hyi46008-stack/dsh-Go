import 'dart:async';

import 'package:flutter/services.dart';

/// 小黄鱼语音唤醒：Dart 侧的开关、事件与「喊中之后拉回前台」。
///
/// 听和识别用的是应用自己的录音通道 + 本地语音模型（LiveWords），不走系统语音识别服务 ——
/// 实测这台手机上系统识别服务在后台直接拒绝给本应用开麦（"caller doesn't have permission"）。
/// 原生侧（voice/WakeWordService.kt）只负责让进程活着并挂一条通知。
class WakeWordService {
  WakeWordService._();

  static const MethodChannel _methods = MethodChannel('dsh/wake');
  static const EventChannel _events = EventChannel('dsh/wake/events');

  static const String defaultKeyword = '小黄鱼';

  static final List<bool Function(String heard)> _handlers = [];
  static StreamSubscription<dynamic>? _subscription;
  static bool _started = false;

  /// 是否已经让原生把前台服务挂起来。
  static bool get started => _started;

  /// 注册一个「唤醒之后干什么」的处理者；返回 true 表示这次唤醒有人接。
  static void addHandler(bool Function(String heard) handler) => _handlers.add(handler);

  static void removeHandler(bool Function(String heard) handler) => _handlers.remove(handler);

  /// 把唤醒事件交给当前打开的界面（只在 App 已经回到前台时调用）。
  static void fireHandlers(String heard) {
    for (final handler in [..._handlers]) {
      try {
        if (handler(heard)) break;
      } catch (_) {
        // 一个界面的处理出了错，不该影响别的界面接这次唤醒。
      }
    }
  }

  /// 挂起前台服务（不采集，只是让进程在后台活着并有通知）。
  static Future<void> start() async {
    if (_started) return;
    try {
      await _listen();
      await _ensureMicrophone();
      await _methods.invokeMethod<void>('start');
      _started = true;
    } catch (_) {
      // 没有原生通道（电脑上的 DSH、旧版 App）：静默跳过，面板会说明。
    }
  }

  /// App 在前台：把麦克风让给听写与通话。
  static Future<void> pause() async {
    if (!_started) return;
    try {
      await _methods.invokeMethod<void>('idle');
    } catch (_) {}
  }

  /// App 退到后台：通知上写着在听。
  static Future<void> resume() async {
    if (!_started) return;
    try {
      await _methods.invokeMethod<void>('listening');
    } catch (_) {}
  }

  /// 用户关掉唤醒：彻底停止。
  static Future<void> stop() async {
    if (!_started) return;
    _started = false;
    try {
      await _methods.invokeMethod<void>('stop');
    } catch (_) {}
  }

  /// 喊中之后把 App 拉回前台（原生侧 startActivity，targetSdk 28 允许后台启动）。
  static Future<void> bringToFront() async {
    try {
      await _methods.invokeMethod<void>('bringToFront');
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
    _subscription = _events.receiveBroadcastStream().listen((_) {}, onError: (Object _) {});
  }
}
