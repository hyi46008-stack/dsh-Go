import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 小黄鱼的两种可选行为：唤醒后留在后台说话、以及字幕悬浮条。
class XiaohuangyuSettings {
  const XiaohuangyuSettings({this.backgroundTalk = true, this.subtitles = true});

  /// 唤醒后不打开通话页，在后台继续听（字幕显示听到的话）。
  final bool backgroundTalk;

  /// 把听到的话显示在屏幕底部的发光字幕条上。
  final bool subtitles;

  XiaohuangyuSettings copyWith({bool? backgroundTalk, bool? subtitles}) => XiaohuangyuSettings(
    backgroundTalk: backgroundTalk ?? this.backgroundTalk,
    subtitles: subtitles ?? this.subtitles,
  );
}

class XiaohuangyuController extends Notifier<XiaohuangyuSettings> {
  static const _bgKey = 'xiaohuangyu.backgroundTalk';
  static const _subTitleKey = 'xiaohuangyu.subtitles';

  @override
  XiaohuangyuSettings build() {
    unawaited(_load());
    return const XiaohuangyuSettings();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    state = XiaohuangyuSettings(
      backgroundTalk: prefs.getBool(_bgKey) ?? true,
      subtitles: prefs.getBool(_subTitleKey) ?? true,
    );
  }

  Future<void> setBackgroundTalk(bool on) async {
    state = state.copyWith(backgroundTalk: on);
    (await SharedPreferences.getInstance()).setBool(_bgKey, on);
  }

  Future<void> setSubtitles(bool on) async {
    state = state.copyWith(subtitles: on);
    (await SharedPreferences.getInstance()).setBool(_subTitleKey, on);
  }
}

final xiaohuangyuProvider = NotifierProvider<XiaohuangyuController, XiaohuangyuSettings>(XiaohuangyuController.new);
