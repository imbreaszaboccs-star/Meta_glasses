import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../data/focus_status.dart';

/// One line about the glasses + the single action that fixes the current problem.
class GlassesLine {
  const GlassesLine(this.value, {this.problem = false, this.fixLabel, this.fix});
  final String value;
  final bool problem;
  final String? fixLabel;
  final Future<void> Function(BuildContext)? fix;
}

GlassesLine glassesLine(FocusStatus s) {
  if (!s.bluetoothPermission) {
    return GlassesLine('Bluetooth not allowed', problem: true, fixLabel: 'Allow', fix: (c) => c.app.focus.requestPermissions());
  }
  if (!s.sdkReady) return const GlassesLine('starting…');
  if (s.registering) return const GlassesLine('linking…');
  if (!s.registered) {
    return GlassesLine('not linked to Meta AI', problem: true, fixLabel: 'Link', fix: (c) => c.app.focus.register());
  }
  if (!s.glassesKnown) {
    return GlassesLine('none paired', problem: true, fixLabel: 'Open Meta AI', fix: (c) => c.app.focus.openMetaAi());
  }
  // Paired but the DAT link idles (e.g. after a session): a session can still be opened.
  if (!s.glassesConnected) return GlassesLine('${s.glassesName ?? 'Glasses'} · standby');
  if (s.updateRequired || s.compatibilityIssue) {
    return GlassesLine('needs an update', problem: true, fixLabel: 'Update', fix: (c) => c.app.focus.openGlassesUpdate());
  }
  final name = s.glassesName ?? 'Glasses';
  if (s.cameraPermission == 'denied') {
    return GlassesLine('$name · camera not allowed', problem: true, fixLabel: 'Allow', fix: (c) => c.app.focus.requestGlassesCamera());
  }
  if (s.glassesMicPermission != 'granted') {
    return GlassesLine('$name · microphone not allowed', problem: true, fixLabel: 'Allow', fix: (c) => c.app.focus.requestGlassesMic());
  }
  final battery = s.glassesBattery;
  final parts = [
    name,
    if (battery != null && battery > 0) '$battery %',
    if (!s.displayCapable) 'no display, steps are spoken',
  ];
  return GlassesLine(parts.join(' · '));
}

/// Asks for whatever is still missing, then starts the block. Works without glasses (phone only).
Future<void> startFocusFlow(BuildContext context, {String goal = ''}) async {
  final app = context.app;
  var s = app.focus.status.value;
  if (!s.micPermission || !s.notificationPermission) {
    await app.focus.requestPermissions();
    await app.focus.refresh();
    s = app.focus.status.value;
  }
  if (s.registered && s.glassesConnected && s.cameraPermission != 'granted') {
    await app.focus.requestGlassesCamera();
  }
  await app.focus.startFocus({...app.settings.toConfig(), 'mode': 'auto', 'goal': goal.trim()});
  await app.focus.refresh();
  if (!context.mounted) return;
  final after = app.focus.status.value;
  if (!after.micPermission) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
      content: Text('The microphone isn\'t allowed, so steps will use the photo only.'),
    ));
  }
}
