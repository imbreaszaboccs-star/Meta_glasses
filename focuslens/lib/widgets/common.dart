import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// Quiet section heading. Sentence case, never all caps.
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key, this.padding = const EdgeInsets.fromLTRB(Gap.m, Gap.l, Gap.m, Gap.s)});
  final String text;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Padding(
        padding: padding,
        child: Semantics(header: true, child: Text(text, style: Theme.of(context).textTheme.titleSmall)),
      );
}

/// "Label ........ value [Fix]". Colour only appears when something is wrong, and the word always says it.
class StatusRow extends StatelessWidget {
  const StatusRow({
    super.key,
    required this.label,
    required this.value,
    this.problem = false,
    this.fixLabel,
    this.onFix,
  });

  final String label, value;
  final bool problem;
  final String? fixLabel;
  final VoidCallback? onFix;

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 48),
      child: Row(
        children: [
          SizedBox(width: 110, child: Text(label, style: text.bodyMedium!.copyWith(color: t.graphite))),
          Expanded(
            child: Text(
              value,
              style: text.bodyMedium!.copyWith(
                color: problem ? t.brick : t.ink,
                fontWeight: problem ? FontWeight.w700 : FontWeight.w400,
              ),
            ),
          ),
          if (fixLabel != null && onFix != null) TextButton(onPressed: onFix, child: Text(fixLabel!)),
        ],
      ),
    );
  }
}

/// The marker-yellow "listening" dot. Breathes slowly, or stays still when animations are off.
class ListeningDot extends StatefulWidget {
  const ListeningDot({super.key, this.active = true, this.size = 10});
  final bool active;
  final double size;

  @override
  State<ListeningDot> createState() => _ListeningDotState();
}

class _ListeningDotState extends State<ListeningDot> with SingleTickerProviderStateMixin {
  late final _c = AnimationController(vsync: this, duration: const Duration(seconds: 2));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(ListeningDot oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync();
  }

  void _sync() {
    final still = MediaQuery.of(context).disableAnimations || !widget.active;
    if (still) {
      _c.stop();
      _c.value = 1;
    } else if (!_c.isAnimating) {
      _c.repeat(reverse: true);
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final color = widget.active ? t.marker : t.graphite;
    return ExcludeSemantics(
      child: AnimatedBuilder(
        animation: _c,
        builder: (_, _) => Container(
          width: widget.size,
          height: widget.size,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: color.withValues(alpha: widget.active ? 0.45 + 0.55 * Curves.easeInOut.transform(_c.value) : 0.6),
          ),
        ),
      ),
    );
  }
}

/// Body paragraph with comfortable measure.
class Prose extends StatelessWidget {
  const Prose(this.text, {super.key, this.style});
  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) => ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Text(text, style: style ?? Theme.of(context).textTheme.bodyLarge),
      );
}

/// Numbered line for onboarding / explanations: mono numeral + text.
class NumberedLine extends StatelessWidget {
  const NumberedLine(this.n, this.text, {super.key});
  final int n;
  final String text;

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: Gap.m),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: 32,
          child: Text('$n', style: TextStyle(fontFamily: kMono, fontSize: 17, color: t.board, fontWeight: FontWeight.w500, height: 1.35)),
        ),
        Expanded(child: Text(text, style: Theme.of(context).textTheme.bodyLarge)),
      ]),
    );
  }
}

/// A bottom sheet with radio choices. Returns the chosen value.
Future<T?> pickOne<T>(BuildContext context, {required String title, required List<(T, String)> options, required T current}) {
  return showModalBottomSheet<T>(
    context: context,
    builder: (context) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(Gap.m, 0, Gap.m, Gap.s),
          child: Text(title, style: Theme.of(context).textTheme.titleLarge),
        ),
        RadioGroup<T>(
          groupValue: current,
          onChanged: (v) => Navigator.pop(context, v),
          child: Column(children: [
            for (final (value, label) in options) RadioListTile<T>(value: value, title: Text(label)),
          ]),
        ),
        const SizedBox(height: Gap.s),
      ]),
    ),
  );
}

String two(int n) => n.toString().padLeft(2, '0');

String clock(DateTime d) => '${d.hour}:${two(d.minute)}';

String elapsed(Duration d) {
  final h = d.inHours, m = d.inMinutes % 60, s = d.inSeconds % 60;
  return h > 0 ? '$h:${two(m)}:${two(s)}' : '${two(m)}:${two(s)}';
}

const modeNames = {'auto': 'Auto', 'lecture': 'Lecture', 'homework': 'Homework', 'desk': 'Desk'};

const modeHelp = {
  'auto': 'Figures out by itself whether you are in a lecture, doing homework or stuck at your desk.',
  'lecture': 'Tells you what was just said and what to write down.',
  'homework': 'Finds the first mistake in your work, or the next small step.',
  'desk': 'Picks the smallest first thing to do when you can\'t get started.',
};

/// Auto / Lecture / Homework / Desk picker.
class ModePicker extends StatelessWidget {
  const ModePicker({super.key, required this.value, required this.onChanged});
  final String value;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: double.infinity,
        child: SegmentedButton<String>(
          showSelectedIcon: false,
          segments: [for (final e in modeNames.entries) ButtonSegment(value: e.key, label: Text(e.value))],
          selected: {value},
          onSelectionChanged: (s) => onChanged(s.first),
        ),
      );
}

const situationNames = {
  'lecture': 'Lecture',
  'homework': 'Homework',
  'desk': 'Desk',
  'reading': 'Reading',
  'other': 'Other',
};

const hintLanguages = [
  ('en', 'English'),
  ('cs', 'Czech'),
  ('sk', 'Slovak'),
  ('de', 'German'),
  ('pl', 'Polish'),
];
