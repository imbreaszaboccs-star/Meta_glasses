import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// Plain-language privacy explanation, shown in onboarding and from Settings.
class PrivacyText extends StatelessWidget {
  const PrivacyText({super.key});

  static const points = [
    'Your phone keeps only the last 2.5 minutes of sound, in memory. It is overwritten all the time and nothing is saved until you pinch.',
    'When you pinch, that sound and one photo go to the FocusLens server to get your step. The server doesn\'t keep them.',
    'With Live transcript on (default), speech during a focus block is also sent about once a minute to be turned into short notes. The notes stay in memory on this phone and are gone when the block ends. Turn it off in Settings.',
    'Cards (photo, step and a short recording) stay on this phone. They are deleted after 30 days unless you change it.',
    'Pause listening at any time from the notification. Pausing also wipes the sound in memory.',
    'Recording other people can be restricted. Ask first when you can, and don\'t use FocusLens where recording isn\'t allowed.',
  ];

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final p in points)
        Padding(
          padding: const EdgeInsets.only(bottom: Gap.m),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Padding(
              padding: const EdgeInsets.only(top: 9, right: Gap.m),
              child: Container(width: 6, height: 6, decoration: BoxDecoration(color: t.board, shape: BoxShape.circle)),
            ),
            Expanded(child: Text(p, style: text.bodyLarge)),
          ]),
        ),
    ]);
  }
}

class PrivacyScreen extends StatelessWidget {
  const PrivacyScreen({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Sound and privacy')),
        body: const SafeArea(
          child: SingleChildScrollView(padding: EdgeInsets.all(Gap.l), child: PrivacyText()),
        ),
      );
}
