import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart' show mapEquals;
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'companion/companion_initiative_owner.dart' show companionInitiativeProvider;
import 'companion/companion_always_on.dart' show companionAlwaysOnOwnerProvider;
import 'companion/companion_residency.dart' show companionResidencyProvider;
import 'companion/companion_state.dart' show companionPhoneSessionsBridgeProvider;
import 'pets/pet_overlay_bridge.dart' show petOverlayBridgeProvider;
import 'pets/pet_sheets.dart';
import 'l10n/l10n.dart';
import 'performance/performance_state.dart';
import 'performance/refresh_rate.dart';
import 'remote/remote_background.dart';
import 'speech/wake/wake_word.dart';
import 'ui/chat/voice/voice_bridge.dart';
import 'state/app_info.dart';
import 'state/appearance_state.dart';
import 'state/locale_state.dart';
import 'state/notify_state.dart' show notifyChannelProvider;
import 'state/star_prompt.dart' show starPromptGateProvider, starPromptProvider;
import 'state/streaming_asr_state.dart';
import 'state/streaming_pcm_state.dart';
import 'state/tile_style_state.dart';
import 'ui/chat/dsh_ui/dsh_ui_model.dart' show DshUiAppAccent;
import 'ui/chat/run_info.dart' show runActivityProvider;
import 'ui/common/keyboard_motion.dart';
import 'ui/common/side_insets.dart';
import 'ui/launch/launch_overlay.dart';
import 'ui/shell/root_gate.dart';
import 'ui/theme/theme.dart';
import 'ui_config/ui_config_state.dart';
import 'usage/usage_stats.dart';
import 'voice/live_words.dart';
import 'voice/live_words_model.dart';
import 'voice/streaming_pcm.dart';

class DshApp extends ConsumerStatefulWidget {
  const DshApp({super.key, this.home});

  /// Replaces the root gate (widget tests).
  final Widget? home;

  @override
  ConsumerState<DshApp> createState() => _DshAppState();
}

class _DshAppState extends ConsumerState<DshApp> with WidgetsBindingObserver {
  /// Themes of the palette last built: rebuilt only when the palette or the
  /// language changes.
  AppAccent? _accent;
  bool? _english;
  bool? _glassBlur;
  bool? _instantPages;
  bool? _fluid;
  ThemePack? _themePack;
  Map<String, String> _loadedFonts = const {};
  late ThemeData _light;
  late ThemeData _dark;

  /// The language last shown; a change after the first frame rebuilds the
  /// whole tree (text read through `appL10n` has no dependency to notify).
  Locale? _shown;

  /// The stored palette arrives a moment after launch; that first switch
  /// lands without a cross-fade, later ones fade (切换配色).
  bool _settled = false;

  /// Reduced motion as of the last build. Switching it changes the theme's page transitions,
  /// and that lands at once rather than half a cross-fade later (减少动画).
  bool? _reducedBefore;

  /// MaterialApp builds Theme for AnimationStyle.noAnimation and AnimatedTheme for anything else,
  /// so the cross-fade coming on or off replaces everything above the builder. The navigator has a
  /// global key to survive that; what the builder holds needs one too, or the launch screen and
  /// the theme reveal start again from nothing, and a launch screen waiting for its motion to end
  /// never leaves.
  final _layers = GlobalKey(debugLabel: 'app layers');

  @override
  void initState() {
    super.initState();
    // Start loading the stored tile style now, so 全部功能 opens in it rather
    // than flashing the default first.
    ref.read(tileStyleProvider);
    // Anonymous usage statistics live as long as the app, in either 界面
    // (release builds: today is noted, and earlier days go out).
    ref.read(usageStatsProvider);
    // 公测版: the star prompt counts time in front from launch, in either 界面.
    if (ref.read(starPromptGateProvider)) ref.read(starPromptProvider);
    // 伙伴小屋: her invitation ledger is read now, so it is known before the room opens.
    ref.read(companionInitiativeProvider);
    // 刷新率: some systems cap third-party apps at 60 Hz and then show their per-app
    // switch as an in-app setting; the app answers frames to run at the fastest rate
    // while it is actually drawing (see RefreshRateService).
    RefreshRateService.instance.start();
    WidgetsBinding.instance.addObserver(this);
    // 小黄鱼语音唤醒：App 在后台时听唤醒词，喊中就拉回前台开始通话。
    // 失败（没有原生通道）时静默跳过。
    unawaited(WakeWordService.start());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    // 前台：听写和通话要麦克风，唤醒先让开；后台：用本地语音模型听唤醒词。
    if (state == AppLifecycleState.resumed) {
      unawaited(_stopWakeListening());
      if (_wakePending) {
        _wakePending = false;
        // 等这一帧把界面立起来，再把唤醒交给最前面的对话（它会开语音通话）。
        WidgetsBinding.instance.addPostFrameCallback((_) => WakeWordService.fireHandlers(WakeWordService.defaultKeyword));
      }
    } else if (state == AppLifecycleState.paused) {
      unawaited(_beginWakeListening());
    }
  }

