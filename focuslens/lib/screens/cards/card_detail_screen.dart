import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';

import '../../app_scope.dart';
import '../../data/cards_repository.dart';
import '../../theme/tokens.dart';
import '../../widgets/common.dart';

/// One card, top to bottom: photo · step(s) · correction · what happened · what was said · audio · actions.
class CardDetailScreen extends StatefulWidget {
  const CardDetailScreen({super.key, required this.cardId});
  final String cardId;

  @override
  State<CardDetailScreen> createState() => _CardDetailScreenState();
}

class _CardDetailScreenState extends State<CardDetailScreen> {
  bool _speaking = false;
  late final _focus = context.app.focus;

  @override
  void initState() {
    super.initState();
    _focus; // capture now; context must not be used in dispose()
  }

  @override
  void dispose() {
    if (_speaking) _focus.stopSpeaking();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final repo = context.app.cards;
    return ListenableBuilder(
      listenable: repo,
      builder: (context, _) {
        final card = repo.byId(widget.cardId);
        if (card == null) {
          return Scaffold(appBar: AppBar(), body: const Center(child: Text('This card is gone.')));
        }
        return Scaffold(
          appBar: AppBar(
            title: Text(clock(card.createdAt), style: TextStyle(fontFamily: kMono, fontSize: 18, color: Tokens.of(context).ink)),
            actions: [
              IconButton(
                tooltip: 'Share',
                icon: const Icon(Icons.ios_share_rounded),
                onPressed: () => context.app.focus.shareCard(card.toShareText(), card.photo),
              ),
              IconButton(
                tooltip: 'Delete',
                icon: const Icon(Icons.delete_outline_rounded),
                onPressed: () => Navigator.pop(context, true),
              ),
            ],
          ),
          body: _body(context, card),
        );
      },
    );
  }

  Widget _body(BuildContext context, CardItem card) {
    final text = Theme.of(context).textTheme;
    final t = Tokens.of(context);
    final app = context.app;
    return ListView(
      padding: const EdgeInsets.fromLTRB(Gap.l, Gap.s, Gap.l, Gap.xxl),
      children: [
        if (card.title.isNotEmpty) ...[
          Text(card.title, style: text.headlineSmall),
          const SizedBox(height: Gap.xs),
        ],
        Text('${modeNames[card.mode] ?? card.mode}${card.late ? ' · answered later' : ''}', style: text.bodySmall),
        const SizedBox(height: Gap.m),

        if (card.photos.isNotEmpty) _Photos(card.photos),

        if (card.pending || card.failed) _StatusPanel(card),

        if (card.steps.isNotEmpty) ...[
          SectionLabel(card.steps.length == 1 ? 'Step' : 'Steps', padding: const EdgeInsets.only(top: Gap.l, bottom: Gap.s)),
          for (final (i, s) in card.steps.indexed)
            Padding(
              padding: const EdgeInsets.only(bottom: Gap.m),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                if (card.steps.length > 1)
                  SizedBox(
                    width: 28,
                    child: Text('${i + 1}', style: TextStyle(fontFamily: kMono, fontSize: 20, color: t.board, height: 1.3)),
                  ),
                Expanded(child: Text(s.text, style: text.headlineSmall)),
              ]),
            ),
        ],

        if (card.correction.isNotEmpty) ...[
          const SectionLabel('Correction', padding: EdgeInsets.only(top: Gap.m, bottom: Gap.s)),
          Text(
            card.correction,
            style: text.bodyLarge!.copyWith(
              decoration: TextDecoration.underline,
              decorationColor: t.marker,
              decorationThickness: 3,
            ),
          ),
        ],

        if (card.summary.isNotEmpty) ...[
          const SectionLabel('What happened', padding: EdgeInsets.only(top: Gap.l, bottom: Gap.s)),
          Prose(card.summary),
        ],

        if (card.transcript.isNotEmpty) ...[
          const SectionLabel('What was said', padding: EdgeInsets.only(top: Gap.l, bottom: Gap.s)),
          Prose(card.transcript, style: text.bodyMedium!.copyWith(color: t.graphite)),
        ],

        if (card.audioPath != null) ...[
          const SectionLabel('Recording', padding: EdgeInsets.only(top: Gap.l, bottom: Gap.xs)),
          _AudioBar(card.audioPath!),
        ],

        const SizedBox(height: Gap.xl),
        if (card.done)
          card.reviewed
              ? OutlinedButton(
                  onPressed: () => app.cards.setReviewed(card.id, false),
                  child: const SizedBox(width: double.infinity, child: Center(child: Text('Mark as not reviewed'))),
                )
              : FilledButton(
                  onPressed: () {
                    app.cards.setReviewed(card.id, true);
                    Navigator.pop(context);
                  },
                  child: const SizedBox(width: double.infinity, child: Center(child: Text('Done'))),
                ),
        if (card.done) ...[
          const SizedBox(height: Gap.s),
          OutlinedButton.icon(
            icon: Icon(_speaking ? Icons.stop_rounded : Icons.volume_up_outlined),
            label: Text(_speaking ? 'Stop reading' : 'Read aloud'),
            onPressed: () {
              if (_speaking) {
                app.focus.stopSpeaking();
              } else {
                app.focus.speak(card.toSpeech());
              }
              setState(() => _speaking = !_speaking);
            },
          ),
        ],
      ],
    );
  }
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel(this.card);
  final CardItem card;

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    final text = Theme.of(context).textTheme;
    final reason = switch (card.error) {
      'bad token' => 'The server refused this app. The build needs the right token.',
      'backend URL/token missing' => 'This build has no server configured.',
      null => null,
      final e => e,
    };
    return Container(
      margin: const EdgeInsets.only(top: Gap.l),
      padding: const EdgeInsets.all(Gap.m),
      decoration: BoxDecoration(
        border: Border.all(color: card.failed ? t.brick : t.rule, width: 1.5),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(
          card.pending
              ? 'Waiting for the server. It sends by itself when there\'s a connection.'
              : 'This card couldn\'t be sent.',
          style: text.bodyLarge,
        ),
        if (card.failed && reason != null) ...[
          const SizedBox(height: Gap.xs),
          Text(reason, style: text.bodySmall),
        ],
        const SizedBox(height: Gap.s),
        TextButton(
          onPressed: () => context.app.cards.retry(card.id),
          child: Text(card.pending ? 'Send now' : 'Try again'),
        ),
      ]),
    );
  }
}

