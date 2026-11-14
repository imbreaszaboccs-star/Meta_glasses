import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/focus_status.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';
import '../../widgets/glasses_status.dart';
import '../diagnostics/diagnostics_screen.dart';
import '../onboarding/glasses_setup.dart';
import '../onboarding/privacy_text.dart';
import 'data_collection_screen.dart';
import 'glasses_menu_section.dart';
import 'integrations_section.dart';

const appVersion = '1.0.0';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  int _versionTaps = 0;

  void _push(Widget page) => Navigator.of(context).push(MaterialPageRoute(builder: (_) => page));

  Future<void> _unlink() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Unlink the glasses?'),
        content: const Text('FocusLens stops talking to your glasses until you link it again.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Unlink')),
        ],
      ),
    );
    if (ok == true && mounted) await context.app.focus.unregister();
  }

  Future<void> _deleteAll() async {
    final ok = await showDialog<bool>(context: context, builder: (_) => const _DeleteAllDialog());
    if (ok != true || !mounted) return;
    final app = context.app;
    await app.focus.deleteAllData();
    await app.settings.resetAll();
    await app.cards.reload();
  }

  void _tapVersion() {
    final s = context.app.settings;
    if (s.diagnosticsUnlocked) return;
    if (++_versionTaps >= 5) {
      s.setDiagnosticsUnlocked(true);
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Diagnostics unlocked')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = context.app;
    final settings = app.settings;
    final text = Theme.of(context).textTheme;
    return SafeArea(
      child: ListenableBuilder(
        listenable: Listenable.merge([settings, app.focus.status]),
        builder: (context, _) {
          final FocusStatus s = app.focus.status.value;
          final g = glassesLine(s);
          return ListView(
            padding: const EdgeInsets.only(bottom: Gap.xl),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.l, 0),
                child: Text('Settings', style: text.titleLarge),
              ),

              const SectionLabel('Glasses'),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: Gap.m),
                child: StatusRow(
                  label: 'Status',
                  value: g.value,
                  problem: g.problem,
                  fixLabel: g.fixLabel,
                  onFix: g.fix == null ? null : () => g.fix!(context),
                ),
              ),
              ListTile(
                title: const Text('Set up glasses'),
                subtitle: const Text('Meta AI, linking, camera'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => _push(const _Sub(title: 'Set up glasses', child: GlassesSetup())),
              ),
              ListTile(
                title: const Text('Try a gesture'),
                enabled: s.registered && s.glassesConnected && !s.running,
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => _push(const _Sub(title: 'Try a gesture', child: GestureTest(active: true))),
              ),
              if (s.registered) ListTile(title: const Text('Unlink from Meta AI'), onTap: _unlink),
              ListTile(
                title: const Text('Hide a step after'),
                trailing: Text(_timeoutLabel(settings.hudTimeoutSec), style: text.bodyMedium),
                onTap: () async {
                  final v = await pickOne(context, title: 'Hide a step after', current: settings.hudTimeoutSec, options: [
                    for (final v in const [10, 20, 40, 0]) (v, _timeoutLabel(v)),
                  ]);
                  if (v != null) settings.setHudTimeoutSec(v);
                },
              ),
              SwitchListTile(
                title: const Text('Read steps aloud'),
                subtitle: const Text('Also speak each step through the glasses speakers. Glasses without a display always speak.'),
                value: settings.readAloud,
                onChanged: settings.setReadAloud,
              ),

              const SectionLabel('Glasses menu'),
              const GlassesMenuSection(),

              const SectionLabel('Listening'),
              SwitchListTile(
                title: const Text('Wake word "hey lens"'),
                subtitle: const Text('Say it to ask for help without pinching. Detected on the phone, nothing is sent.'),
                value: settings.wakeWord,
                onChanged: settings.setWakeWord,
              ),
              SwitchListTile(
                title: const Text('Proactive nudges'),
                subtitle: const Text('Glasses notice when you drift.'),
                value: settings.nudges,
                onChanged: settings.setNudges,
              ),
              SwitchListTile(
                title: const Text('Sound when dozing'),
                subtitle: const Text('Also say it out loud when the glasses notice you nodding off.'),
                value: settings.dozeSound,
                onChanged: settings.setDozeSound,
              ),
              SwitchListTile(
                title: const Text('Collect motion data'),
                subtitle: const Text('Record head motion of each focus block on this phone, to train the activity model. Never uploaded.'),
                value: settings.collect,
                onChanged: settings.setCollect,
              ),
              ListTile(
                title: const Text('Data collection'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => _push(const DataCollectionScreen()),
              ),
              ListTile(
                title: const Text('Memory length'),
                subtitle: const Text('How much recent sound the phone keeps for a step.'),
                trailing: Text('${settings.bufferSec % 60 == 0 ? settings.bufferSec ~/ 60 : (settings.bufferSec / 60).toStringAsFixed(1)} min', style: text.bodyMedium),
                onTap: () async {
                  final v = await pickOne(context, title: 'Memory length', current: settings.bufferSec, options: const [
                    (60, '1 minute'),
                    (120, '2 minutes'),
                    (150, '2.5 minutes'),
                  ]);
                  if (v != null) settings.setBufferSec(v);
                },
              ),
              ListTile(
                title: const Text('Sound kept on cards'),
                trailing: Text(_keepLabel(settings.keepAudio), style: text.bodyMedium),
                onTap: () async {
                  final v = await pickOne(context, title: 'Sound kept on cards', current: settings.keepAudio, options: [
                    for (final k in const ['none', 'last60', 'all']) (k, _keepLabel(k)),
                  ]);
                  if (v != null) settings.setKeepAudio(v);
                },
              ),

              const SectionLabel('Steps'),
              ListTile(
                title: const Text('Language of the steps'),
                subtitle: const Text('Match the language of your lectures and notes.'),
                trailing: Text(_languageName(settings.language), style: text.bodyMedium),
                onTap: () async {
                  final v = await pickOne(context, title: 'Language of the steps', current: settings.language, options: hintLanguages);
                  if (v != null) settings.setLanguage(v);
                },
              ),

              const SectionLabel('Integrations'),
              const IntegrationsSection(),

              const SectionLabel('Privacy'),
              ListTile(
                title: const Text('Delete cards after'),
                trailing: Text(_retentionLabel(settings.retentionDays), style: text.bodyMedium),
                onTap: () async {
                  final v = await pickOne(context, title: 'Delete cards after', current: settings.retentionDays, options: [
                    for (final d in const [7, 30, 0]) (d, _retentionLabel(d)),
                  ]);
                  if (v != null) settings.setRetentionDays(v);
                },
              ),
              ListTile(
                title: const Text('Sound and privacy'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => _push(const PrivacyScreen()),
              ),
              ListTile(
                title: const Text('Forget my mistake profile'),
                subtitle: const Text('The recurring mistakes the AI remembers to watch for.'),
                onTap: () async {
                  await context.app.focus.forgetMistakes();
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Mistake profile cleared')));
                  }
                },
              ),
              ListTile(
                title: Text('Delete all data', style: TextStyle(color: Tokens.of(context).brick)),
                subtitle: const Text('Cards, recordings, photos and settings on this phone.'),
                onTap: _deleteAll,
              ),

              const SectionLabel('About'),
              ListTile(title: const Text('Version'), trailing: Text(appVersion, style: text.bodyMedium), onTap: _tapVersion),
              ListTile(
                title: const Text('Run setup again'),
                onTap: () => settings.setOnboarded(false),
              ),
              if (settings.diagnosticsUnlocked)
                ListTile(
                  title: const Text('Diagnostics'),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => _push(const DiagnosticsScreen()),
                ),
            ],
          );
        },
      ),
    );
  }

  static String _timeoutLabel(int s) => s == 0 ? 'Never' : '$s s';

  static String _keepLabel(String k) => switch (k) {
        'none' => 'None',
        'all' => 'Everything sent',
        _ => 'Last 60 seconds',
      };

  static String _retentionLabel(int d) => d == 0 ? 'Never' : '$d days';

  static String _languageName(String code) {
    for (final (c, n) in hintLanguages) {
      if (c == code) return n;
    }
    return code;
  }
}

class _Sub extends StatelessWidget {
  const _Sub({required this.title, required this.child});
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(title)),
        body: SafeArea(child: ListView(padding: const EdgeInsets.all(Gap.l), children: [child])),
      );
}

/// Typed confirmation: deleting everything should take a deliberate moment.
class _DeleteAllDialog extends StatefulWidget {
  const _DeleteAllDialog();

  @override
  State<_DeleteAllDialog> createState() => _DeleteAllDialogState();
}

class _DeleteAllDialogState extends State<_DeleteAllDialog> {
  final _c = TextEditingController();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ok = _c.text.trim().toUpperCase() == 'DELETE';
    return AlertDialog(
      title: const Text('Delete all data?'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('This removes every card, photo and recording on this phone, ends a running focus block and resets the settings. It can\'t be undone.'),
        const SizedBox(height: Gap.m),
        const Text('Type DELETE to confirm.'),
        const SizedBox(height: Gap.s),
        TextField(controller: _c, autofocus: true, onChanged: (_) => setState(() {})),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        TextButton(
          onPressed: ok ? () => Navigator.pop(context, true) : null,
          style: TextButton.styleFrom(foregroundColor: Tokens.of(context).brick),
          child: const Text('Delete everything'),
        ),
      ],
    );
  }
}
