import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// User settings (task.md T2.4). Persisted here and mirrored to the native FocusEngine on every change.
class Settings extends ChangeNotifier {
  Settings._(this._p);

  static Future<Settings> load() async => Settings._(await SharedPreferences.getInstance());

  final SharedPreferences _p;

  /// Called after every change with the native config map.
  void Function(Map<String, Object?> config)? onConfigChanged;

  bool get onboarded => _p.getBool('onboarded') ?? false;
  bool get privacyAccepted => _p.getBool('privacyAccepted') ?? false;
  /// Language of the hints from the backend. The app UI itself is English.
  String get language => _p.getString('language') ?? 'en';
  String get mode => _p.getString('mode') ?? 'lecture';
  int get bufferSec => _p.getInt('bufferSec') ?? 180;
  String get keepAudio => _p.getString('keepAudio') ?? 'last60';
  int get hudTimeoutSec => _p.getInt('hudTimeoutSec') ?? 20;
  bool get readAloud => _p.getBool('readAloud') ?? false;
  int get retentionDays => _p.getInt('retentionDays') ?? 30;
  bool get diagnosticsUnlocked => _p.getBool('diagnostics') ?? false;

  Map<String, Object?> toConfig() => {
        'mode': mode,
        'language': language,
        'bufferSec': bufferSec,
        'keepAudio': keepAudio,
        'hudTimeoutSec': hudTimeoutSec,
        'readAloud': readAloud,
        'retentionDays': retentionDays,
      };

  Future<void> _set(String key, Object value, {bool native = true}) async {
    switch (value) {
      case bool v:
        await _p.setBool(key, v);
      case int v:
        await _p.setInt(key, v);
      case String v:
        await _p.setString(key, v);
    }
    notifyListeners();
    if (native) onConfigChanged?.call(toConfig());
  }

  Future<void> setOnboarded(bool v) => _set('onboarded', v, native: false);
  Future<void> setPrivacyAccepted(bool v) => _set('privacyAccepted', v, native: false);
  Future<void> setLanguage(String v) => _set('language', v);
  Future<void> setMode(String v) => _set('mode', v);
  Future<void> setBufferSec(int v) => _set('bufferSec', v);
  Future<void> setKeepAudio(String v) => _set('keepAudio', v);
  Future<void> setHudTimeoutSec(int v) => _set('hudTimeoutSec', v);
  Future<void> setReadAloud(bool v) => _set('readAloud', v);
  Future<void> setRetentionDays(int v) => _set('retentionDays', v);
  Future<void> setDiagnosticsUnlocked(bool v) => _set('diagnostics', v, native: false);

  /// After "delete all data": back to first-run state, keeping the language.
  Future<void> resetAll() async {
    final lang = language;
    await _p.clear();
    await _p.setString('language', lang);
    notifyListeners();
    onConfigChanged?.call(toConfig());
  }
}
