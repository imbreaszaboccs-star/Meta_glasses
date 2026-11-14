import 'package:flutter/material.dart';

/// "Study desk" palette (task.md §6): paper, ink, chalkboard, one highlighter.
/// The marker yellow is for the listening dot, the correction underline and focus rings only.
@immutable
class Tokens extends ThemeExtension<Tokens> {
  const Tokens({
    required this.paper,
    required this.surface,
    required this.ink,
    required this.graphite,
    required this.rule,
    required this.board,
    required this.onBoard,
    required this.marker,
    required this.brick,
  });

  final Color paper, surface, ink, graphite, rule, board, onBoard, marker, brick;

  static const light = Tokens(
    paper: Color(0xFFF3EFE6),
    surface: Color(0xFFFBF9F4),
    ink: Color(0xFF1D1C1A),
    graphite: Color(0xFF5F5B54),
    rule: Color(0xFFDDD6C8),
    board: Color(0xFF2E4A3B),
    onBoard: Color(0xFFF3EFE6),
    marker: Color(0xFFE9C46A),
    brick: Color(0xFFA8432F),
  );

  static const dark = Tokens(
    paper: Color(0xFF161917),
    surface: Color(0xFF1F2420),
    ink: Color(0xFFE8E4DA),
    graphite: Color(0xFFA39E93),
    rule: Color(0xFF2F3530),
    board: Color(0xFF7FA78F),
    onBoard: Color(0xFF101311),
    marker: Color(0xFFD8B65C),
    brick: Color(0xFFE07A62),
  );

  static Tokens of(BuildContext context) => Theme.of(context).extension<Tokens>()!;

  @override
  Tokens copyWith() => this;

  @override
  Tokens lerp(Tokens? other, double t) {
    if (other == null) return this;
    Color l(Color a, Color b) => Color.lerp(a, b, t)!;
    return Tokens(
      paper: l(paper, other.paper),
      surface: l(surface, other.surface),
      ink: l(ink, other.ink),
      graphite: l(graphite, other.graphite),
      rule: l(rule, other.rule),
      board: l(board, other.board),
      onBoard: l(onBoard, other.onBoard),
      marker: l(marker, other.marker),
      brick: l(brick, other.brick),
    );
  }
}

const kSans = 'Atkinson';
const kMono = 'PlexMono';

/// Spacing scale.
abstract final class Gap {
  static const xs = 4.0, s = 8.0, m = 16.0, l = 24.0, xl = 32.0, xxl = 48.0;
}
