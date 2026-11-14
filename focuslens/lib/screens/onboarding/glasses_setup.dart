import 'dart:async';

import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/focus_status.dart';
import '../../theme/tokens.dart';

/// Connect the glasses: Meta AI + Developer Mode → link → connected → camera. Live status per step.
class GlassesSetup extends StatelessWidget {
  const GlassesSetup({super.key});

  @override
  Widget build(BuildContext context) {
    final app = context.app;
    return ValueListenableBuilder<FocusStatus>(
      valueListenable: app.focus.status,
      builder: (context, s, _) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _Step(
          n: 1,
          title: 'Meta AI app with Developer Mode',
          body: 'Pair your glasses in Meta AI. Then: Settings → App info → tap the app version 5 times → turn on Developer Mode.',
          done: s.glassesKnown,
          action: s.glassesKnown ? null : ('Open Meta AI', app.focus.openMetaAi),
        ),
        _Step(
          n: 2,
          title: 'Link FocusLens',
          body: !s.bluetoothPermission
              ? 'Allow Bluetooth first.'
              : s.registering
                  ? 'Confirm in the Meta AI app, then come back here.'
                  : 'Meta AI asks you to allow FocusLens.',
          done: s.registered,
          action: s.registered
              ? null
              : !s.bluetoothPermission
                  ? ('Allow Bluetooth', app.focus.requestPermissions)
                  : ('Link', app.focus.register),
        ),
        _Step(
          n: 3,
          title: 'Glasses connected',
          body: s.glassesConnected
              ? [s.glassesName ?? 'Glasses', if (!s.displayCapable) 'no display: steps will be spoken'].join(' · ')
              : 'Put the glasses on and keep Bluetooth on.',
          done: s.glassesConnected,
          action: s.updateRequired || s.compatibilityIssue ? ('Update glasses', app.focus.openGlassesUpdate) : null,
        ),
        _Step(
          n: 4,
          title: 'Glasses camera',
          body: 'So the glasses can take one photo when you ask for help.',
          done: s.cameraPermission == 'granted',
          action: s.cameraPermission == 'granted' || !s.glassesConnected || !s.registered
              ? null
              : ('Allow camera', app.focus.requestGlassesCamera),
        ),
      ]),
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({required this.n, required this.title, required this.body, required this.done, this.action});
  final int n;
  final String title, body;
  final bool done;
  final (String, Future<Object?> Function())? action;

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: Gap.l),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: 32,
          child: done
              ? Icon(Icons.check_rounded, color: t.board, semanticLabel: 'Done')
              : Text('$n', style: TextStyle(fontFamily: kMono, fontSize: 18, color: t.graphite, height: 1.3)),
        ),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: text.titleMedium),
            const SizedBox(height: 2),
            Text(body, style: text.bodyMedium!.copyWith(color: t.graphite)),
            if (action != null) ...[
              const SizedBox(height: Gap.s),
              OutlinedButton(onPressed: () => action!.$2(), child: Text(action!.$1)),
            ],
          ]),
        ),
      ]),
    );
  }
}

/// "Try a pinch": opens a light glasses session (no camera, no mic) and reacts to the first gesture.
class GestureTest extends StatefulWidget {
  const GestureTest({super.key, required this.active});

  /// Only hold a glasses session while this is on screen.
  final bool active;

  @override
  State<GestureTest> createState() => _GestureTestState();
}

class _GestureTestState extends State<GestureTest> {
  StreamSubscription? _sub;
  final _seen = <String>[];
  bool _started = false;
  late final _focus = context.app.focus;

  static const _names = {
    'SELECT': 'Pinch',
    'LEFT': 'Swipe left',
    'RIGHT': 'Swipe right',
    'UP': 'Swipe up',
    'DOWN': 'Swipe down',
    'BACK': 'Back',
    'CAPTURE': 'Capture button',
  };

  @override
  void initState() {
    super.initState();
    _sub = _focus.events.where((e) => e['type'] == 'gesture').listen((e) {
      setState(() {
        _seen.insert(0, _names[e['name']] ?? '${e['name']}');
        if (_seen.length > 5) _seen.removeLast();
      });
    });
    _focus.status.addListener(_sync);
    WidgetsBinding.instance.addPostFrameCallback((_) => _sync());
  }

  @override
  void didUpdateWidget(GestureTest oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync();
  }

  void _sync() {
    if (!mounted) return;
    final s = _focus.status.value;
    final canRun = widget.active && s.registered && s.glassesConnected && !s.running;
    if (canRun && !_started) {
      _started = true;
      _focus.startGestureTest();
    } else if (!widget.active && _started) {
      _started = false;
      _focus.stopGestureTest();
    }
  }

  @override
  void dispose() {
    _focus.status.removeListener(_sync);
    _sub?.cancel();
    if (_started) _focus.stopGestureTest();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    return ValueListenableBuilder<FocusStatus>(
      valueListenable: _focus.status,
      builder: (context, s, _) {
        if (!s.registered || !s.glassesConnected) {
          return Text(
            'Connect your glasses first. You can try this later from Settings.',
            style: text.bodyLarge!.copyWith(color: t.graphite),
          );
        }
        final worked = _seen.contains('Pinch');
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(
            s.glassesPhase == 'READY' ? 'Pinch your thumb and index finger together.' : 'Connecting to the glasses…',
            style: text.headlineSmall,
          ),
          const SizedBox(height: Gap.l),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            child: worked
                ? Semantics(
                    liveRegion: true,
                    child: Row(key: const ValueKey('ok'), children: [
                      Icon(Icons.check_rounded, color: t.board),
                      const SizedBox(width: Gap.s),
                      Text('It works. You\'ll see the same on the glasses.', style: text.titleMedium),
                    ]),
                  )
                : Text('Waiting for a pinch…', key: const ValueKey('wait'), style: text.bodyLarge!.copyWith(color: t.graphite)),
          ),
          if (_seen.isNotEmpty) ...[
            const SizedBox(height: Gap.l),
            Text('Swipe right on the band = next step. Swipe left = previous step.', style: text.bodyMedium),
            const SizedBox(height: Gap.s),
            for (final g in _seen) Text(g, style: TextStyle(fontFamily: kMono, fontSize: 14, color: t.graphite)),
          ],
        ]);
      },
    );
  }
}
