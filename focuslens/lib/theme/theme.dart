import 'package:flutter/material.dart';

import 'tokens.dart';

ThemeData buildTheme(Brightness brightness) {
  final t = brightness == Brightness.light ? Tokens.light : Tokens.dark;
  final scheme = ColorScheme(
    brightness: brightness,
    primary: t.board,
    onPrimary: t.onBoard,
    secondary: t.board,
    onSecondary: t.onBoard,
    error: t.brick,
    onError: t.paper,
    surface: t.paper,
    onSurface: t.ink,
    surfaceContainerLowest: t.paper,
    surfaceContainerLow: t.surface,
    surfaceContainer: t.surface,
    surfaceContainerHigh: t.surface,
    surfaceContainerHighest: t.surface,
    onSurfaceVariant: t.graphite,
    outline: t.rule,
    outlineVariant: t.rule,
    primaryContainer: t.board,
    onPrimaryContainer: t.onBoard,
    secondaryContainer: t.rule,
    onSecondaryContainer: t.ink,
    surfaceTint: Colors.transparent,
  );

  TextStyle s(double size, {FontWeight w = FontWeight.w400, Color? c, double h = 1.35}) =>
      TextStyle(fontFamily: kSans, fontSize: size, fontWeight: w, color: c ?? t.ink, height: h);

  final text = TextTheme(
    displaySmall: s(30, w: FontWeight.w700, h: 1.2),
    headlineMedium: s(26, w: FontWeight.w700, h: 1.3), // the step text
    headlineSmall: s(22, w: FontWeight.w700, h: 1.3),
    titleLarge: s(20, w: FontWeight.w700),
    titleMedium: s(17, w: FontWeight.w700),
    titleSmall: s(15, w: FontWeight.w700, c: t.graphite),
    bodyLarge: s(17),
    bodyMedium: s(16),
    bodySmall: s(13, c: t.graphite),
    labelLarge: s(16, w: FontWeight.w700, h: 1.2),
    labelMedium: s(14, w: FontWeight.w700, h: 1.2),
    labelSmall: s(12, c: t.graphite, h: 1.2),
  );

  final shape6 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(6));
  const minTap = Size(56, 56);

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: t.paper,
    canvasColor: t.paper,
    fontFamily: kSans,
    textTheme: text,
    extensions: [t],
    splashFactory: InkRipple.splashFactory,
    dividerTheme: DividerThemeData(color: t.rule, thickness: 1, space: 1),
    appBarTheme: AppBarTheme(
      backgroundColor: t.paper,
      foregroundColor: t.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: text.titleLarge,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: t.board,
        foregroundColor: t.onBoard,
        minimumSize: const Size(64, 64),
        shape: shape6,
        textStyle: text.labelLarge,
        elevation: 0,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: t.ink,
        minimumSize: minTap,
        side: BorderSide(color: t.rule, width: 1.5),
        shape: shape6,
        textStyle: text.labelLarge,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: t.board,
        minimumSize: const Size(48, 48),
        shape: shape6,
        textStyle: text.labelMedium,
      ),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        minimumSize: const WidgetStatePropertyAll(Size(48, 52)),
        shape: WidgetStatePropertyAll(shape6),
        side: WidgetStatePropertyAll(BorderSide(color: t.rule, width: 1.5)),
        textStyle: WidgetStatePropertyAll(text.labelMedium),
        backgroundColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.board : null),
        foregroundColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.onBoard : t.ink),
        iconColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.onBoard : t.ink),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: t.paper,
      surfaceTintColor: Colors.transparent,
      indicatorColor: t.rule,
      height: 68,
      elevation: 0,
      labelTextStyle: WidgetStatePropertyAll(text.labelSmall!.copyWith(color: t.ink, fontWeight: FontWeight.w700)),
      iconTheme: WidgetStatePropertyAll(IconThemeData(color: t.ink, size: 24)),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.onBoard : t.graphite),
      trackColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.board : t.rule),
      trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
    ),
    radioTheme: RadioThemeData(fillColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.board : t.graphite)),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith((st) => st.contains(WidgetState.selected) ? t.board : Colors.transparent),
      checkColor: WidgetStatePropertyAll(t.onBoard),
      side: BorderSide(color: t.graphite, width: 1.5),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(3)),
    ),
    listTileTheme: ListTileThemeData(
      minVerticalPadding: 12,
      contentPadding: const EdgeInsets.symmetric(horizontal: Gap.m),
      titleTextStyle: text.bodyLarge,
      subtitleTextStyle: text.bodySmall,
      iconColor: t.graphite,
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: t.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: t.rule,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(10))),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: t.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      titleTextStyle: text.titleLarge,
      contentTextStyle: text.bodyMedium,
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: t.ink,
      contentTextStyle: text.bodyMedium!.copyWith(color: t.paper),
      actionTextColor: t.marker,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
    ),
    sliderTheme: SliderThemeData(activeTrackColor: t.board, inactiveTrackColor: t.rule, thumbColor: t.board, trackHeight: 3),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: t.board, linearTrackColor: t.rule),
    inputDecorationTheme: InputDecorationTheme(
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: BorderSide(color: t.rule)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: BorderSide(color: t.board, width: 2)),
    ),
    focusColor: t.marker.withValues(alpha: 0.35),
    pageTransitionsTheme: const PageTransitionsTheme(builders: {
      TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
    }),
  );
}
