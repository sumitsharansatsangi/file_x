import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:storax/storax.dart';

void main() {
  runApp(const StoraxExampleApp());
}

/* ─────────────────────────────────────────────
 * APP
 * ───────────────────────────────────────────── */

class StoraxExampleApp extends StatelessWidget {
  const StoraxExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true),
      home: const RootsPage(),
    );
  }
}

/* ─────────────────────────────────────────────
 * ROOTS PAGE
 * ───────────────────────────────────────────── */

class RootsPage extends StatefulWidget {
  const RootsPage({super.key});

  @override
  State<RootsPage> createState() => _RootsPageState();
}

class _RootsPageState extends State<RootsPage> with WidgetsBindingObserver {
  final storax = Storax();
  List<StoraxVolume> roots = [];
  bool loading = true;
  late final StreamSubscription<StoraxEvent> _sub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sub = storax.events.listen(_handleEvent);
    _init();
  }

  void _handleEvent(StoraxEvent event) async {
    switch (event.type) {
      case StoraxEventType.usbAttached:
        await _refreshRoots();
        break;
      case StoraxEventType.usbDetached:
      case StoraxEventType.safPicked:
        await _refreshRoots();
        break;
      case StoraxEventType.transferProgress:
        _handleTransferProgress(event);
        break;
    }
  }

  void _handleTransferProgress(StoraxEvent event) {
    final payload = event.payload;
    if (payload is! Map) return;

    final bool done = payload['done'] == true;
    final String? error = payload['error'] as String?;

    if (!mounted) return;

    if (error != null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Operation failed: $error')));
      return;
    }

    if (done) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Operation completed')));
      _refreshRoots();
    }
  }

  Future<void> _init() async {
    await _ensurePermission();
    await _refreshRoots();
  }

  Future<void> _ensurePermission() async {
    if (await storax.hasAllFilesAccess()) return;
    await storax.requestAllFilesAccess();
  }

  Future<void> _refreshRoots() async {
    final data = await storax.getAllRoots();
    if (!mounted) return;
    setState(() {
      roots = data;
      loading = false;
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Storage'),
        actions: [
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Trash',
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => TrashPage()),
              );
            },
          ),
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: 'Pick SAF folder',
            onPressed: () async {
              await storax.openSafFolderPicker();
              await _refreshRoots();
            },
          ),
        ],
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : ListView.builder(
              itemCount: roots.length,
              itemBuilder: (_, i) {
                final r = roots[i];
                final isSaf = r.mode == StoraxMode.saf;
                return ListTile(
                  leading: Icon(isSaf ? Icons.lock : Icons.storage),
                  title: Text(r.name),
                  subtitle: Text(isSaf ? 'SAF folder' : r.path ?? ''),
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => FileBrowserPage(
                          initialTarget: (isSaf ? r.uri : r.path)!,
                          isSaf: isSaf,
                          title: r.name,
                        ),
                      ),
                    );
                  },
                );
              },
            ),
    );
  }
}

/* ─────────────────────────────────────────────
 * FILE BROWSER PAGE
 * ───────────────────────────────────────────── */

class FileBrowserPage extends StatefulWidget {
  final String initialTarget;
  final bool isSaf;
  final String title;

  const FileBrowserPage({
    super.key,
    required this.initialTarget,
    required this.isSaf,
    required this.title,
  });

  @override
  State<FileBrowserPage> createState() => _FileBrowserPageState();
}

class _FileBrowserPageState extends State<FileBrowserPage> {
  final storax = Storax();
  final List<String> pathStack = [];
  List<StoraxEntry> entries = [];

  bool gridView = true;
  String search = '';

  late final StreamSubscription<StoraxEvent> _sub;

  @override
  void initState() {
    super.initState();
    pathStack.add(widget.initialTarget);
    _load();

    _sub = storax.events.listen((e) {
      if (e.type == StoraxEventType.transferProgress &&
          e.payload is Map &&
          e.payload['done'] == true) {
        _load();
      }
    });
  }

