import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/inbox_repository.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';

/// Notes said on the glasses (swipe down). Synced to Todoist when it is connected.
class InboxScreen extends StatelessWidget {
  const InboxScreen({super.key});

  Future<void> _sync(BuildContext context) async {
    final err = await context.app.inbox.sync();
    if (context.mounted && err != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(err)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final repo = context.app.inbox;
    final text = Theme.of(context).textTheme;
    final t = Tokens.of(context);
    return SafeArea(
      child: ListenableBuilder(
        listenable: repo,
        builder: (context, _) {
          final items = repo.items;
          return RefreshIndicator(
            onRefresh: () => _sync(context),
            child: CustomScrollView(slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.s, Gap.s),
                sliver: SliverToBoxAdapter(
                  child: Row(children: [
                    Expanded(child: Text('Inbox', style: text.titleLarge)),
                    TextButton.icon(
                      onPressed: () => _sync(context),
                      icon: const Icon(Icons.sync_rounded),
                      label: const Text('Sync now'),
                    ),
                  ]),
                ),
              ),
              if (repo.loaded && items.isEmpty)
                SliverFillRemaining(
                  hasScrollBody: false,
                  child: Padding(
                    padding: const EdgeInsets.all(Gap.xl),
                    child: Center(
                      child: Text(
                        'Notes you say on the glasses (swipe down) land here.',
                        textAlign: TextAlign.center,
                        style: text.bodyLarge!.copyWith(color: t.graphite),
                      ),
                    ),
                  ),
                )
              else
                SliverList.builder(
                  itemCount: items.length,
                  itemBuilder: (context, i) => _NoteTile(item: items[i]),
                ),
              const SliverToBoxAdapter(child: SizedBox(height: Gap.xl)),
            ]),
          );
        },
      ),
    );
  }
}

class _NoteTile extends StatelessWidget {
  const _NoteTile({required this.item});
  final InboxItem item;

  @override
  Widget build(BuildContext context) {
    final repo = context.app.inbox;
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    return Dismissible(
      key: ValueKey(item.id),
      direction: DismissDirection.endToStart,
      background: Container(
        color: t.brick,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: Gap.l),
        child: const Icon(Icons.delete_outline_rounded, color: Colors.white),
      ),
      onDismissed: (_) => _deleteWithUndo(context, item),
      child: CheckboxListTile(
        value: item.done,
        onChanged: (v) => repo.setDone(item.id, v ?? false),
        controlAffinity: ListTileControlAffinity.leading,
        title: Text(
          item.text,
          style: item.done ? TextStyle(decoration: TextDecoration.lineThrough, color: t.graphite) : null,
        ),
        subtitle: Row(children: [
          Text('${_day(item.createdAt)} ${clock(item.createdAt)}', style: text.bodySmall),
          if (item.todoist) ...[
            const SizedBox(width: Gap.s),
            Icon(Icons.check_rounded, size: 14, color: t.graphite, semanticLabel: 'Synced to Todoist'),
            const SizedBox(width: 2),
            Text('Todoist', style: text.bodySmall),
          ],
        ]),
      ),
    );
  }

  /// Hide now, undo for 5 s, then delete for real (same as cards).
  void _deleteWithUndo(BuildContext context, InboxItem item) {
    final repo = context.app.inbox;
    repo.hide(item.id);
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger
        .showSnackBar(SnackBar(
          content: const Text('Note deleted'),
          duration: const Duration(seconds: 5),
          action: SnackBarAction(label: 'Undo', onPressed: () => repo.unhide(item.id)),
        ))
        .closed
        .then((reason) {
      if (reason != SnackBarClosedReason.action) repo.delete(item.id);
    });
  }

  static String _day(DateTime d) {
    final n = DateTime.now();
    if (d.year == n.year && d.month == n.month && d.day == n.day) return 'Today';
    return '${d.day}.${d.month}.';
  }
}
