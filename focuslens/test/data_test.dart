import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:focuslens/data/cards_repository.dart';
import 'package:focuslens/data/focus_status.dart';
import 'package:focuslens/widgets/common.dart';

void main() {
  late Directory tmp;

  setUp(() => tmp = Directory.systemTemp.createTempSync('focuslens_test'));
  tearDown(() => tmp.deleteSync(recursive: true));

  Directory card(String id, Map<String, dynamic> json, {List<String> files = const []}) {
    final d = Directory('${tmp.path}/$id')..createSync();
    File('${d.path}/card.json').writeAsStringSync(jsonEncode(json));
    for (final f in files) {
      File('${d.path}/$f').writeAsBytesSync([1, 2, 3]);
    }
    return d;
  }

  test('parses a done card written by the native CardStore', () {
    final d = card('2026-11-14T10-32-05', {
      'id': '2026-11-14T10-32-05',
      'sessionId': 's1',
      'createdAt': '2026-11-14T10:32:05',
      'mode': 'homework',
      'status': 'done',
      'steps': [
        {'text': 'Fix the minus on line 3.', 'at': '2026-11-14T10:32:09', 'photo': 'photo.jpg'},
        {'text': 'Now multiply both sides by two.', 'at': '2026-11-14T10:33:00', 'photo': 'photo-2.jpg'},
      ],
      'card': {'title': 'Quadratic equation', 'summary': 'Sign error.', 'correction': '25 − 24 = 1', 'transcript_tail': 'watch the sign'},
      'reviewed': false,
      'audio': 'audio.ogg',
    }, files: ['photo.jpg', 'photo-2.jpg', 'audio.ogg']);

    final c = CardItem.parse(d)!;
    expect(c.done, isTrue);
    expect(c.mode, 'homework');
    expect(c.steps.map((s) => s.text), ['Fix the minus on line 3.', 'Now multiply both sides by two.']);
    expect(c.photos.length, 2);
    expect(c.photo, endsWith('photo.jpg'));
    expect(c.audioPath, endsWith('audio.ogg'));
    expect(c.correction, '25 − 24 = 1');
    expect(c.toShareText(), contains('→ Fix the minus on line 3.'));
    expect(c.toSpeech(), startsWith('Quadratic equation. Fix the minus'));
  });

  test('a pending card has its photo but no steps, and a missing audio file is ignored', () {
    final d = card('2026-11-14T11-00-00', {
      'id': '2026-11-14T11-00-00',
      'status': 'pending',
      'steps': [],
      'card': {},
      'audio': 'audio.ogg', // file not written
    }, files: ['photo.jpg']);

    final c = CardItem.parse(d)!;
    expect(c.pending, isTrue);
    expect(c.photo, endsWith('photo.jpg'));
    expect(c.audioPath, isNull);
    expect(c.firstStep, isNull);
    expect(c.title, isEmpty);
  });

  test('a broken card.json is skipped, not a crash', () {
    final d = Directory('${tmp.path}/broken')..createSync();
    File('${d.path}/card.json').writeAsStringSync('{not json');
    expect(CardItem.parse(d), isNull);
  });

  test('FocusStatus reads the engine snapshot with safe defaults', () {
    expect(FocusStatus.empty.running, isFalse);
    expect(FocusStatus.empty.mode, 'auto');
    expect(FocusStatus.empty.situation, isEmpty);
    expect(FocusStatus.empty.attentionState, isNull);
    expect(FocusStatus.empty.registration, 'UNAVAILABLE');

    const s = FocusStatus({
      'permissions': {'mic': true, 'bluetooth': true, 'notifications': true, 'battery': false},
      'registration': 'REGISTERED',
      'glasses': {'connected': true, 'name': 'Meta Ray-Ban Display', 'battery': 81, 'phase': 'READY', 'hasDisplay': true},
      'focus': {'running': true, 'mode': 'auto', 'situation': 'lecture', 'attention': 'ENGAGED', 'startedAt': 1763112725000, 'hintCount': 3},
      'mic': {'listening': true, 'bufferedSec': 42},
      'hint': {'phase': 'SHOWN', 'text': 'Clear the left side of the desk.', 'lastText': 'Clear the left side of the desk.'},
      'config': {'bufferSec': 120},
    });
    expect(s.basicPermissions, isTrue);
    expect(s.batteryUnrestricted, isFalse);
    expect(s.registered, isTrue);
    expect(s.glassesReady, isTrue);
    expect(s.glassesBattery, 81);
    expect(s.running, isTrue);
    expect(s.mode, 'auto');
    expect(s.situation, 'lecture');
    expect(s.attentionState, 'ENGAGED');
    expect(s.hintCount, 3);
    expect(s.bufferedSec, 42);
    expect(s.shownText, 'Clear the left side of the desk.');
    expect(s.bufferSecConfig, 120);
    expect(s.startedAt, isNotNull);
  });

  test('time formatting', () {
    expect(elapsed(const Duration(minutes: 4, seconds: 7)), '04:07');
    expect(elapsed(const Duration(hours: 1, minutes: 2, seconds: 3)), '1:02:03');
    expect(clock(DateTime(2026, 11, 14, 9, 5)), '9:05');
  });
}