  /// 退到后台：开一轮本地识别，听到「小黄鱼」就把 App 拉回来。
  Future<void> _beginWakeListening() async {
    if (_wakeWords != null) return;
    try {
      // 本地识别要的是录音机的 PCM 流：不先开录音，识别永远收不到声音。
      final bridge = ref.read(voiceBridgeProvider);
      await bridge.startRecorder(maxSeconds: 600);
      _wakeBridge = bridge;
      _wakeEvents = bridge.events.listen(_onWakeVoiceEvent);
      final chosen = await liveWordsModel(ref);
      debugPrint('wake: model=${chosen == null ? 'none' : (chosen.model.isSystem ? 'system' : 'local')}');
      if (chosen == null) return;
      final live = LiveWords(
        ref.read(streamingPcmCaptureProvider),
        ref.read(streamingAsrProvider),
        chosen.model,
        StreamingPcmSource.dictation,
        _onWakeWords,
        trial: chosen.trial,
        onFailure: (Object _) {},
      );
      await live.start();
      _wakeWords = live;
      unawaited(WakeWordService.resume());
      debugPrint('wake: listening');
    } catch (error) {
      // 模型没就绪、麦克风被别的应用占着：这一轮就不听，回到前台再算。
      debugPrint('wake: start failed $error');
      unawaited(_stopWakeListening());
    }
  }

  Future<void> _stopWakeListening() async {
    final live = _wakeWords;
    _wakeWords = null;
    unawaited(_wakeEvents?.cancel());
    _wakeEvents = null;
    final bridge = _wakeBridge;
    _wakeBridge = null;
    if (bridge != null) unawaited(bridge.cancel().catchError((Object _) {}));
    unawaited(WakeWordService.pause());
    if (live == null) return;
    try {
      await live.stop();
    } catch (_) {}
  }

  /// 引擎的录音一轮最多 600 秒；到点后重开一轮，后台继续听。
  void _onWakeVoiceEvent(VoiceEvent event) {
    if (_wakeWords == null) return;
    if (event is VoiceLimit || event is VoiceEnd) {
      final bridge = _wakeBridge;
      if (bridge == null) return;
      unawaited(bridge.startRecorder(maxSeconds: 600).catchError((Object _) {}));
    }
  }

