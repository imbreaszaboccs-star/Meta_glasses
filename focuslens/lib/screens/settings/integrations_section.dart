import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../theme/tokens.dart';

/// Todoist + Notion. The tokens go straight to the native encrypted store; Dart only ever sees "•••• last4".
class IntegrationsSection extends StatefulWidget {
  const IntegrationsSection({super.key});

  @override
  State<IntegrationsSection> createState() => _IntegrationsSectionState();
}

class _IntegrationsSectionState extends State<IntegrationsSection> {
  Map<String, dynamic> _status = const {};

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final s = await context.app.focus.integrationStatus();
    if (mounted) setState(() => _status = s);
  }

  Map get _todoist => (_status['todoist'] as Map?) ?? const {};
  Map get _notion => (_status['notion'] as Map?) ?? const {};

  void _say(String m) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));

  Future<void> _connect(String kind) async {
    final r = await showDialog<(String, String)>(context: context, builder: (_) => _TokenDialog(notion: kind == 'notion'));
    if (r == null || !mounted) return;
    final res = await context.app.focus.integrationSave(kind, r.$1, database: r.$2);
    if (!mounted) return;
    await _refresh();
    if (!res.ok) return _say(res.error ?? 'Could not save.');
    await _test(kind);
  }

  Future<void> _test(String kind) async {
    _say('Testing…');
    final res = await context.app.focus.integrationTest(kind);
    if (mounted) _say(res.ok ? 'Connected' : (res.error ?? 'Test failed'));
  }

  Future<void> _clear(String kind) async {
    await context.app.focus.integrationClear(kind);
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final t = Tokens.of(context);
    Widget row(String name, String kind, Map s, String hint) {
      final on = s['configured'] == true;
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        ListTile(
          title: Text(name),
          subtitle: Text(on ? '${s['masked']}${kind == 'notion' ? ' · database ${_short('${s['database']}')}' : ''}' : hint),
          trailing: TextButton(onPressed: () => _connect(kind), child: Text(on ? 'Change' : 'Connect')),
        ),
        if (on)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: Gap.m),
            child: Row(children: [
              TextButton(onPressed: () => _test(kind), child: const Text('Test')),
              TextButton(
                onPressed: () => _clear(kind),
                style: TextButton.styleFrom(foregroundColor: t.brick),
                child: const Text('Clear'),
              ),
            ]),
          ),
      ]);
    }

    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      row('Todoist', 'todoist', _todoist,
          'Notes from the glasses become tasks. Token: Todoist → Settings → Integrations → Developer.'),
      row('Notion', 'notion', _notion,
          'Export cards as a page. Text only, photos are not sent. Share the database with your integration (… → Connections).'),
      Padding(
        padding: const EdgeInsets.fromLTRB(Gap.m, Gap.s, Gap.m, 0),
        child: Text('Tokens stay on this phone, encrypted.', style: text.bodySmall),
      ),
    ]);
  }

  static String _short(String id) => id.length > 8 ? '${id.substring(0, 4)}…${id.substring(id.length - 4)}' : id;
}

class _TokenDialog extends StatefulWidget {
  const _TokenDialog({required this.notion});
  final bool notion;

  @override
  State<_TokenDialog> createState() => _TokenDialogState();
}

class _TokenDialogState extends State<_TokenDialog> {
  final _token = TextEditingController();
  final _db = TextEditingController();

  @override
  void dispose() {
    _token.dispose();
    _db.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.notion ? 'Connect Notion' : 'Connect Todoist'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(
          controller: _token,
          autofocus: true,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(labelText: widget.notion ? 'Integration secret' : 'API token'),
        ),
        if (widget.notion) ...[
          const SizedBox(height: Gap.m),
          TextField(
            controller: _db,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'Database link or ID'),
          ),
          const SizedBox(height: Gap.s),
          Text('Share the database with the integration first: … → Connections.', style: Theme.of(context).textTheme.bodySmall),
        ],
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        TextButton(onPressed: () => Navigator.pop(context, (_token.text, _db.text)), child: const Text('Save')),
      ],
    );
  }
}
