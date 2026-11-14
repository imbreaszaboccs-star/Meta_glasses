import 'package:flutter/material.dart';

import '../../app_scope.dart';
import '../../data/focus_status.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';
import 'glasses_setup.dart';
import 'privacy_text.dart';

/// First run (task.md T2.1). About two minutes; every step can be skipped and done later from Settings.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _pages = PageController();
  int _page = 0;
  static const _count = 6;

  void _next() {
    if (_page < _count - 1) {
      _pages.nextPage(duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
    }
  }

  void _skipPinch() => _pages.animateToPage(_page + 2, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);

  void _back() => _pages.animateToPage(_page == 5 && _skippedPinch ? 3 : _page - 1,
      duration: const Duration(milliseconds: 200), curve: Curves.easeOut);

  bool _skippedPinch = false;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    return PopScope(
      canPop: _page == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: Scaffold(
        body: SafeArea(
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(Gap.s, Gap.s, Gap.l, 0),
              child: Row(children: [
                if (_page > 0)
                  IconButton(onPressed: _back, tooltip: 'Back', icon: const Icon(Icons.arrow_back_rounded))
                else
                  const SizedBox(width: 48, height: 48),
                const Spacer(),
                Text('${_page + 1} / $_count', style: TextStyle(fontFamily: kMono, fontSize: 14, color: t.graphite)),
              ]),
            ),
            Expanded(
              child: PageView(
                controller: _pages,
                physics: const NeverScrollableScrollPhysics(),
                onPageChanged: (p) => setState(() => _page = p),
                children: [
                  _Intro(onNext: _next),
                  _Privacy(onNext: _next),
                  _Permissions(onNext: _next),
                  _Page(
                    title: 'Connect your glasses',
                    footer: _Footer(primary: 'Continue', onPrimary: () { _skippedPinch = false; _next(); }, secondary: 'Set up later', onSecondary: () { _skippedPinch = true; _skipPinch(); }),
                    child: const GlassesSetup(),
                  ),
                  _Page(
                    title: 'Try a pinch',
                    footer: _Footer(primary: 'Continue', onPrimary: _next, secondary: 'Skip for now', onSecondary: _next),
                    child: GestureTest(active: _page == 4),
                  ),
                  const _Defaults(),
                ],
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

/// Page frame: title, scrollable body, fixed footer.
class _Page extends StatelessWidget {
  const _Page({required this.title, required this.child, required this.footer});
  final String title;
  final Widget child;
  final Widget footer;

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      Expanded(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(Gap.l, Gap.l, Gap.l, Gap.l),
          children: [
            Semantics(header: true, child: Text(title, style: Theme.of(context).textTheme.displaySmall)),
            const SizedBox(height: Gap.l),
            child,
          ],
        ),
      ),
      Padding(padding: const EdgeInsets.fromLTRB(Gap.l, 0, Gap.l, Gap.l), child: footer),
    ]);
  }
}

class _Footer extends StatelessWidget {
  const _Footer({required this.primary, required this.onPrimary, this.secondary, this.onSecondary});
  final String primary;
  final VoidCallback? onPrimary;
  final String? secondary;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) => Column(mainAxisSize: MainAxisSize.min, children: [
        FilledButton(
          onPressed: onPrimary,
          child: SizedBox(width: double.infinity, child: Center(child: Text(primary))),
        ),
        if (secondary != null)
          Padding(
            padding: const EdgeInsets.only(top: Gap.xs),
            child: SizedBox(width: double.infinity, child: TextButton(onPressed: onSecondary, child: Text(secondary!))),
          ),
      ]);
}

// 1 ---------------------------------------------------------------- what it does

class _Intro extends StatelessWidget {
  const _Intro({required this.onNext});
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) => _Page(
        title: 'One next step.\nNo phone in your hand.',
        footer: _Footer(primary: 'Continue', onPrimary: onNext),
        child: const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          NumberedLine(1, 'Pinch your fingers when you lose the thread or get stuck.'),
          NumberedLine(2, 'The glasses take one photo, and your phone adds the last 2.5 minutes of sound.'),
          NumberedLine(3, 'You get one short next step on the glasses. The details wait in Cards for later.'),
        ]),
      );
}