  void _onWakeWords(String words) {
    if (words.isNotEmpty) debugPrint('wake: heard "$words"');
    if (!words.contains(WakeWordService.defaultKeyword)) return;
    if (_wakePending) return;
    _wakePending = true;
    unawaited(_stopWakeListening());
    unawaited(WakeWordService.bringToFront());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 后台听唤醒词时那一轮本地识别；喊中或回到前台就收掉。
  LiveWords? _wakeWords;
  bool _wakePending = false;
  VoiceBridge? _wakeBridge;
  StreamSubscription<VoiceEvent>? _wakeEvents;

  /// 跟随系统: the system language changed while the app was open.
  @override
  void didChangeLocales(List<Locale>? locales) {
    if (ref.read(appLanguageProvider) != AppLanguage.system) return;
    setState(() {});
    // Android re-registers the notification channels under the new language.
    unawaited(ref.read(notifyChannelProvider).status());
  }

  /// Decoded pet sheets nobody pins go, as Flutter's own image cache does.
  @override
  void didHaveMemoryPressure() => PetSheets.instance.trim();

  /// The system's remove-animations setting changed while the app was open: page
  /// transitions are chosen in [build], so they would keep the old answer.
  @override
  void didChangeAccessibilityFeatures() => setState(() {});

  void _themesFor(
    AppAccent accent,
    bool english,
    bool glassBlur,
    bool instantPages,
    bool fluid,
    ThemePack? themePack,
    Map<String, String> loadedFonts,
  ) {
    if (accent == _accent &&
        english == _english &&
        glassBlur == _glassBlur &&
        instantPages == _instantPages &&
        fluid == _fluid &&
        themePack == _themePack &&
        mapEquals(loadedFonts, _loadedFonts)) {
      return;
    }
    _accent = accent;
    _english = english;
    _glassBlur = glassBlur;
    _instantPages = instantPages;
    _fluid = fluid;
    _themePack = themePack;
    _loadedFonts = loadedFonts;
    // Plugin views that ask for `accent: "app"` follow the palette.
    DshUiAppAccent.set(accent.seed.toARGB32());
    // English UI still shows Chinese content: keep its glyphs Chinese.
    final glyphs = english ? AppLocale.zhCN : null;
    _light = buildLightTheme(
      accent: accent,
      glyphLocale: glyphs,
      glassBlur: glassBlur,
      instantPages: instantPages,
      fluid: fluid,
      themePack: themePack,
      loadedFonts: loadedFonts,
    );
    _dark = buildDarkTheme(
      accent: accent,
      glyphLocale: glyphs,
      glassBlur: glassBlur,
      instantPages: instantPages,
      fluid: fluid,
      themePack: themePack,
      loadedFonts: loadedFonts,
    );
  }

  Locale _resolveLocale() {
    final locale = AppLocale.resolve(
      ref.watch(appLanguageProvider).locale,
      WidgetsBinding.instance.platformDispatcher.locales,
    );
    if (locale != _shown) {
      final first = _shown == null;
      _shown = locale;
      AppLocale.use(locale);
      if (!first) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          // Providers refresh before this build switches appL10n: redo the
          // activity titles in the new language.
          ref.invalidate(runActivityProvider);
          _rebuildAll();
        });
      }
    }
    return locale;
  }

  void _rebuildAll() {
    void mark(Element e) {
      e.markNeedsBuild();
      e.visitChildren(mark);
    }

    if (mounted) (context as Element).visitChildren(mark);
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(companionAlwaysOnOwnerProvider);
    if (widget.home == null) ref.watch(remoteBackgroundProvider);
    // Only Android's native phone control knows her sessions.
    if (Platform.isAndroid) ref.watch(companionPhoneSessionsBridgeProvider);
    if (ref.watch(companionResidencyProvider).enabled) ref.watch(petOverlayBridgeProvider);
    // 刷新率设置改动时告诉原生;setMode 对同一个值不做任何事。
    unawaited(RefreshRateService.instance.setMode(ref.watch(performancePrefsProvider.select((p) => p.refreshRate))));
    final themeMode = ref.watch(uiThemeModeProvider.select((s) => s.value));
    final appearance = ref.watch(appearanceProvider);
    final waiting =
        !appearance.loaded ||
        (appearance.source == AccentSource.wallpaper && ref.watch(wallpaperAccentProvider).isLoading);
    final locale = _resolveLocale();
    final performance = ref.watch(
      performanceDefaultsProvider.select(
        (p) => (blur: p.blur, reduceMotion: p.reduceMotion, fluidMotion: p.fluidMotion),
      ),
    );
    final reduced =
        performance.reduceMotion || WidgetsBinding.instance.platformDispatcher.accessibilityFeatures.disableAnimations;
    final fluid = performance.fluidMotion && !reduced;
    FrameClock.instance.enabled = fluid;
    _themesFor(
      ref.watch(accentProvider),
      locale.languageCode == 'en',
      performance.blur,
      reduced,
      fluid,
      ref.watch(activeThemePackProvider),
      ref.watch(activeThemeFontsProvider),
    );
    if (!waiting && !_settled) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _settled = true);
    }
    final reducedChanged = _reducedBefore != null && _reducedBefore != reduced;
    _reducedBefore = reduced;
    return MaterialApp(
      title: AppInfo.name,
      debugShowCheckedModeBanner: false,
      theme: _light,
      darkTheme: _dark,
      themeMode: themeMode,
      // Palette and light / dark changes cross-fade the whole app (AppMotion).
      // A reveal from the finger brings the new colours in itself.
      // Never AnimationStyle.noAnimation: MaterialApp then swaps AnimatedTheme for Theme, and
      // everything under builder (the launch screen mid-motion too) is built anew.
      themeAnimationStyle: _settled && !reduced && !reducedChanged && !ThemeRevealHost.active
          ? AnimationStyle(duration: AppMotion.standard, curve: AppMotion.std)
          : AnimationStyle(duration: Duration.zero),
      // zh_CN, not bare zh: otherwise CJK falls back to Japanese glyph shapes
      // on non-Chinese system locales. English keeps Chinese glyphs through
      // the theme's text locale (_themesFor).
      locale: locale,
      supportedLocales: AppLocale.supported,
      localizationsDelegates: const [L10n.delegate, ...GlobalMaterialLocalizations.delegates],
      // A phone on its side: the cutout's inset on both sides (side_insets). Over everything,
      // the launch screen of a cold start (启动画面).
      builder: (context, child) {
        final media = MediaQuery.of(context);
        return MediaQuery(
          key: _layers,
          data: media.copyWith(disableAnimations: media.disableAnimations || performance.reduceMotion),
          child: AppMotionScope(
            fluid: fluid,
            child: ThemeRevealHost(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  LaunchUnder(
                    child: Platform.operatingSystem == 'ohos'
                        ? EasedKeyboardInset(child: MirroredSideInsets(child: child!))
                        : MirroredSideInsets(child: child!),
                  ),
                  const LaunchLayer(),
                ],
              ),
            ),
          ),
        );
      },
      home: widget.home ?? const RootGate(),
    );
  }
}
