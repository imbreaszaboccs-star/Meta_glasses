import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'focus_status.dart';

class IntegrationResult {
  const IntegrationResult(this.ok, this.error, this.url);
  final bool ok;
  final String? error;
  final String? url;
}

class RemotePairing {
  const RemotePairing({required this.ok, this.error, this.code = '', this.webUrl = '', this.installUrl = ''});
  final bool ok;
  final String? error;
  final String code, webUrl, installUrl;
}

class Flashcard {
  const Flashcard(this.q, this.a);
  final String q, a;
}

/// The only place that talks to the native FocusEngine (task.md T1.9). UI code never touches the channel.
class FocusRepository {
  static const _m = MethodChannel('focuslens/focus');
  static const _e = EventChannel('focuslens/focus/events');

  final status = ValueNotifier<FocusStatus>(FocusStatus.empty);
  final _events = StreamController<Map<String, dynamic>>.broadcast();
  StreamSubscription? _sub;

  late String cardsDir;
  late String sessionsDir;

  /// Events other than status: diag | card | gesture | focusEnded | updateRequired.
  Stream<Map<String, dynamic>> get events => _events.stream;

  Future<void> init() async {
    _sub = _e.receiveBroadcastStream().listen((raw) {
      final e = Map<String, dynamic>.from(raw as Map);
      if (e['type'] == 'status') {
        status.value = FocusStatus(Map<String, dynamic>.from(e['status'] as Map));
      } else {
        _events.add(e);
      }
    });
    final paths = Map<String, dynamic>.from(await _m.invokeMethod('paths') as Map);
    cardsDir = paths['cards'] as String;
    sessionsDir = paths['sessions'] as String;
    await refresh();
  }

  Future<void> refresh() async {
    final s = await _m.invokeMethod('getStatus');
    if (s != null) status.value = FocusStatus(Map<String, dynamic>.from(s as Map));
  }

  Future<T?> _call<T>(String method, [Object? args]) async {
    try {
      return await _m.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      debugPrint('focus/$method failed: $e');
      return null;
    }
  }

  // permissions & system
  Future<void> requestPermissions() => _call('requestPermissions');
  Future<bool> openBatterySettings() async => await _call<bool>('openBatterySettings') ?? false;
  Future<bool> openAppSettings() async => await _call<bool>('openAppSettings') ?? false;
  Future<bool> openMetaAi() async => await _call<bool>('openMetaAi') ?? false;

  // glasses
  Future<void> register() => _call('register');
  Future<void> unregister() => _call('unregister');
  Future<String?> requestGlassesCamera() => _call<String>('requestGlassesCamera');
  Future<String?> requestGlassesMic() => _call<String>('requestGlassesMic');
  Future<void> openGlassesUpdate() => _call('openGlassesUpdate');

  // glasses menu: pair this phone with the web app on the glasses (backend relay)
  Future<RemotePairing> remotePair() async {
    final m = await _call<Map>('remotePair');
    if (m == null) return const RemotePairing(ok: false, error: 'Something went wrong.');
    return RemotePairing(
      ok: m['ok'] == true,
      error: m['error'] as String?,
      code: '${m['code'] ?? ''}',
      webUrl: '${m['webUrl'] ?? ''}',
      installUrl: '${m['installUrl'] ?? ''}',
    );
  }

  Future<void> remoteUnpair() => _call('remoteUnpair');
  Future<bool> remoteInstall(String url) async => await _call<bool>('remoteInstall', {'url': url}) ?? false;
  Future<void> reconnect() => _call('reconnect');
  Future<void> startGestureTest() => _call('startGestureTest');
  Future<void> stopGestureTest() => _call('stopGestureTest');

  // focus block
  Future<void> startFocus(Map<String, Object?> config) => _call('startFocus', config);
  Future<void> stopFocus() => _call('stopFocus');
  Future<void> pauseMic() => _call('pauseMic');
  Future<void> resumeMic() => _call('resumeMic');
  Future<void> help() => _call('help');
  Future<void> bookmark() => _call('bookmark');
  Future<void> next() => _call('next');
  Future<void> prev() => _call('prev');
  Future<void> dismiss() => _call('dismiss');
  Future<void> setMode(String mode) => _call('setMode', {'mode': mode});
  Future<void> updateConfig(Map<String, Object?> config) => _call('updateConfig', config);

