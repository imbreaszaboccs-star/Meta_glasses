import 'dart:async';

import 'package:flutter/foundation.dart';

import 'focus_repository.dart';

class InboxItem {
  InboxItem(this.id, this.text, this.createdAt, this.done, this.todoist);
  final String id, text;
  final DateTime createdAt;
  final bool done, todoist;
}

/// Brain-dump notes (`inbox.json`, owned by the native InboxStore). Mutations go native, then we reload.
class InboxRepository extends ChangeNotifier {
  InboxRepository(this._focus) {
    // The glasses add notes while the app is open and no event is emitted, so poll cheaply.
    _timer = Timer.periodic(const Duration(seconds: 10), (_) => reload());
  }

  final FocusRepository _focus;
  Timer? _timer;
  List<InboxItem> _all = const [];
  final _hidden = <String>{};
  List<InboxItem> get items => [for (final i in _all) if (!_hidden.contains(i.id)) i];
  bool loaded = false;

  void hide(String id) {
    _hidden.add(id);
    notifyListeners();
  }

  void unhide(String id) {
    _hidden.remove(id);
    notifyListeners();
  }

  int get openCount => items.where((i) => !i.done).length;

  Future<void> reload() async {
    final raw = await _focus.inboxList();
    _all = [
      for (final j in raw.whereType<Map>())
        InboxItem(
          '${j['id']}',
          '${j['text']}',
          DateTime.tryParse('${j['createdAt']}') ?? DateTime.now(),
          j['done'] == true,
          (j['synced'] as Map?)?['todoist'] == true,
        ),
    ].reversed.toList();
    loaded = true;
    notifyListeners();
  }

  Future<void> setDone(String id, bool done) async {
    await _focus.inboxSetDone(id, done);
    await reload();
  }

  Future<void> delete(String id) async {
    await _focus.inboxDelete(id);
    await reload();
    _hidden.remove(id);
  }

  /// Pushes unsynced notes to Todoist now. Returns an error message or null.
  Future<String?> sync() async {
    final r = await _focus.inboxSync();
    await reload();
    return r.ok ? null : r.error;
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}
