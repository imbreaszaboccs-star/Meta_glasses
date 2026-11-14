import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'focus_status.dart';

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
  Future<void> openGlassesUpdate() => _call('openGlassesUpdate');
  Future<void> reconnect() => _call('reconnect');
  Future<void> startGestureTest() => _call('startGestureTest');
  Future<void> stopGestureTest() => _call('stopGestureTest');

  // focus block
  Future<void> startFocus(Map<String, Object?> config) => _call('startFocus', config);
  Future<void> stopFocus() => _call('stopFocus');
  Future<void> pauseMic() => _call('pauseMic');
  Future<void> resumeMic() => _call('resumeMic');
  Future<void> help() => _call('help');
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
  Future<void> shareCard(String text, String? photo) => _call('shareCard', {'text': text, 'photo': photo});
  Future<void> speak(String text) => _call('speak', {'text': text});
  Future<void> stopSpeaking() => _call('stopSpeaking');

  // diagnostics
  Future<void> checkServer() => _call('checkServer');
  Future<Map<String, dynamic>> diagnostics() async =>
      Map<String, dynamic>.from(await _call<Map>('diagnostics') ?? const {});

  void dispose() {
    _sub?.cancel();
    _events.close();
  }
}