class _Photos extends StatelessWidget {
  const _Photos(this.photos);
  final List<String> photos;

  void _open(BuildContext context, int index) => Navigator.of(context).push(
        MaterialPageRoute(fullscreenDialog: true, builder: (_) => _PhotoViewer(photos, index)),
      );

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      GestureDetector(
        onTap: () => _open(context, 0),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: AspectRatio(
            aspectRatio: 4 / 3,
            child: Image.file(File(photos.first), fit: BoxFit.cover, cacheWidth: 1080,
                semanticLabel: 'Photo from the glasses. Tap to zoom.'),
          ),
        ),
      ),
      if (photos.length > 1)
        Padding(
          padding: const EdgeInsets.only(top: Gap.s),
          child: SizedBox(
            height: 56,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: photos.length,
              separatorBuilder: (_, _) => const SizedBox(width: Gap.s),
              itemBuilder: (context, i) => GestureDetector(
                onTap: () => _open(context, i),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: Image.file(File(photos[i]), width: 74, height: 56, fit: BoxFit.cover, cacheWidth: 222,
                      semanticLabel: 'Photo ${i + 1}'),
                ),
              ),
            ),
          ),
        ),
    ]);
  }
}

class _PhotoViewer extends StatelessWidget {
  const _PhotoViewer(this.photos, this.initial);
  final List<String> photos;
  final int initial;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(backgroundColor: Colors.black, foregroundColor: Colors.white),
      body: PageView(
        controller: PageController(initialPage: initial),
        children: [
          for (final p in photos)
            InteractiveViewer(maxScale: 6, child: Center(child: Image.file(File(p), fit: BoxFit.contain))),
        ],
      ),
    );
  }
}

class _AudioBar extends StatefulWidget {
  const _AudioBar(this.path);
  final String path;

  @override
  State<_AudioBar> createState() => _AudioBarState();
}

class _AudioBarState extends State<_AudioBar> {
  final _player = AudioPlayer();
  Duration _dur = Duration.zero;
  bool _error = false;
  static const _speeds = [1.0, 1.25, 1.5];
  int _speed = 0;

  @override
  void initState() {
    super.initState();
    _player.setFilePath(widget.path).then((d) {
      if (mounted) setState(() => _dur = d ?? Duration.zero);
    }).catchError((_) {
      if (mounted) setState(() => _error = true);
    });
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Tokens.of(context);
    if (_error) return Text('This recording can\'t be played.', style: Theme.of(context).textTheme.bodySmall);
    return StreamBuilder<Duration>(
      stream: _player.positionStream,
      builder: (context, snap) {
        final pos = snap.data ?? Duration.zero;
        final max = _dur.inMilliseconds.toDouble().clamp(1.0, double.infinity);
        return Column(children: [
          Row(children: [
            StreamBuilder<PlayerState>(
              stream: _player.playerStateStream,
              builder: (context, st) {
                final playing = st.data?.playing ?? false;
                final completed = st.data?.processingState == ProcessingState.completed;
                return IconButton.filled(
                  tooltip: playing ? 'Pause' : 'Play',
                  style: IconButton.styleFrom(backgroundColor: t.board, foregroundColor: t.onBoard, minimumSize: const Size(52, 52)),
                  icon: Icon(playing && !completed ? Icons.pause_rounded : Icons.play_arrow_rounded),
                  onPressed: () async {
                    if (completed) await _player.seek(Duration.zero);
                    playing && !completed ? _player.pause() : _player.play();
                  },
                );
              },
            ),
            Expanded(
              child: Slider(
                value: pos.inMilliseconds.toDouble().clamp(0, max),
                max: max,
                onChanged: (v) => _player.seek(Duration(milliseconds: v.toInt())),
                semanticFormatterCallback: (_) => elapsed(pos),
              ),
            ),
            Text('${elapsed(pos)} / ${elapsed(_dur)}', style: TextStyle(fontFamily: kMono, fontSize: 13, color: t.graphite)),
          ]),
          Row(children: [
            if (_dur > const Duration(seconds: 70))
              TextButton(
                onPressed: () {
                  _player.seek(_dur - const Duration(seconds: 60));
                  _player.play();
                },
                child: const Text('Last 60 s'),
              ),
            const Spacer(),
            TextButton(
              onPressed: () {
                setState(() => _speed = (_speed + 1) % _speeds.length);
                _player.setSpeed(_speeds[_speed]);
              },
              child: Text('${_speeds[_speed]}×', semanticsLabel: 'Speed ${_speeds[_speed]}'),
            ),
          ]),
        ]);
      },
    );
  }
}