  @override
  void dispose() {
    _sub.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final data = await storax.listDirectory(
      target: pathStack.last,
      isSaf: widget.isSaf,
    );
    if (!mounted) return;
    setState(() {
      entries = data
          .where((e) => e.name.toLowerCase().contains(search.toLowerCase()))
          .toList();
    });
  }

  Future<void> _createFolder() async {
    final name = await _askText('Folder name');
    if (name == null || name.isEmpty) return;

    await storax.createFolder(
      parent: pathStack.last,
      name: name,
      isSaf: widget.isSaf,
    );
    _load();
  }

  Future<void> _createFile() async {
    final name = await _askText('File name (example.txt)');
    if (name == null || name.isEmpty) return;

    await storax.createFile(
      parent: pathStack.last,
      name: name,
      isSaf: widget.isSaf,
    );
    _load();
  }

  Future<String?> _askText(String title) {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: TextField(controller: controller),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  Future<void> _open(StoraxEntry e) async {
    if (e.isDirectory) {
      pathStack.add((widget.isSaf ? e.uri : e.path)!);
      _load();
    } else {
      await storax.openFile(path: e.path ?? e.uri ?? "", mime: e.mime);
    }
  }

  void _showActions(StoraxEntry e) {
    final isSaf = widget.isSaf;

    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _action(Icons.drive_file_rename_outline, 'Rename', () async {
              final n = await _askText('New name');
              if (n == null) return;
              await storax.rename(
                target: (isSaf ? e.uri : e.path)!,
                newName: n,
                isSaf: isSaf,
              );
            }),
            _action(Icons.copy, 'Copy', () async {
              final dst = await _askText('Destination path');
              if (dst == null) return;
              await storax.copy(
                source: (isSaf ? e.uri : e.path)!,
                destination: dst,
                isSaf: isSaf,
              );
            }),
            _action(Icons.drive_file_move, 'Move', () async {
              final dst = await _askText('Destination path');
              if (dst == null) return;
              await storax.move(
                source: (isSaf ? e.uri : e.path)!,
                destination: dst,
                isSaf: isSaf,
              );
            }),
            _action(Icons.delete_forever, 'Delete', () async {
              await storax.delete(
                target: (isSaf ? e.uri : e.path)!,
                isSaf: isSaf,
              );
              _load();
            }),
            _action(Icons.delete, 'Move to trash', () async {
              await storax.moveToTrash(
                target: (isSaf ? e.uri : e.path)!,
                isSaf: isSaf,
                safRootUri: isSaf ? widget.initialTarget : null,
              );
              if (mounted) {
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('Moved to Trash')));
                _load();
              }
            }),
          ],
        ),
      ),
    );
  }

  ListTile _action(IconData icon, String text, VoidCallback onTap) {
    return ListTile(
      leading: Icon(icon),
      title: Text(text),
      onTap: () {
        Navigator.pop(context);
        onTap();
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
        actions: [
          IconButton(
            icon: const Icon(Icons.create_new_folder),
            onPressed: _createFolder,
          ),
          IconButton(icon: const Icon(Icons.note_add), onPressed: _createFile),
          IconButton(
            icon: Icon(gridView ? Icons.list : Icons.grid_view),
            onPressed: () => setState(() => gridView = !gridView),
          ),
        ],
      ),
      body: gridView
          ? GridView.builder(
              padding: const EdgeInsets.all(8),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
              ),
              itemCount: entries.length,
              itemBuilder: (_, i) {
                final e = entries[i];
                return InkWell(
                  onTap: () async {
                    await _open(e);
                  },
                  onLongPress: () => _showActions(e),
                  child: Column(
                    children: [
                      Icon(
                        e.isDirectory ? Icons.folder : Icons.insert_drive_file,
                        size: 48,
                      ),
                      Text(
                        e.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                );
              },
            )
          : ListView.builder(
              itemCount: entries.length,
              itemBuilder: (_, i) {
                final e = entries[i];
                return ListTile(
                  leading: Icon(
                    e.isDirectory ? Icons.folder : Icons.insert_drive_file,
                  ),
                  title: Text(e.name),
                  onTap: () async {
                    debugPrint(e.mime);
                    debugPrint(e.path);
                    debugPrint(e.uri);
                    if (e.mime != null && e.mime!.contains("video")) {
                      await Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (context) =>
                              VideoThumbnailPage(videoPath: e.path ?? e.uri!),
                        ),
                      );
                    }
                    else if ( e.path != null && e.path!.contains("mp4")){
                      await Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (context) =>
                              VideoThumbnailPage(videoPath: e.path ?? e.uri!),
                        ),
                      );
                    }
                    else{
                      await _open(e);
                    }
                  },
                  onLongPress: () => _showActions(e),
                );
              },
            ),
    );
  }
}

