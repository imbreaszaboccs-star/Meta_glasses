/// Typed view of FocusEngine.status() (android/.../focus/FocusEngine.kt).
class FocusStatus {
  const FocusStatus(this._m);

  static const empty = FocusStatus({});

  final Map<String, dynamic> _m;

  Map<String, dynamic> _sub(String k) => (_m[k] as Map?)?.cast<String, dynamic>() ?? const {};

  // permissions
  bool get micPermission => _sub('permissions')['mic'] == true;
  bool get bluetoothPermission => _sub('permissions')['bluetooth'] == true;
  bool get notificationPermission => _sub('permissions')['notifications'] == true;
  bool get batteryUnrestricted => _sub('permissions')['battery'] == true;
  bool get basicPermissions => micPermission && bluetoothPermission && notificationPermission;

  // SDK / registration
  bool get sdkReady => _m['sdkReady'] == true;
  String get registration => _m['registration'] as String? ?? 'UNAVAILABLE';
  bool get registered => registration == 'REGISTERED';
  bool get registering => registration == 'REGISTERING';

  // glasses
  Map<String, dynamic> get _g => _sub('glasses');
  bool get glassesKnown => _g['known'] == true;
  bool get glassesConnected => _g['connected'] == true;
  String? get glassesName => _g['name'] as String?;
  int? get glassesBattery => (_g['battery'] as num?)?.toInt();
  bool get displayCapable => _g['displayCapable'] == true;
  String get cameraPermission => _g['cameraPermission'] as String? ?? 'unknown';
  String get glassesPhase => _g['phase'] as String? ?? 'OFF';
  bool get glassesReady => glassesPhase == 'READY';
  bool get hasDisplay => _g['hasDisplay'] == true;
  bool get hasCamera => _g['hasCamera'] == true;
  bool get updateRequired => _g['updateRequired'] == true;
  bool get gestureTest => _g['testMode'] == true;
  String? get glassesError => _g['error'] as String?;
  bool get compatibilityIssue {
    final c = _g['compatibility'] as String?;
    return c == 'DEVICE_UPDATE_REQUIRED' || c == 'SDK_UPDATE_REQUIRED';
  }

  // focus block
  Map<String, dynamic> get _f => _sub('focus');
  bool get running => _f['running'] == true;
  String get mode => _f['mode'] as String? ?? 'lecture';
  DateTime? get startedAt {
    final ms = (_f['startedAt'] as num?)?.toInt();
    return ms == null ? null : DateTime.fromMillisecondsSinceEpoch(ms);
  }
  int get hintCount => (_f['hintCount'] as num?)?.toInt() ?? 0;
  bool get micPaused => _f['micPaused'] == true;
  bool get phoneOnly => _f['phoneOnly'] == true;

  // mic
  bool get listening => _sub('mic')['listening'] == true;
  int get bufferedSec => (_sub('mic')['bufferedSec'] as num?)?.toInt() ?? 0;
  String? get micError => _sub('mic')['error'] as String?;

  // hint
  Map<String, dynamic> get _h => _sub('hint');
  String get hintPhase => _h['phase'] as String? ?? 'IDLE';
  bool get thinking => hintPhase == 'THINKING';
  String? get shownText => _h['text'] as String?;
  String? get lastText => _h['lastText'] as String?;
  String? get lastCardId => _h['lastCardId'] as String?;
  String? get hudMessage => _h['message'] as String?;

  // server
  bool get serverConfigured => _sub('server')['configured'] == true;
  bool? get serverOk => _sub('server')['ok'] as bool?;
  String? get serverError => _sub('server')['error'] as String?;

  int get bufferSecConfig => (_sub('config')['bufferSec'] as num?)?.toInt() ?? 180;
}
