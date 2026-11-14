import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/cards_repository.dart';

/// Today's finished cards, oldest first (what "Review today" and "Export today" work on).
List<CardItem> todaysCards(CardsRepository repo) {
  final n = DateTime.now();
  return repo.visible
      .where((c) => c.done && c.createdAt.year == n.year && c.createdAt.month == n.month && c.createdAt.day == n.day)
      .toList()
      .reversed
      .toList();
}

/// Sends cards to the user's Notion database (text only, photos are not uploaded) and reports the outcome.
Future<void> exportToNotion(BuildContext context, List<String> ids) async {
  final focus = context.app.focus;
  final messenger = ScaffoldMessenger.of(context);
  final status = await focus.integrationStatus();
  if ((status['notion'] as Map?)?['configured'] != true) {
    messenger.showSnackBar(const SnackBar(content: Text('Connect Notion first: Settings → Integrations.')));
    return;
  }
  messenger.showSnackBar(const SnackBar(content: Text('Sending to Notion…'), duration: Duration(seconds: 30)));
  final r = await focus.exportCardsToNotion(ids);
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(SnackBar(
    content: Text(r.ok ? 'Exported to Notion' : (r.error ?? 'Export failed')),
    action: r.ok && r.url != null ? SnackBarAction(label: 'Open', onPressed: () => focus.openUrl(r.url!)) : null,
  ));
}