class TrashPage extends StatefulWidget {
  const TrashPage({super.key});

  @override
  State<TrashPage> createState() => _TrashPageState();
}

class _TrashPageState extends State<TrashPage> {
  final storax = Storax();
  late Future<List<StoraxTrashEntry>> _future;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = storax.listTrash();
  }

  Future<void> _restore(StoraxTrashEntry e) async {
    await storax.restoreFromTrash(e);

    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('Restored')));

    setState(_load);
  }

  Future<void> _emptyTrash() async {
    await storax.emptyTrash(isSaf: false);
    await storax.emptyTrash(isSaf: true);

    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('Trash emptied')));

    setState(_load);
  }

  String _formatDate(DateTime dt) {
    return '${dt.day.toString().padLeft(2, '0')}-'
        '${dt.month.toString().padLeft(2, '0')}-'
        '${dt.year} '
        '${dt.hour.toString().padLeft(2, '0')}:'
        '${dt.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Trash'),
        actions: [
          IconButton(
            icon: const Icon(Icons.delete_forever),
            tooltip: 'Empty trash',
            onPressed: _emptyTrash,
          ),
        ],
      ),
      body: FutureBuilder<List<StoraxTrashEntry>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }

          if (snap.hasError) {
            return Center(child: Text('Error: ${snap.error}'));
          }

          final items = snap.data ?? [];
          if (items.isEmpty) {
            return const Center(child: Text('Trash is empty'));
          }

          return ListView.builder(
            itemCount: items.length,
            itemBuilder: (_, i) {
              final e = items[i];
              return ListTile(
                leading: const Icon(Icons.delete),
                title: Text(e.name),
                subtitle: Text(
                  'Deleted at ${_formatDate(e.trashedAt)}',
                  style: const TextStyle(fontSize: 12),
                ),

                trailing: IconButton(
                  icon: const Icon(Icons.restore),
                  onPressed: () => _restore(e),
                ),
              );
            },
          );
        },
      ),
    );
  }
}

/* ─────────────────────────────────────────────
 * VIDEO THUMBNAIL PAGE
 *  */
class VideoThumbnailPage extends StatelessWidget {
  final String videoPath;

  const VideoThumbnailPage({super.key, required this.videoPath});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Video Thumbnail')),
      body: FutureBuilder<List<Uint8List>?>(
        future: Storax().generateUniqueFrame(
          videoPath: videoPath,
          width: 300, // Keep quality low for 50 items
          // frameCount: 8,
        ),
        builder: (context, snapshot) {
          if (snapshot.hasData && snapshot.data != null) {
            debugPrint(snapshot.data!.length.toString());
            return VideoPreviewPlayer(frames: snapshot.data!);
          }
          return Center(child: CircularProgressIndicator());
        },
      ),
    );
  }
}

class VideoPreviewPlayer extends StatefulWidget {
  final List<Uint8List> frames;
  final Duration fps;

  const VideoPreviewPlayer({
    super.key,
    required this.frames,
    this.fps = const Duration(milliseconds: 150),
  });

  @override
  VideoPreviewPlayerState createState() => VideoPreviewPlayerState();
}

class VideoPreviewPlayerState extends State<VideoPreviewPlayer> {
  int _frameIndex = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    // Start the "animation" loop
    _timer = Timer.periodic(widget.fps, (timer) {
      if (mounted) {
        setState(() {
          _frameIndex = (_frameIndex + 1) % widget.frames.length;
        });
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Image.memory(
      widget.frames[_frameIndex],
      fit: BoxFit.cover,
      gaplessPlayback: true, // Prevents flickering when switching frames
    );
  }
}
