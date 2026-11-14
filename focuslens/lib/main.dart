import 'package:flutter/material.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'app.dart';
import 'app_scope.dart';
import 'data/cards_repository.dart';
import 'data/focus_repository.dart';
import 'data/inbox_repository.dart';
import 'data/settings.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('en');

  final settings = await Settings.load();
  final focus = FocusRepository();
  await focus.init();
  // The native engine mirrors the settings it needs (mode, language, buffer…).
  settings.onConfigChanged = focus.updateConfig;
  await focus.updateConfig(settings.toConfig());
  focus.status.addListener(() => settings.adoptNative(focus.status.value.config));
  final cards = CardsRepository(focus)..reload();
  final inbox = InboxRepository(focus)..reload();

  runApp(AppScope(focus: focus, settings: settings, cards: cards, inbox: inbox, child: const FocusLensApp()));
}
