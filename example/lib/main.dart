import 'dart:async';

import 'package:file_x/file_x.dart';
import 'package:file_x/file_x_method_channel.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const FileXExampleApp());
}

class FileXExampleApp extends StatelessWidget {
  const FileXExampleApp({super.key});

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
  final fileX = FileX();
  List<Map<String, dynamic>> roots = [];
  bool loading = true;
  late final StreamSubscription<FileXEvent> _sub;


  Future<void> ensureStoragePermission(
    FileX fileX,
    BuildContext context,
  ) async {
    final hasAccess = await fileX.hasAllFilesAccess();

    if (hasAccess == true) return;

    // Android 11+ All Files Access dialog
    await fileX.requestAllFilesAccess();
    if (context.mounted) {
      // Tell user what to do
      await showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('Permission required'),
          content: const Text(
            'Please allow "All files access" for this app.\n\n'
            'After granting permission, come back to the app.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('OK'),
            ),
          ],
        ),
      );
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sub = fileX.events.listen((event) async {
      switch (event.type) {
        case FileXEventType.usbAttached:
          await _handleUsbAttached();
          break;

        case FileXEventType.usbDetached:
        case FileXEventType.safPicked:
          await _refreshRoots();
          break;
      }
    });
    _init();
  }

  Future<void> _handleUsbAttached() async {
    // Snapshot current native roots
    final beforePaths = roots
        .where((r) => r['type'] == 'native')
        .map((r) => r['path'])
        .toSet();

    // Give Android a moment to finish mounting
    await Future.delayed(const Duration(milliseconds: 600));

    final updatedRoots = await fileX.getAllRoots();

    final nativeUsbAppeared = updatedRoots.any((r) {
      return r['type'] == 'native' &&
          r['path'] != null &&
          !beforePaths.contains(r['path']);
    });

    if (!nativeUsbAppeared && mounted) {
      final shouldAsk = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('USB storage detected'),
          content: const Text(
            'To access files on the USB device, permission is required.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Grant access'),
            ),
          ],
        ),
      );

      if (shouldAsk == true) {
        await fileX.openSafFolderPicker();
      }
    }

    if (!mounted) return;
    setState(() {
      roots = updatedRoots;
      loading = false;
    });
  }


  Future<void> _refreshRoots() async {
    final data = await fileX.getAllRoots();
    if (!mounted) return;
    setState(() {
      roots = data;
      loading = false;
    });
  }


  Future<void> _init() async {
    await ensureStoragePermission(fileX, context);
    await _refreshRoots();
  }


  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _init();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Storage'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: 'Pick SAF folder',
            onPressed: () async {
              await fileX.openSafFolderPicker();
              final allRoots = await fileX.getAllRoots();
              setState(() {
                roots = allRoots;
              });
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
                final isSaf = r['type'] == 'saf';
                return ListTile(
                  leading: Icon(isSaf ? Icons.lock : Icons.storage),
                  title: Text(r['name'] ?? ''),
                  isThreeLine: true,
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(isSaf ? 'SAF folder' : r['path'] ?? ''),
                      isSaf
                          ? const Text('SAF folder')
                          : Text(
                              '${fileX.formatBytes(r['free'] ?? 0)} free of ${fileX.formatBytes(r['total'] ?? 0)}',
                            ),
                    ],
                  ),

                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => FileBrowserPage(
                        initialTarget: isSaf ? r['uri'] : r['path'],
                        isSaf: isSaf,
                        title: r['name'],
                      ),
                    ),
                  ),
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
  final fileX = FileX();

  final List<String> pathStack = [];
  List<Map<String, dynamic>> entries = [];

  bool gridView = true;
  String search = '';
  SortMode sortMode = SortMode.name;

  @override
  void initState() {
    super.initState();
    pathStack.add(widget.initialTarget);
    _load();
  }

  Future<void> _load() async {
    final data = await fileX.listDirectory(
      target: pathStack.last,
      isSaf: widget.isSaf,
    );
    setState(() {
      entries = _applySearchAndSort(data);
    });
  }

  List<Map<String, dynamic>> _applySearchAndSort(
    List<Map<String, dynamic>> data,
  ) {
    var out = data.where((e) {
      return e['name'].toString().toLowerCase().contains(search.toLowerCase());
    }).toList();

    out.sort((a, b) {
      switch (sortMode) {
        case SortMode.size:
          return (a['size'] ?? 0).compareTo(b['size'] ?? 0);
        case SortMode.date:
          return (a['lastModified'] ?? 0).compareTo(b['lastModified'] ?? 0);
        case SortMode.name:
          return a['name'].compareTo(b['name']);
      }
    });
    return out;
  }

  void _open(Map<String, dynamic> e) {
    if (e['isDirectory'] == true) {
      pathStack.add(widget.isSaf ? e['uri'] : e['path']);
      _load();
    } else {
      debugPrint("Opening ${e['path']} with ${e['mime']}");
      fileX.openFile(path: e['path'], mime: e['mime']);
    }
  }

  void _goBack() {
    if (pathStack.length > 1) {
      pathStack.removeLast();
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: pathStack.length <= 1,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;

        if (pathStack.length > 1) {
          _goBack();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.title),
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: () {
              if (pathStack.length > 1) {
                _goBack();
              } else {
                Navigator.pop(context);
              }
            },
          ),

          actions: [
            IconButton(
              icon: Icon(gridView ? Icons.list : Icons.grid_view),
              onPressed: () => setState(() => gridView = !gridView),
            ),
            PopupMenuButton<SortMode>(
              onSelected: (m) => setState(() {
                sortMode = m;
                entries = _applySearchAndSort(entries);
              }),
              itemBuilder: (_) => const [
                PopupMenuItem(
                  value: SortMode.name,
                  child: Text('Sort by name'),
                ),
                PopupMenuItem(
                  value: SortMode.size,
                  child: Text('Sort by size'),
                ),
                PopupMenuItem(
                  value: SortMode.date,
                  child: Text('Sort by date'),
                ),
              ],
            ),
          ],
        ),
        body: Column(
          children: [
            _SearchBar(
              onChanged: (v) {
                setState(() {
                  search = v;
                  entries = _applySearchAndSort(entries);
                });
              },
            ),
            Expanded(
              child: gridView
                  ? GridView.builder(
                      padding: const EdgeInsets.all(8),
                      gridDelegate:
                          const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 3,
                            childAspectRatio: .75,
                          ),
                      itemCount: entries.length,
                      itemBuilder: (_, i) => _EntryTile(
                        entry: entries[i],
                        onTap: () => _open(entries[i]),
                      ),
                    )
                  : ListView.builder(
                      itemCount: entries.length,
                      itemBuilder: (_, i) => _EntryListTile(
                        entry: entries[i],
                        onTap: () => _open(entries[i]),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

/* ─────────────────────────────────────────────
 * UI COMPONENTS
 * ───────────────────────────────────────────── */

class _SearchBar extends StatelessWidget {
  final ValueChanged<String> onChanged;

  const _SearchBar({required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8),
      child: TextField(
        decoration: const InputDecoration(
          prefixIcon: Icon(Icons.search),
          hintText: 'Search files',
        ),
        onChanged: onChanged,
      ),
    );
  }
}

class _EntryTile extends StatelessWidget {
  final Map<String, dynamic> entry;
  final VoidCallback onTap;

  const _EntryTile({required this.entry, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final isDir = entry['isDirectory'] == true;
    return InkWell(
      onTap: onTap,
      onLongPress: () => _showActions(context, entry),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            _iconFor(entry),
            size: 48,
            color: isDir ? Colors.amber : Colors.blue,
          ),
          const SizedBox(height: 8),
          Text(
            entry['name'],
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }
}

class _EntryListTile extends StatelessWidget {
  final Map<String, dynamic> entry;
  final VoidCallback onTap;

  const _EntryListTile({required this.entry, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(_iconFor(entry)),
      title: Text(entry['name']),
      subtitle: entry['isDirectory']
          ? const Text('Folder')
          : Text(entry['mime'] ?? ''),
      onTap: onTap,
      onLongPress: () => _showActions(context, entry),
    );
  }
}

/* ─────────────────────────────────────────────
 * FILE ACTIONS (example hooks)
 * ───────────────────────────────────────────── */

void _showActions(BuildContext context, Map<String, dynamic> e) {
  showModalBottomSheet(
    context: context,
    builder: (_) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: const [
          ListTile(
            leading: Icon(Icons.drive_file_rename_outline),
            title: Text('Rename'),
          ),
          ListTile(leading: Icon(Icons.copy), title: Text('Copy')),
          ListTile(leading: Icon(Icons.delete), title: Text('Delete')),
        ],
      ),
    ),
  );
}

/* ─────────────────────────────────────────────
 * UTILITIES
 * ───────────────────────────────────────────── */

IconData _iconFor(Map<String, dynamic> e) {
  if (e['isDirectory'] == true) return Icons.folder;
  final mime = e['mime'] ?? '';
  if (mime.startsWith('image')) return Icons.image;
  if (mime.startsWith('video')) return Icons.movie;
  if (mime.startsWith('audio')) return Icons.music_note;
  if (mime.contains('pdf')) return Icons.picture_as_pdf;
  return Icons.insert_drive_file;
}

enum SortMode { name, size, date }