// 2 ---------------------------------------------------------------- privacy

class _Privacy extends StatefulWidget {
  const _Privacy({required this.onNext});
  final VoidCallback onNext;

  @override
  State<_Privacy> createState() => _PrivacyState();
}

class _PrivacyState extends State<_Privacy> {
  late bool _ok = context.app.settings.privacyAccepted;

  @override
  Widget build(BuildContext context) => _Page(
        title: 'The sound around you',
        footer: _Footer(
          primary: 'Continue',
          onPrimary: _ok
              ? () {
                  context.app.settings.setPrivacyAccepted(true);
                  widget.onNext();
                }
              : null,
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const PrivacyText(),
          const SizedBox(height: Gap.m),
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            value: _ok,
            onChanged: (v) => setState(() => _ok = v ?? false),
            title: const Text('I understand, and I\'ll respect places where recording isn\'t allowed.'),
          ),
        ]),
      );
}

// 3 ---------------------------------------------------------------- permissions

class _Permissions extends StatelessWidget {
  const _Permissions({required this.onNext});
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    final app = context.app;
    return ValueListenableBuilder<FocusStatus>(
      valueListenable: app.focus.status,
      builder: (context, s, _) {
        final allBasic = s.basicPermissions;
        return _Page(
          title: 'Permissions',
          footer: _Footer(
            primary: 'Continue',
            onPrimary: onNext,
            secondary: allBasic && s.batteryUnrestricted ? null : 'Skip for now',
            onSecondary: onNext,
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _PermissionLine('Microphone', 'Keeps the last 2.5 minutes of sound in memory.', s.micPermission),
            _PermissionLine('Notifications', 'Shows that FocusLens is listening, with Pause and End.', s.notificationPermission),
            _PermissionLine('Bluetooth', 'Talks to your glasses.', s.bluetoothPermission),
            if (!allBasic)
              Padding(
                padding: const EdgeInsets.only(bottom: Gap.l),
                child: Row(children: [
                  OutlinedButton(onPressed: app.focus.requestPermissions, child: const Text('Allow')),
                  const SizedBox(width: Gap.s),
                  TextButton(onPressed: app.focus.openAppSettings, child: const Text('Open settings')),
                ]),
              ),
            const Divider(),
            const SizedBox(height: Gap.m),
            _PermissionLine('Run in the background', 'Keeps a focus block going with the screen off.', s.batteryUnrestricted),
            if (!s.batteryUnrestricted)
              OutlinedButton(onPressed: app.focus.openBatterySettings, child: const Text('Allow')),
          ]),
        );
      },
    );
  }
}

class _PermissionLine extends StatelessWidget {
  const _PermissionLine(this.title, this.why, this.granted);
  final String title, why;
  final bool granted;

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: Gap.m),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Icon(granted ? Icons.check_rounded : Icons.radio_button_unchecked_rounded,
              color: granted ? t.board : t.graphite, size: 22, semanticLabel: granted ? 'Allowed' : 'Not allowed yet'),
        ),
        const SizedBox(width: Gap.m),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: text.titleMedium),
            Text(why, style: text.bodyMedium!.copyWith(color: t.graphite)),
          ]),
        ),
      ]),
    );
  }
}

// 6 ---------------------------------------------------------------- defaults

class _Defaults extends StatelessWidget {
  const _Defaults();

  @override
  Widget build(BuildContext context) {
    final settings = context.app.settings;
    final text = Theme.of(context).textTheme;
    return ListenableBuilder(
      listenable: settings,
      builder: (context, _) => _Page(
        title: 'Last thing',
        footer: _Footer(primary: 'Done', onPrimary: () => settings.setOnboarded(true)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Language of the steps', style: text.titleMedium),
          Text('Pick the language of your lectures and notes.', style: text.bodySmall),
          RadioGroup<String>(
            groupValue: settings.language,
            onChanged: (v) => settings.setLanguage(v!),
            child: Column(children: [
              for (final (code, name) in hintLanguages)
                RadioListTile<String>(contentPadding: EdgeInsets.zero, value: code, title: Text(name)),
            ]),
          ),
          const SizedBox(height: Gap.m),
          Text('You can change all of this later in Settings.', style: text.bodySmall),
        ]),
      ),
    );
  }
}
