import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MaterialApp(home: GestureLab(), debugShowCheckedModeBanner: false));

/// Spike: loguje všechno, co přijde z Meta brýlí a Neural Band (viz android/.../GlassesBridge.kt).
class GestureLab extends StatefulWidget {
  const GestureLab({super.key});
  @override
  State<GestureLab> createState() => _GestureLabState();
}

class _LogLine {
  _LogLine(this.kind, this.text, this.ts);
  final String kind, text;
  final DateTime ts;
  @override
  String toString() => '${ts.toIso8601String().substring(11, 23)} [$kind] $text';
}

class _GestureLabState extends State<GestureLab> {
  static const _m = MethodChannel('focuslens/glasses');
  static const _events = EventChannel('focuslens/glasses/events');

  final _log = <_LogLine>[];
  final _status = <String, String>{}; // poslední hodnota pro registration/session/display/inputs
  final _counts = <String, int>{}; // NAV / SELECT / ... / HUD
  bool _hud = true;
  String? _photoPath, _photoInfo;

  @override
  void initState() {
    super.initState();
    _events.receiveBroadcastStream().listen((e) {
      final m = Map<String, dynamic>.from(e as Map);
      final line = _LogLine(m['kind'], m['text'], DateTime.fromMillisecondsSinceEpoch(m['ts']));
      setState(() {
        _log.insert(0, line);
        if (_log.length > 1000) _log.removeLast();
        if (const {'registration', 'session', 'display', 'inputs'}.contains(line.kind)) {
          _status[line.kind] = line.text;
        }
        final key = line.kind == 'input' ? line.text.split(' ').first : (line.kind == 'photo' ? 'FOTO' : null);
        if (key != null) _counts[key] = (_counts[key] ?? 0) + 1;
        if (line.kind == 'photo') {
          _photoPath = m['path'];
          _photoInfo = line.text;
        }
      });
    });
    _m.invokeMethod('init');
  }

  Future<void> _call(String method, [Object? args]) => _m.invokeMethod(method, args);

  Color _color(String kind) => switch (kind) {
        'input' => Colors.green.shade700,
        'photo' || 'camera' => Colors.purple,
        'error' => Colors.red,
        'session' || 'display' || 'inputs' => Colors.blue,
        _ => Colors.grey.shade700,
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('FocusLens · Gesture Lab'),
        actions: [
          IconButton(
            tooltip: 'Kopírovat log',
            icon: const Icon(Icons.copy),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: _log.reversed.join('\n')));
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Log zkopírován')));
            },
          ),
          IconButton(
            tooltip: 'Vymazat',
            icon: const Icon(Icons.delete_outline),
            onPressed: () => setState(() {
              _log.clear();
              _counts.clear();
            }),
          ),
        ],
      ),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Wrap(spacing: 8, runSpacing: 8, children: [
            for (final k in ['registration', 'session', 'display', 'inputs'])
              Chip(label: Text('$k: ${_status[k] ?? '–'}')),
          ]),
        ),
        Wrap(spacing: 8, runSpacing: 8, alignment: WrapAlignment.center, children: [
          FilledButton.tonal(onPressed: () => _call('register'), child: const Text('1. Registrovat')),
          FilledButton(
            onPressed: () => _call('startSession', {'display': _hud}),
            child: const Text('2. Start session'),
          ),
          FilledButton.icon(
            onPressed: () => _call('help'),
            icon: const Icon(Icons.camera_alt),
            label: const Text('Pomoc'),
          ),
          OutlinedButton(onPressed: () => _call('stopSession'), child: const Text('Stop')),
          TextButton(onPressed: () => _call('unregister'), child: const Text('Odregistrovat')),
        ]),
        SwitchListTile(
          dense: true,
          title: const Text('Použít HUD (display session)'),
          value: _hud,
          onChanged: (v) => setState(() => _hud = v),
        ),
        if (_photoPath != null)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.file(File(_photoPath!), height: 140, gaplessPlayback: true),
              ),
              const SizedBox(width: 12),
              Expanded(child: Text(_photoInfo ?? '', style: const TextStyle(fontSize: 13))),
            ]),
          ),
        if (_counts.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Wrap(spacing: 8, children: [
              for (final e in _counts.entries)
                Chip(backgroundColor: Colors.green.shade50, label: Text('${e.key}: ${e.value}')),
            ]),
          ),
        const Divider(),
        Expanded(
          child: ListView.builder(
            itemCount: _log.length,
            itemBuilder: (_, i) {
              final l = _log[i];
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
                child: Text(l.toString(),
                    style: TextStyle(fontFamily: 'monospace', fontSize: 12, color: _color(l.kind))),
              );
            },
          ),
        ),
      ]),
    );
  }
}
