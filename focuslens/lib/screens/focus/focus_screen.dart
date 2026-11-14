import 'dart:async';

import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/focus_status.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';
import '../../widgets/glasses_status.dart';

/// Home. Two states: ready and running. The main action is always the biggest thing on screen.
class FocusScreen extends StatefulWidget {
  const FocusScreen({super.key, required this.onOpenCards});
  final VoidCallback onOpenCards;

  @override
  State<FocusScreen> createState() => _FocusScreenState();
}

class _FocusScreenState extends State<FocusScreen> {
  @override
  void initState() {
    super.initState();
    context.app.focus.checkServer();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ValueListenableBuilder<FocusStatus>(
        valueListenable: context.app.focus.status,
        builder: (context, s, _) => AnimatedSwitcher(
          duration: const Duration(milliseconds: 180),
          child: s.running
              ? _Running(key: const ValueKey('run'), s: s, onOpenCards: widget.onOpenCards)
              : _Ready(key: const ValueKey('ready'), s: s),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- READY

class _Ready extends StatefulWidget {
  const _Ready({super.key, required this.s});
  final FocusStatus s;

  @override
  State<_Ready> createState() => _ReadyState();
}

class _ReadyState extends State<_Ready> {
  bool _starting = false;

  Future<void> _start() async {
    setState(() => _starting = true);
    try {
      await startFocusFlow(context);
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.s;
    final app = context.app;
    final text = Theme.of(context).textTheme;
    final t = Tokens.of(context);
    final g = glassesLine(s);
    final glassesOk = s.registered && s.glassesConnected;

    return ListenableBuilder(
      listenable: Listenable.merge([app.settings, app.cards]),
      builder: (context, _) {
        final mode = app.settings.mode;
        final pending = app.cards.pendingCount;
        return ListView(
          padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.l, Gap.xl),
          children: [
            Text('FocusLens', style: text.titleLarge),
            const SizedBox(height: Gap.xl),
            Text('What are you doing?', style: text.headlineSmall),
            const SizedBox(height: Gap.m),
            ModePicker(value: mode, onChanged: app.settings.setMode),
            const SizedBox(height: Gap.s),
            Text(modeHelp[mode]!, style: text.bodyMedium!.copyWith(color: t.graphite)),
            const SizedBox(height: Gap.xl),
            FilledButton(
              onPressed: _starting ? null : _start,
              child: SizedBox(
                width: double.infinity,
                child: Center(child: Text(glassesOk ? 'Start focus' : 'Start without glasses')),
              ),
            ),
            if (!glassesOk)
              Padding(
                padding: const EdgeInsets.only(top: Gap.s),
                child: Text(
                  'Without glasses you get help from the phone mic only. Use the Help button or the notification.',
                  style: text.bodySmall,
                ),
              ),
            const SizedBox(height: Gap.xl),
            const Divider(),
            const SizedBox(height: Gap.s),
            StatusRow(
              label: 'Glasses',
              value: g.value,
              problem: g.problem,
              fixLabel: g.fixLabel,
              onFix: g.fix == null ? null : () => g.fix!(context),
            ),
            StatusRow(
              label: 'Microphone',
              value: s.micPermission ? 'ready' : 'not allowed',
              problem: !s.micPermission,
              fixLabel: 'Allow',
              onFix: s.micPermission ? null : () => app.focus.requestPermissions(),
            ),
            StatusRow(
              label: 'Server',
              value: !s.serverConfigured
                  ? 'not configured in this build'
                  : switch (s.serverOk) { true => 'reachable', false => 'not reachable', null => 'checking…' },
              problem: !s.serverConfigured || s.serverOk == false,
              fixLabel: s.serverConfigured && s.serverOk == false ? 'Check again' : null,
              onFix: () => app.focus.checkServer(),
            ),
            if (pending > 0) ...[
              const SizedBox(height: Gap.s),
              StatusRow(
                label: 'Cards',
                value: pending == 1 ? '1 waiting to send' : '$pending waiting to send',
                fixLabel: 'Send now',
                onFix: () => app.focus.retryPending(),
              ),
            ],
          ],
        );
      },
    );
  }
}

// ---------------------------------------------------------------- RUNNING

class _Running extends StatefulWidget {
  const _Running({super.key, required this.s, required this.onOpenCards});
  final FocusStatus s;
  final VoidCallback onOpenCards;

  @override
  State<_Running> createState() => _RunningState();
}

class _RunningState extends State<_Running> {
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  Future<void> _end() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('End this focus block?'),
        content: const Text('The sound in memory is dropped. Your cards stay.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Keep going')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('End')),
        ],
      ),
    );
    if (ok == true && mounted) await context.app.focus.stopFocus();
  }

  Future<void> _changeMode(String current) async {
    final m = await pickOne(context,
        title: 'Mode', current: current, options: [for (final e in modeNames.entries) (e.key, e.value)]);
    if (m != null && mounted) {
      await context.app.settings.setMode(m); // pushes the new mode to the engine
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.s;
    final app = context.app;
    final text = Theme.of(context).textTheme;
    final t = Tokens.of(context);
    final since = s.startedAt == null ? Duration.zero : DateTime.now().difference(s.startedAt!);
    final minutes = (s.bufferSecConfig / 60).ceil();
    final step = s.lastText;

    return ListView(
      padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.l, Gap.xl),
      children: [
        // header: mode + timer
        Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
          Expanded(
            child: InkWell(
              onTap: () => _changeMode(s.mode),
              borderRadius: BorderRadius.circular(6),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: Gap.s),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Text(modeNames[s.mode] ?? s.mode, style: text.headlineSmall),
                  const SizedBox(width: Gap.xs),
                  Icon(Icons.expand_more_rounded, color: t.graphite, semanticLabel: 'Change mode'),
                ]),
              ),
            ),
          ),
          Text(elapsed(since), style: TextStyle(fontFamily: kMono, fontSize: 18, color: t.graphite)),
        ]),
        const SizedBox(height: Gap.m),

        // listening line
        Row(children: [
          ListeningDot(active: s.listening && !s.micPaused),
          const SizedBox(width: Gap.s + 2),
          Expanded(
            child: Text(
              s.micPaused
                  ? 'Mic paused. Nothing is being kept.'
                  : s.listening
                      ? 'Listening · last $minutes min'
                      : s.micError == 'permission'
                          ? 'Microphone not allowed'
                          : 'Microphone not running',
              style: text.bodyMedium!.copyWith(color: s.listening || s.micPaused ? t.ink : t.brick),
            ),
          ),
          TextButton(
            onPressed: s.micPaused ? app.focus.resumeMic : app.focus.pauseMic,
            child: Text(s.micPaused ? 'Resume' : 'Pause'),
          ),
        ]),
        // glasses line
        Row(children: [
          Icon(s.phoneOnly ? Icons.phone_android_rounded : Icons.visibility_outlined, size: 18, color: t.graphite),
          const SizedBox(width: Gap.s),
          Expanded(
            child: Text(
              s.phoneOnly
                  ? (s.glassesConnected ? 'Phone only · glasses connecting…' : 'Phone only · glasses not connected')
                  : s.glassesReady
                      ? (s.hasDisplay ? 'Glasses ready · pinch for help' : 'Glasses ready · steps are spoken')
                      : 'Glasses: ${s.glassesPhase.toLowerCase()}',
              style: text.bodySmall,
            ),
          ),
          if (s.phoneOnly && s.registered)
            TextButton(onPressed: app.focus.reconnect, child: const Text('Reconnect')),
        ]),
        const SizedBox(height: Gap.l),
        const Divider(),
        const SizedBox(height: Gap.l),

        // the step
        if (s.thinking) ...[
          Text('Thinking…', style: text.titleSmall),
          const SizedBox(height: Gap.m),
          const LinearProgressIndicator(minHeight: 3),
          const SizedBox(height: Gap.xl),
        ] else if (step != null) ...[
          Text('Last step', style: text.titleSmall),
          const SizedBox(height: Gap.s),
          Semantics(liveRegion: true, child: Text(step, style: text.headlineMedium)),
          if (s.hudMessage != null) ...[
            const SizedBox(height: Gap.s),
            Text(s.hudMessage!, style: text.bodySmall),
          ],
          const SizedBox(height: Gap.l),
        ] else ...[
          Text(
            s.hudMessage ??
                (s.phoneOnly
                    ? 'When you get stuck, tap Help. The step shows up here.'
                    : 'When you get stuck, pinch your fingers. The step shows up on the glasses and here.'),
            style: text.bodyLarge!.copyWith(color: t.graphite),
          ),
          const SizedBox(height: Gap.xl),
        ],

        FilledButton(
          onPressed: s.thinking ? null : app.focus.help,
          child: const SizedBox(width: double.infinity, child: Center(child: Text('Help'))),
        ),
        if (step != null && !s.thinking) ...[
          const SizedBox(height: Gap.s),
          OutlinedButton(
            onPressed: app.focus.next,
            child: const SizedBox(width: double.infinity, child: Center(child: Text('Next step'))),
          ),
        ],
        const SizedBox(height: Gap.l),
        if (s.hintCount > 0)
          TextButton(
            onPressed: widget.onOpenCards,
            child: Text(s.hintCount == 1 ? '1 step in this block →' : '${s.hintCount} steps in this block →'),
          ),
        const SizedBox(height: Gap.l),
        Center(
          child: TextButton(
            style: TextButton.styleFrom(foregroundColor: t.ink),
            onPressed: _end,
            child: const Text('End focus'),
          ),
        ),
      ],
    );
  }
}
