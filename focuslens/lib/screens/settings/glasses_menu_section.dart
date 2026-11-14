import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app_scope.dart';
import '../../data/focus_repository.dart';
import '../../data/focus_status.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';

const _privacy = 'Your glasses menu shows today\'s notes and cards; they pass through the FocusLens server, not stored.';

/// Start and steer FocusLens from the glasses' app menu (a web app on the glasses, relayed by our backend),
/// plus where a block listens: phone mic or glasses mic.
class GlassesMenuSection extends StatelessWidget {
  const GlassesMenuSection({super.key});

  @override
  Widget build(BuildContext context) {
    final app = context.app;
    final text = Theme.of(context).textTheme;
    return ListenableBuilder(
      listenable: Listenable.merge([app.settings, app.focus.status]),
      builder: (context, _) {
        final FocusStatus s = app.focus.status.value;
        final settings = app.settings;
        final error = s.remoteError;
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (!s.remotePaired)
            ListTile(
              title: const Text('Add FocusLens to glasses menu'),
              subtitle: Text(error ?? 'Start a focus block and read your notes from the glasses, with the phone locked.'),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _add(context),
            )
          else ...[
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: Gap.m),
              child: StatusRow(
                label: 'Glasses menu',
                value: s.remoteConnected ? 'Connected' : (error ?? 'Connecting…'),
                problem: !s.remoteConnected && error != null,
              ),
            ),
            ListTile(
              title: const Text('Show the pairing code again'),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _add(context),
            ),
            if (!s.batteryUnrestricted)
              ListTile(
                title: const Text('Allow background activity'),
                subtitle: const Text('Keeps the connection alive while the phone sleeps.'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: app.focus.openBatterySettings,
              ),
            ListTile(
              title: Text('Remove from glasses', style: TextStyle(color: Tokens.of(context).brick)),
              onTap: () => _remove(context),
            ),
          ],
          Padding(
            padding: const EdgeInsets.fromLTRB(Gap.m, 0, Gap.m, Gap.s),
            child: Text(_privacy, style: text.bodySmall),
          ),
          ListTile(
            title: const Text('Audio source'),
            subtitle: const Text('Auto: the phone mic while the app is open, otherwise the glasses mic.'),
            trailing: Text(_sourceLabel(settings.audioSource), style: text.bodyMedium),
            onTap: () async {
              final v = await pickOne(context, title: 'Audio source', current: settings.audioSource, options: [
                for (final k in const ['auto', 'phone', 'glasses']) (k, _sourceLabel(k)),
              ]);
              if (v != null) settings.setAudioSource(v);
            },
          ),
          ListTile(
            title: const Text('Allow glasses microphone'),
            subtitle: const Text('Lets a block hear through the glasses when the phone is locked (experimental).'),
            enabled: s.registered && s.glassesConnected,
            trailing: s.glassesMicPermission == 'granted'
                ? const Icon(Icons.check_rounded)
                : TextButton(
                    onPressed: s.registered && s.glassesConnected ? app.focus.requestGlassesMic : null,
                    child: const Text('Allow'),
                  ),
          ),
        ]);
      },
    );
  }

  static String _sourceLabel(String k) => switch (k) {
        'phone' => 'Phone',
        'glasses' => 'Glasses',
        _ => 'Auto',
      };

  Future<void> _add(BuildContext context) async {
    final app = context.app;
    final messenger = ScaffoldMessenger.of(context);
    final r = await app.focus.remotePair();
    if (!r.ok) {
      messenger.showSnackBar(SnackBar(content: Text(r.error ?? 'Could not reach the server.')));
      return;
    }
    if (!context.mounted) return;
    if (!app.focus.status.value.batteryUnrestricted) {
      final allow = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Keep it connected'),
          content: const Text('Android may pause FocusLens while the phone sleeps. Allow background activity so the glasses menu keeps working.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Not now')),
            TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Allow')),
          ],
        ),
      );
      if (allow == true) await app.focus.openBatterySettings();
      if (!context.mounted) return;
    }
    // Meta AI offers to add the web app to the glasses; the code and address are the fallback.
    final dialog = showDialog<void>(context: context, builder: (_) => _PairDialog(r, app.focus));
    await app.focus.remoteInstall(r.installUrl);
    await dialog;
  }

  Future<void> _remove(BuildContext context) async {
    final focus = context.app.focus;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove from glasses?'),
        content: const Text('The glasses menu stops working until you add FocusLens again. Delete the web app in Meta AI too if you like.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Remove')),
        ],
      ),
    );
    if (ok == true) {
      await focus.remoteUnpair();
      await focus.refresh();
    }
  }
}

class _PairDialog extends StatelessWidget {
  const _PairDialog(this.r, this.focus);
  final RemotePairing r;
  final FocusRepository focus;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return AlertDialog(
      title: const Text('Add to glasses menu'),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Meta AI should offer to add FocusLens to your glasses. Accept, then enter this code on the glasses (valid 10 minutes):'),
          const SizedBox(height: Gap.m),
          Center(child: SelectableText(r.code, style: text.headlineMedium)),
          const SizedBox(height: Gap.m),
          const Text('Nothing opened? In the Meta AI app: Settings → App Connections → Web Apps → add this address:'),
          const SizedBox(height: Gap.s),
          SelectableText(r.webUrl, style: text.bodyMedium),
          TextButton(
            onPressed: () => Clipboard.setData(ClipboardData(text: r.webUrl)),
            child: const Text('Copy address'),
          ),
          const SizedBox(height: Gap.s),
          Text(_privacy, style: text.bodySmall),
        ]),
      ),
      actions: [
        TextButton(onPressed: () => focus.remoteInstall(r.installUrl), child: const Text('Open Meta AI')),
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Done')),
      ],
    );
  }
}
