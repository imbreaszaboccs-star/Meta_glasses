import 'package:flutter/widgets.dart';

import 'data/cards_repository.dart';
import 'data/focus_repository.dart';
import 'data/settings.dart';

/// Hands the three app-wide objects down the tree. No state-management framework needed.
class AppScope extends InheritedWidget {
  const AppScope({
    super.key,
    required this.focus,
    required this.settings,
    required this.cards,
    required super.child,
  });

  final FocusRepository focus;
  final Settings settings;
  final CardsRepository cards;

  static AppScope of(BuildContext context) => context.getInheritedWidgetOfExactType<AppScope>()!;

  @override
  bool updateShouldNotify(AppScope oldWidget) => false;
}

extension AppScopeX on BuildContext {
  AppScope get app => AppScope.of(this);
}
