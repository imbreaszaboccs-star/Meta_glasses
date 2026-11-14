import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../app_scope.dart';
import '../../data/cards_repository.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';
import 'card_detail_screen.dart';

/// The evening review: cards grouped by day, then by focus block.
class CardsScreen extends StatelessWidget {
  const CardsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final repo = context.app.cards;
    final text = Theme.of(context).textTheme;
    return SafeArea(
      child: ListenableBuilder(
        listenable: repo,
        builder: (context, _) {
          final cards = repo.visible;
          final pending = repo.pendingCount;
          return RefreshIndicator(
            onRefresh: repo.reload,
            child: CustomScrollView(slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.l, Gap.s),
                sliver: SliverToBoxAdapter(child: Text('Cards', style: text.titleLarge)),
              ),
              if (pending > 0)
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: Gap.l),
                  sliver: SliverToBoxAdapter(
                    child: StatusRow(
                      label: 'Waiting',
                      value: pending == 1 ? '1 card for the server' : '$pending cards for the server',
                      fixLabel: 'Send now',
                      onFix: () => context.app.focus.retryPending(),
                    ),
                  ),
                ),
              if (repo.loaded && cards.isEmpty)
                SliverFillRemaining(
                  hasScrollBody: false,
                  child: Padding(
                    padding: const EdgeInsets.all(Gap.xl),
                    child: Center(
                      child: Text(
                        'What the glasses helped you with will show up here.',
                        textAlign: TextAlign.center,
                        style: text.bodyLarge!.copyWith(color: Tokens.of(context).graphite),
                      ),
                    ),
                  ),
                )
              else
                SliverList.list(children: _grouped(context, cards, repo.sessions)),
              const SliverToBoxAdapter(child: SizedBox(height: Gap.xl)),
            ]),
          );
        },
      ),
    );
  }

  List<Widget> _grouped(BuildContext context, List<CardItem> cards, Map<String, FocusSession> sessions) {
    final out = <Widget>[];
    String? day;
    String? session;
    for (final c in cards) {
      final d = _dayLabel(c.createdAt);
      if (d != day) {
        day = d;
        session = null;
        out.add(SectionLabel(d, padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.l, Gap.xs)));
      }
      if (c.sessionId != session) {
        session = c.sessionId;
        final s = sessions[c.sessionId];
        if (s != null) out.add(_SessionHeader(s));
      }
      out.add(CardRow(card: c));
    }
    return out;
  }

  static String _dayLabel(DateTime d) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final that = DateTime(d.year, d.month, d.day);
    final diff = today.difference(that).inDays;
    if (diff == 0) return 'Today';
    if (diff == 1) return 'Yesterday';
    return DateFormat(d.year == now.year ? 'EEEE d MMMM' : 'EEEE d MMMM y', 'en').format(d);
  }
}

class _SessionHeader extends StatelessWidget {
  const _SessionHeader(this.s);
  final FocusSession s;

  @override
  Widget build(BuildContext context) {
    final range = s.endedAt == null ? '${clock(s.startedAt)} – now' : '${clock(s.startedAt)}–${clock(s.endedAt!)}';
    return Padding(
      padding: const EdgeInsets.fromLTRB(Gap.l, Gap.s, Gap.l, Gap.xs),
      child: Text('${modeNames[s.mode] ?? s.mode} · $range', style: Theme.of(context).textTheme.bodySmall),
    );
  }
}

class CardRow extends StatelessWidget {
  const CardRow({super.key, required this.card});
  final CardItem card;

  Future<void> _open(BuildContext context) async {
    final deleted = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => CardDetailScreen(cardId: card.id)),
    );
    if (deleted == true && context.mounted) deleteWithUndo(context, card.id);
  }

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    final title = card.title.isNotEmpty ? card.title : (card.pending ? 'Waiting for the server' : 'Untitled');
    final sub = card.firstStep;
    return Dismissible(
      key: ValueKey(card.id),
      direction: DismissDirection.endToStart,
      background: Container(
        color: t.rule,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: Gap.l),
        child: Text('Delete', style: text.labelMedium),
      ),
      onDismissed: (_) => deleteWithUndo(context, card.id),
      child: Opacity(
        opacity: card.reviewed ? 0.55 : 1,
        child: InkWell(
          onTap: () => _open(context),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: Gap.l, vertical: Gap.m - 4),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              _Thumb(card),
              const SizedBox(width: Gap.m),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, style: text.titleMedium, maxLines: 2, overflow: TextOverflow.ellipsis),
                  if (sub != null && sub != title) ...[
                    const SizedBox(height: 2),
                    Text(sub, style: text.bodyMedium, maxLines: 2, overflow: TextOverflow.ellipsis),
                  ],
                  const SizedBox(height: Gap.xs),
                  Row(children: [
                    Text(clock(card.createdAt), style: TextStyle(fontFamily: kMono, fontSize: 13, color: t.graphite)),
                    if (card.pending) ...[
                      const SizedBox(width: Gap.s),
                      Text('waiting for the server', style: text.bodySmall!.copyWith(fontStyle: FontStyle.italic)),
                    ],
                    if (card.failed) ...[
                      const SizedBox(width: Gap.s),
                      Text('not sent', style: text.bodySmall!.copyWith(color: t.brick, fontWeight: FontWeight.w700)),
                    ],
                    if (card.reviewed) ...[
                      const SizedBox(width: Gap.s),
                      Text('reviewed', style: text.bodySmall),
                    ],
                  ]),
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb(this.card);
  final CardItem card;

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    const size = 64.0;
    final photo = card.photo;
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: SizedBox(
        width: size,
        height: size,
        child: photo != null
            ? Image.file(File(photo), fit: BoxFit.cover, cacheWidth: 192, gaplessPlayback: true,
                semanticLabel: 'Photo', errorBuilder: (_, _, _) => _placeholder(t))
            : _placeholder(t),
      ),
    );
  }

  Widget _placeholder(Tokens t) => ColoredBox(
        color: t.rule,
        child: Icon(Icons.graphic_eq_rounded, color: t.graphite, semanticLabel: 'Sound only'),
      );
}

/// Hide now, undo for a few seconds, then delete for real. No confirmation dialog.
void deleteWithUndo(BuildContext context, String id) {
  final repo = context.app.cards;
  repo.hide(id);
  final messenger = ScaffoldMessenger.of(context);
  messenger.hideCurrentSnackBar();
  messenger
      .showSnackBar(SnackBar(
        content: const Text('Card deleted'),
        duration: const Duration(seconds: 5),
        action: SnackBarAction(label: 'Undo', onPressed: () => repo.unhide(id)),
      ))
      .closed
      .then((reason) {
    if (reason != SnackBarClosedReason.action) repo.commitDelete(id);
  });
}
