import 'dart:async';

import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../theme/tokens.dart';
import 'cards/cards_screen.dart';
import 'focus/focus_screen.dart';
import 'settings/settings_screen.dart';

/// Three places, no deeper: Focus · Cards · Settings.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _tab = 0;
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    _sub = context.app.focus.events.where((e) => e['type'] == 'focusEnded').listen(_onFocusEnded);
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  void _go(int tab) => setState(() => _tab = tab);

  /// One quiet summary when a block ends. No scores, no streaks.
  void _onFocusEnded(Map<String, dynamic> e) {
    if (!mounted) return;
    context.app.cards.reloadSoon();
    final start = DateTime.fromMillisecondsSinceEpoch((e['startedAt'] as num).toInt());
    final end = DateTime.fromMillisecondsSinceEpoch((e['endedAt'] as num).toInt());
    final mins = end.difference(start).inMinutes;
    final steps = (e['hintCount'] as num?)?.toInt() ?? 0;
    final endedOnGlasses = e['reason'] == 'glasses';
    showModalBottomSheet<void>(
      context: context,
      builder: (context) {
        final text = Theme.of(context).textTheme;
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(Gap.l, 0, Gap.l, Gap.l),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Focus block ended', style: text.headlineSmall),
              const SizedBox(height: Gap.s),
              Text(
                '${mins < 1 ? 'Under a minute' : '$mins min'} · ${steps == 1 ? '1 step' : '$steps steps'}'
                '${endedOnGlasses ? ' · ended on the glasses' : ''}',
                style: text.bodyLarge!.copyWith(color: Tokens.of(context).graphite),
              ),
              const SizedBox(height: Gap.l),
              if (steps > 0)
                FilledButton(
                  onPressed: () {
                    Navigator.pop(context);
                    _go(1);
                  },
                  child: const SizedBox(width: double.infinity, child: Center(child: Text('Review cards'))),
                ),
              const SizedBox(height: Gap.s),
              SizedBox(
                width: double.infinity,
                child: TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close')),
              ),
            ]),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _tab, children: [
        FocusScreen(onOpenCards: () => _go(1)),
        const CardsScreen(),
        const SettingsScreen(),
      ]),
      bottomNavigationBar: Column(mainAxisSize: MainAxisSize.min, children: [
        const Divider(),
        NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: _go,
          destinations: const [
            NavigationDestination(icon: Icon(Icons.adjust_rounded), label: 'Focus'),
            NavigationDestination(icon: Icon(Icons.view_agenda_outlined), selectedIcon: Icon(Icons.view_agenda_rounded), label: 'Cards'),
            NavigationDestination(icon: Icon(Icons.tune_rounded), label: 'Settings'),
          ],
        ),
      ]),
    );
  }
}
