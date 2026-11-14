import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app_scope.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';

/// Hidden screen (Settings → tap Version 5×): the old Gesture Lab log, latency, server check.
class DiagnosticsScreen extends StatefulWidget {
  const DiagnosticsScreen({super.key});

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen> {
  Map<String, dynamic> _d = const {};
  final _log = <Map<String, dynamic>>[];
  StreamSubscription? _sub;
  late final _focus = context.app.focus;

  @override
  void initState() {
    super.initState();
    _load();
    _sub = _focus.events.where((e) => e['type'] == 'diag').listen((e) {
      setState(() {
        _log.insert(0, e);
        if (_log.length > 400) _log.removeLast();
      });
    });
  }

  Future<void> _load() async {
    final d = await _focus.diagnostics();
    if (!mounted) return;
    setState(() {
      _d = d;
      _log
        ..clear()
        ..addAll(((d['log'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList().reversed);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  String _line(Map<String, dynamic> e) {
    final ts = DateTime.fromMillisecondsSinceEpoch((e['ts'] as num).toInt());
    return '${two(ts.hour)}:${two(ts.minute)}:${two(ts.second)} [${e['kind']}] ${e['text']}';
  }

  static int? _pct(List<int> v, double p) {
    if (v.isEmpty) return null;
    final s = [...v]..sort();
    return s[((s.length - 1) * p).round()];
  }

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    final mono = TextStyle(fontFamily: kMono, fontSize: 12, color: t.ink, height: 1.4);
    final lat = ((_d['latencies'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final totals = [for (final l in lat) if (l['ok'] == true) (l['totalMs'] as num).toInt()];
    final s = _focus.status.value;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagnostics'),
        actions: [
          IconButton(
            tooltip: 'Copy log',
            icon: const Icon(Icons.copy_rounded),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: _log.reversed.map(_line).join('\n')));
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Log copied')));
            },
          ),
          IconButton(tooltip: 'Refresh', icon: const Icon(Icons.refresh_rounded), onPressed: _load),
        ],
      ),
      body: ListView(padding: const EdgeInsets.only(bottom: Gap.xl), children: [
        const SectionLabel('Server'),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: Gap.m),
          child: Column(children: [
            StatusRow(label: 'URL', value: '${_d['serverUrl'] ?? '–'}'),
            StatusRow(label: 'Token', value: _d['configured'] == true ? 'set' : 'missing', problem: _d['configured'] != true),
            StatusRow(
              label: 'Health',
              value: switch (s.serverOk) { true => 'ok', false => 'failed: ${s.serverError ?? ''}', null => 'not checked' },
              problem: s.serverOk == false,
              fixLabel: 'Check',
              onFix: () async {
                await _focus.checkServer();
                await Future.delayed(const Duration(seconds: 2));
                if (mounted) setState(() {});
              },
            ),
            StatusRow(label: 'Meta SDK', value: '${_d['sdkReady'] == true ? 'ready' : 'not ready'} · dev mode ${_d['devMode'] == true ? 'on' : 'off'}'),
            StatusRow(label: 'Session', value: '${s.registration.toLowerCase()} · ${s.glassesPhase.toLowerCase()}'),
          ]),
        ),
        const SectionLabel('Latency, last 20 steps'),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: Gap.m),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(
              totals.isEmpty ? 'No steps yet.' : 'p50 ${_pct(totals, .5)} ms · p90 ${_pct(totals, .9)} ms · target < 4000 ms',
              style: text.bodyMedium,
            ),
            const SizedBox(height: Gap.s),
            Text('photo · ready · AI · total · audio', style: mono.copyWith(color: t.graphite)),
            for (final l in lat.reversed)
              Text(
                '${l['photoMs'] ?? '–'} · ${l['encodeMs'] ?? '–'} · ${l['aiMs'] ?? '–'} · ${l['totalMs'] ?? '–'}'
                ' · ${((l['audioBytes'] as num? ?? 0) / 1024).round()} kB ${(l['audioMime'] as String? ?? '').replaceFirst('audio/', '')}'
                '${l['ok'] == true ? '' : ' · failed'}',
                style: mono,
              ),
          ]),
        ),
        const SectionLabel('Log'),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: Gap.m),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            for (final e in _log.take(300))
              Text(
                _line(e),
                style: mono.copyWith(color: e['kind'] == 'error' ? t.brick : (e['kind'] == 'input' ? t.board : t.ink)),
              ),
          ]),
        ),
      ]),
    );
  }
}
