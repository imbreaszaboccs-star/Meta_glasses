import 'package:flutter/material.dart';

import 'app_scope.dart';
import 'screens/home_shell.dart';
import 'screens/onboarding/onboarding_screen.dart';
import 'theme/theme.dart';

class FocusLensApp extends StatefulWidget {
  const FocusLensApp({super.key});

  @override
  State<FocusLensApp> createState() => _FocusLensAppState();
}

class _FocusLensAppState extends State<FocusLensApp> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Cards may have been written (or filled by the retry worker) while we were away.
      context.app.cards.reloadSoon();
      context.app.focus.refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.app.settings;
    return MaterialApp(
      title: 'FocusLens',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      themeMode: ThemeMode.system,
      // Respect the system text size, up to 200 % (task.md T2.6).
      builder: (context, child) => MediaQuery.withClampedTextScaling(maxScaleFactor: 2.0, child: child!),
      home: ListenableBuilder(
        listenable: settings,
        builder: (context, _) => settings.onboarded ? const HomeShell() : const OnboardingScreen(),
      ),
    );
  }
}
