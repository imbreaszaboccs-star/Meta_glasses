import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'focus_repository.dart';

class CardStep {
  CardStep(this.text, this.at, this.photo);
  final String text;
  final DateTime? at;
  final String? photo; // absolute path
}

/// One context card (`filesDir/cards/<id>/card.json`, written by the native CardStore).
class CardItem {
  CardItem({
    required this.id,
    required this.dir,
    required this.sessionId,
    required this.createdAt,
    required this.mode,
    required this.situation,
    required this.status,
    required this.error,
    required this.steps,
    required this.title,
    required this.summary,
    required this.correction,
    required this.transcript,
    required this.reviewed,
    required this.audioPath,
    required this.photos,
    required this.late,
    required this.formula,
    required this.formulaPath,
    required this.speakers,
    required this.quest,
    required this.questIndex,
    required this.cropPath,
  });

  final String id, dir, mode, status, title, summary, correction, transcript;
  final String? situation; // what the AI detected in auto mode
  final String? sessionId, error, audioPath;
  final DateTime createdAt;
  final List<CardStep> steps;
  final bool reviewed, late;
  final String formula; // LaTeX source, '' if none
  final String? formulaPath; // PNG, white on black
  final List<(String, String)> speakers; // (label, said)
  final List<String> photos; // absolute paths, first = the original photo
  final List<(String, bool)> quest; // micro-steps (text, done); empty when the hint had a single step
  final int questIndex; // the step the user was on
  final String? cropPath; // the error, cropped and framed

  bool get pending => status == 'pending';
  bool get failed => status == 'failed';
  bool get done => status == 'done';
  String? get photo => photos.isEmpty ? null : photos.first;
  String? get firstStep => steps.isEmpty ? null : steps.first.text;

  /// Plain text for sharing / reading aloud.
  String toShareText() {
    final b = StringBuffer();
    if (title.isNotEmpty) b.writeln(title);
    for (final s in steps) {
      b.writeln('→ ${s.text}');
    }
    if (correction.isNotEmpty) b..writeln()..writeln('Correction: $correction');
    if (summary.isNotEmpty) b..writeln()..writeln(summary);
    return b.toString().trim();
  }

  String toSpeech() => [
        if (title.isNotEmpty) title,
        ...steps.map((s) => s.text),
        if (correction.isNotEmpty) correction,
        if (summary.isNotEmpty) summary,
      ].join('. ');

  static CardItem? parse(Directory d) {
    try {
      final f = File('${d.path}/card.json');
      if (!f.existsSync()) return null;
      final j = jsonDecode(f.readAsStringSync()) as Map<String, dynamic>;
      final card = (j['card'] as Map?)?.cast<String, dynamic>() ?? const {};
      String path(String name) => '${d.path}/$name';
      final steps = <CardStep>[];
      final photos = <String>[];
      for (final s in (j['steps'] as List? ?? const [])) {
        final m = (s as Map).cast<String, dynamic>();
        final p = m['photo'] as String?;
        if (p != null && File(path(p)).existsSync()) photos.add(path(p));
        steps.add(CardStep(m['text'] as String? ?? '', DateTime.tryParse(m['at'] as String? ?? ''), p == null ? null : path(p)));
      }
      // A pending card has its photo but no steps yet.
      if (photos.isEmpty && File(path('photo.jpg')).existsSync()) photos.add(path('photo.jpg'));
      final audio = j['audio'] as String?;
      final q = (j['quest'] as Map?)?.cast<String, dynamic>() ?? const {};
      final qDone = (q['done'] as List? ?? const []);
      final quest = <(String, bool)>[
        for (final (i, t) in (q['steps'] as List? ?? const []).indexed) ('$t', i < qDone.length && qDone[i] == true),
      ];
      return CardItem(
        id: j['id'] as String? ?? d.uri.pathSegments.where((s) => s.isNotEmpty).last,
        dir: d.path,
        sessionId: j['sessionId'] as String?,
        createdAt: DateTime.tryParse(j['createdAt'] as String? ?? '') ?? d.statSync().modified,
        mode: j['mode'] as String? ?? 'auto',
        situation: card['situation'] as String?,
        status: j['status'] as String? ?? 'done',
        error: j['error'] as String?,
        steps: steps,
        title: (card['title'] as String? ?? '').trim(),
        summary: (card['summary'] as String? ?? '').trim(),
        correction: (card['correction'] as String? ?? '').trim(),
        transcript: (card['transcript_tail'] as String? ?? '').trim(),
        reviewed: j['reviewed'] == true,
        audioPath: audio != null && File(path(audio)).existsSync() ? path(audio) : null,
        photos: photos,
        late: j['late'] == true,
        formula: (j['formula'] as String? ?? '').trim(),
        formulaPath: () {
          final f = j['formulaImage'] as String?;
          return f != null && File(path(f)).existsSync() ? path(f) : null;
        }(),
        quest: quest,
        questIndex: (q['index'] as num?)?.toInt() ?? 0,
        cropPath: () {
          final f = j['cropImage'] as String?;
          return f != null && File(path(f)).existsSync() ? path(f) : null;
        }(),
        speakers: [
          for (final m in (card['speakers'] as List? ?? const []).whereType<Map>())
            if ('${m['said'] ?? ''}'.trim().isNotEmpty) ('${m['label'] ?? ''}'.trim(), '${m['said']}'.trim()),
        ],
      );
    } catch (e) {
      debugPrint('bad card ${d.path}: $e');
      return null;
    }
  }
}