  // cards
  Future<void> deleteCard(String id) => _call('deleteCard', {'id': id});
  Future<void> setReviewed(String id, bool reviewed) => _call('setReviewed', {'id': id, 'reviewed': reviewed});
  Future<void> retryCard(String id) => _call('retryCard', {'id': id});
  Future<void> retryPending() => _call('retryPending');
  Future<void> deleteAllData() => _call('deleteAllData');
  Future<void> forgetMistakes() => _call('forgetMistakes');
  Future<void> shareCard(String text, String? photo) => _call('shareCard', {'text': text, 'photo': photo});
  Future<void> speak(String text) => _call('speak', {'text': text});
  Future<void> stopSpeaking() => _call('stopSpeaking');

  // data collection (motion recordings stay on the phone until exported)
  Future<void> setImuLabel(String? label) => _call('setImuLabel', {'label': label});
  Future<IntegrationResult> exportImu() => _result('exportImu');

  // inbox, integrations, review (all on the native side; tokens never reach Dart)
  Future<List<dynamic>> inboxList() async => jsonDecode(await _call<String>('inboxList') ?? '[]') as List<dynamic>;
  Future<void> inboxSetDone(String id, bool done) => _call('inboxSetDone', {'id': id, 'done': done});
  Future<void> inboxDelete(String id) => _call('inboxDelete', {'id': id});
  Future<IntegrationResult> inboxSync() => _result('inboxSync');
  Future<Map<String, dynamic>> integrationStatus() async =>
      Map<String, dynamic>.from(await _call<Map>('integrationStatus') ?? const {});
  Future<IntegrationResult> integrationSave(String kind, String token, {String? database}) =>
      _result('integrationSave', {'kind': kind, 'token': token, 'database': database});
  Future<void> integrationClear(String kind) => _call('integrationClear', {'kind': kind});
  Future<IntegrationResult> integrationTest(String kind) => _result('integrationTest', {'kind': kind});
  Future<IntegrationResult> exportCardsToNotion(List<String> ids) => _result('exportCardsToNotion', {'ids': ids});
  Future<bool> openUrl(String url) async => await _call<bool>('openUrl', {'url': url}) ?? false;
  Future<bool> shareToAnki(String front, String back) async =>
      await _call<bool>('shareToAnki', {'front': front, 'back': back}) ?? false;
  Future<IntegrationResult> exportAnkiCsv(List<Flashcard> cards) =>
      _result('exportAnkiCsv', {'cards': [for (final c in cards) {'q': c.q, 'a': c.a}]});

  /// Flashcards for the given cards, written by the backend's /review.
  Future<(List<Flashcard>, String?)> review(List<String> ids) async {
    final m = await _call<Map>('review', {'ids': ids});
    if (m == null) return (<Flashcard>[], 'Something went wrong.');
    final cards = [
      for (final c in (m['cards'] as List? ?? const []).whereType<Map>()) Flashcard('${c['q']}', '${c['a']}'),
    ];
    return (cards, m['ok'] == true ? null : (m['error'] as String? ?? 'Something went wrong.'));
  }

  Future<IntegrationResult> _result(String method, [Object? args]) async {
    final m = await _call<Map>(method, args);
    if (m == null) return const IntegrationResult(false, 'Something went wrong.', null);
    return IntegrationResult(m['ok'] == true, m['error'] as String?, m['url'] as String?);
  }

  // diagnostics
  Future<void> checkServer() => _call('checkServer');
  Future<Map<String, dynamic>> diagnostics() async =>
      Map<String, dynamic>.from(await _call<Map>('diagnostics') ?? const {});

  void dispose() {
    _sub?.cancel();
    _events.close();
  }
}
