import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/focus_repository.dart';
import '../../theme/tokens.dart';
import 'notion_export.dart';

/// Evening active recall: questions made from today's cards. Got it / Again are local only.
class ReviewScreen extends StatefulWidget {
  const ReviewScreen({super.key});

  @override
  State<ReviewScreen> createState() => _ReviewScreenState();
}

class _ReviewScreenState extends State<ReviewScreen> {
  List<Flashcard> _cards = const [];
  String? _error;
  bool _loading = true;
  int _i = 0;
  bool _shown = false;
  int _got = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final ids = todaysCards(context.app.cards).map((c) => c.id).toList();
    if (ids.isEmpty) {
      setState(() {
        _loading = false;
        _error = 'No cards from today yet.';
      });
      return;
    }
    final (cards, err) = await context.app.focus.review(ids);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _cards = cards;
      _i = 0;
      _got = 0;
      _shown = false;
      _error = err ?? (cards.isEmpty ? 'No questions came back. Try again.' : null);
    });
  }

  void _next({bool got = false}) => setState(() {
        if (got) _got++;
        _i++;
        _shown = false;
      });

  Future<void> _anki(Flashcard c) async {
    final ok = await context.app.focus.shareToAnki(c.q, c.a);
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('No app to add the card with.')));
    }
  }

  Future<void> _csv() async {
    final r = await context.app.focus.exportAnkiCsv(_cards);
    if (!r.ok && mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(r.error ?? 'Export failed')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Review today'),
        actions: [
          if (_cards.isNotEmpty) TextButton(onPressed: _csv, child: const Text('Export all (CSV)')),
        ],
      ),
      body: SafeArea(child: _body(context)),
    );
  }

  Widget _body(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final t = Tokens.of(context);
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(Gap.xl),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(_error!, textAlign: TextAlign.center, style: text.bodyLarge!.copyWith(color: t.graphite)),
            const SizedBox(height: Gap.m),
            OutlinedButton(onPressed: _load, child: const Text('Try again')),
          ]),
        ),
      );
    }
    if (_i >= _cards.length) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(Gap.xl),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text('Done for today', style: text.headlineSmall),
            const SizedBox(height: Gap.s),
            Text('$_got of ${_cards.length} remembered', style: text.bodyLarge!.copyWith(color: t.graphite)),
            const SizedBox(height: Gap.l),
            FilledButton(onPressed: _csv, child: const Text('Export all (CSV)')),
            TextButton(onPressed: () => setState(() {
              _i = 0;
              _got = 0;
              _shown = false;
            }),
            child: const Text('Go again')),
          ]),
        ),
      );
    }
    final c = _cards[_i];
    return ListView(padding: const EdgeInsets.all(Gap.l), children: [
      Text('${_i + 1} / ${_cards.length}', style: text.bodySmall),
      const SizedBox(height: Gap.m),
      Text(c.q, style: text.headlineSmall),
      const SizedBox(height: Gap.l),
      if (_shown) ...[
        Container(
          padding: const EdgeInsets.all(Gap.m),
          decoration: BoxDecoration(color: t.surface, border: Border.all(color: t.rule)),
          child: Text(c.a, style: text.bodyLarge),
        ),
        const SizedBox(height: Gap.l),
        Row(children: [
          Expanded(child: OutlinedButton(onPressed: () => _next(), child: const Text('Again'))),
          const SizedBox(width: Gap.m),
          Expanded(child: FilledButton(onPressed: () => _next(got: true), child: const Text('Got it'))),
        ]),
      ] else
        FilledButton(onPressed: () => setState(() => _shown = true), child: const Text('Show answer')),
      const SizedBox(height: Gap.m),
      TextButton.icon(
        onPressed: () => _anki(c),
        icon: const Icon(Icons.add_card_rounded),
        label: const Text('Add to AnkiDroid'),
      ),
    ]);
  }
}
