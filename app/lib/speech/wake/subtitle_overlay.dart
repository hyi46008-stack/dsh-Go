import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 「小黄鱼」后台免提时的字幕悬浮条（原生悬浮窗，见 voice/SubtitleOverlay.kt）。
///
/// 没有悬浮窗权限时原生侧会静默失败，这里也不抛错：听不到字不该把唤醒流程搞挂。
class WakeSubtitles {
  WakeSubtitles._();

  static const MethodChannel _channel = MethodChannel('dsh/subtitle');
  static String _last = '';

  static Future<bool> get allowed async {
    try {
      return await _channel.invokeMethod<bool>('allowed') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> show(String text) async {
    _last = text;
    try {
      await _channel.invokeMethod<void>('show', {'text': text});
    } catch (error) {
      debugPrint('subtitle: show failed $error');
    }
  }

  static Future<void> update(String text) async {
    if (text == _last) return;
    _last = text;
    try {
      await _channel.invokeMethod<void>('update', {'text': text});
    } catch (_) {}
  }

  static Future<void> hide() async {
    _last = '';
    try {
      await _channel.invokeMethod<void>('hide');
    } catch (error) {
      debugPrint('subtitle: hide failed $error');
    }
  }
}
