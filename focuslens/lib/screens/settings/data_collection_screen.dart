import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../theme/tokens.dart';

/// Scripted recording for the activity model: pick what you are doing now, do it, export the files, train on the PC.
/// Only records while "Collect motion data" is on and a focus block is running. Files stay on the phone until exported.
class DataCollectionScreen extends StatefulWidget {
  const DataCollectionScreen({super.key});

  @override
  State<DataCollectionScreen> createState() => _DataCollectionScreenState();
}

class _DataCollectionScreenState extends State<DataCollectionScreen> {
  static const _labels = ['board', 'notes', 'switching', 'still', 'looking_around', 'nod', 'walking'];
  String? _label;
  String? _message;

  Future<void> _set(String? l) async {
    setState(() => _label = l);
    await context.app.focus.setImuLabel(l);
  }

  Future<void> _export() async {
    final r = await context.app.focus.exportImu();
    if (mounted && !r.ok) setState(() => _message = r.error);
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.app.settings;
    final text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Data collection')),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: settings,
          builder: (context, _) => ListView(
            padding: const EdgeInsets.all(Gap.l),
            children: [
              Text(
                settings.collect
                    ? 'Start a focus block, pick what you are doing and do it for a minute or two. Everything is saved on this phone.'
                    : 'Turn on "Collect motion data" in Settings first, then start a focus block.',
                style: text.bodyMedium,
              ),
              const SizedBox(height: Gap.m),
              Text('Now: ${_label ?? 'no label'}', style: text.titleMedium),
              const SizedBox(height: Gap.s),
              Wrap(
                spacing: Gap.s,
                runSpacing: Gap.s,
                children: [
                  for (final l in _labels)
                    ChoiceChip(label: Text(l.replaceAll('_', ' ')), selected: _label == l, onSelected: (_) => _set(l)),
                ],
              ),
              const SizedBox(height: Gap.m),
              OutlinedButton(onPressed: _label == null ? null : () => _set(null), child: const Text('Clear label')),
              const SizedBox(height: Gap.l),
              FilledButton(onPressed: _export, child: const Text('Export')),
              if (_message != null) Padding(padding: const EdgeInsets.only(top: Gap.s), child: Text(_message!, style: text.bodyMedium)),
            ],
          ),
        ),
      ),
    );
  }
}