class FocusSession {
  FocusSession(this.id, this.mode, this.startedAt, this.endedAt);
  final String id, mode;
  final DateTime startedAt;
  final DateTime? endedAt;
}

/// Reads cards from disk. All mutations go through the native side (one writer), then we reload.
class CardsRepository extends ChangeNotifier {
  CardsRepository(this._focus) {
    _sub = _focus.events.where((e) => e['type'] == 'card').listen((_) => reloadSoon());
  }

  final FocusRepository _focus;
  StreamSubscription? _sub;
  Timer? _debounce;

  List<CardItem> cards = const [];
  Map<String, FocusSession> sessions = const {};
  final Set<String> _hidden = {}; // deleted with undo pending
  bool loaded = false;

  List<CardItem> get visible => cards.where((c) => !_hidden.contains(c.id)).toList();
  int get pendingCount => cards.where((c) => c.pending).length;

  CardItem? byId(String id) {
    for (final c in cards) {
      if (c.id == id) return c;
    }
    return null;
  }

  void reloadSoon() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), reload);
  }

  Future<void> reload() async {
    final result = await compute(_load, (_focus.cardsDir, _focus.sessionsDir));
    cards = result.$1;
    sessions = result.$2;
    loaded = true;
    notifyListeners();
  }

  static (List<CardItem>, Map<String, FocusSession>) _load((String, String) dirs) {
    final cards = <CardItem>[];
    final cd = Directory(dirs.$1);
    if (cd.existsSync()) {
      for (final e in cd.listSync()) {
        if (e is Directory) {
          final c = CardItem.parse(e);
          if (c != null) cards.add(c);
        }
      }
    }
    cards.sort((a, b) => b.id.compareTo(a.id));
    final sessions = <String, FocusSession>{};
    final sd = Directory(dirs.$2);
    if (sd.existsSync()) {
      for (final f in sd.listSync().whereType<File>().where((f) => f.path.endsWith('.json'))) {
        try {
          final j = jsonDecode(f.readAsStringSync()) as Map<String, dynamic>;
          final end = (j['endedAt'] as num?)?.toInt();
          sessions[j['id'] as String] = FocusSession(
            j['id'] as String,
            j['mode'] as String? ?? 'auto',
            DateTime.fromMillisecondsSinceEpoch((j['startedAt'] as num).toInt()),
            end == null ? null : DateTime.fromMillisecondsSinceEpoch(end),
          );
        } catch (_) {}
      }
    }
    return (cards, sessions);
  }

  /// Hide now, delete for real when [commit] is called (after the undo window).
  void hide(String id) {
    _hidden.add(id);
    notifyListeners();
  }

  void unhide(String id) {
    _hidden.remove(id);
    notifyListeners();
  }

  Future<void> commitDelete(String id) async {
    await _focus.deleteCard(id);
    _hidden.remove(id);
    cards = cards.where((c) => c.id != id).toList();
    notifyListeners();
  }

  Future<void> setReviewed(String id, bool reviewed) async {
    await _focus.setReviewed(id, reviewed);
    reloadSoon();
  }

  Future<void> retry(String id) async {
    await _focus.retryCard(id);
    reloadSoon();
  }

  @override
  void dispose() {
    _sub?.cancel();
    _debounce?.cancel();
    super.dispose();
  }
}
