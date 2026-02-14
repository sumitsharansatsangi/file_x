import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:storax/storax.dart';

void main() {
  runApp(const StoraxApp());
}

/* ─────────────────────────────────────────────
 * APP ROOT
 * ───────────────────────────────────────────── */

class StoraxApp extends StatelessWidget {
  const StoraxApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
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

class _RootsPageState extends State<RootsPage> {
  final storax = Storax.instance;
  bool loading = true;
  List<StoraxVolume> roots = [];

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    if (!await storax.hasAllFilesAccess()) {
      await storax.requestAllFilesAccess();
    }
    await _refresh();
  }

  Future<void> _refresh() async {
    setState(() => loading = true);
    final data = await storax.getAllRoots();
    if (!mounted) return;
    setState(() {
      roots = data;
      loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Storax"),
        actions: [
          IconButton(
            icon: const Icon(Icons.info_outline),
            onPressed: () async {
              final caps = await storax.getDeviceCapabilities();
              if (!mounted || !context.mounted) return;
              showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  title: const Text("Device Info"),
                  content: Text(caps?.toString() ?? "Unknown"),
                ),
              );
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
                return ListTile(
                  leading: Icon(
                    r.mode == StoraxMode.saf
                        ? Icons.folder_shared
                        : Icons.storage,
                  ),
                  title: Text(r.name),
                  subtitle: Text("${_formatBytes(r.free)} free"),
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => FileManagerPage(
                          initialPath: (r.uri ?? r.path)!,
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
 * FILE MANAGER PAGE (PRODUCTION STYLE)
 * ───────────────────────────────────────────── */

class FileManagerPage extends StatefulWidget {
  final String initialPath;
  final String title;

  const FileManagerPage({
    super.key,
    required this.initialPath,
    required this.title,
  });

  @override
  State<FileManagerPage> createState() => _FileManagerPageState();
}

class _FileManagerPageState extends State<FileManagerPage> {
  final storax = Storax.instance;

  final List<String> pathStack = [];
  final Set<StoraxEntry> selected = {};

  List<StoraxEntry> entries = [];

  bool loading = false;
  double? transferPercent;
  bool canUndo = false;
  bool canRedo = false;

  StreamSubscription? _eventSub;

  @override
  void initState() {
    super.initState();
    pathStack.add(widget.initialPath);
    _listenEvents();
    _load();
  }

  void _listenEvents() {
    _eventSub = storax.events.listen((event) {
      if (event is TransferProgressEvent) {
        setState(() {
          transferPercent = event.percent;
        });
      }

      if (event is UndoStateChangedEvent) {
        setState(() {
          canUndo = event.canUndo;
          canRedo = event.canRedo;
        });
      }
    });
  }

  Future<void> _load() async {
    setState(() => loading = true);
    final data = await storax.listDirectory(target: pathStack.last);

    data.sort((a, b) {
      if (a.isDirectory && !b.isDirectory) return -1;
      if (!a.isDirectory && b.isDirectory) return 1;
      return a.name.compareTo(b.name);
    });

    if (!mounted) return;
    setState(() {
      entries = data;
      loading = false;
    });
  }

  /* ─────────────────────────────────────────────
   * OPERATIONS
   * ───────────────────────────────────────────── */

  void _showItemMenu(StoraxEntry e) {
    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.edit),
              title: const Text("Rename"),
              onTap: () {
                Navigator.pop(context);
                _rename(e);
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: const Text("Delete"),
              onTap: () async {
                Navigator.pop(context);
                await storax.delete(target: e.path ?? e.uri!);
                _load();
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _rename(StoraxEntry e) async {
    final controller = TextEditingController(text: e.name);

    final newName = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text("Rename"),
        content: TextField(controller: controller),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text("Cancel"),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text("OK"),
          ),
        ],
      ),
    );

    if (newName != null) {
      await storax.rename(
        source: e.path ?? e.uri!,
        newName: newName,
        conflictPolicy: 2,
      );
      _load();
    }
  }

  Future<void> _deleteSelected() async {
    for (final e in selected) {
      await storax.delete(target: e.path ?? e.uri!);
    }
    selected.clear();
    _load();
  }

  Future<void> _copySelected() async {
    if (selected.isEmpty) return;

    for (final e in selected) {
      await storax.copy(
        source: e.path ?? e.uri!,
        destinationParent: pathStack.last,
        newName: e.name,
      );
    }
    selected.clear();
    _load();
  }

  /* ─────────────────────────────────────────────
   * BUILD
   * ───────────────────────────────────────────── */

  @override
  Widget build(BuildContext context) {
    final multiSelect = selected.isNotEmpty;

    return Scaffold(
      appBar: AppBar(
        title: multiSelect
            ? Text("${selected.length} selected")
            : Text(widget.title),
        leading: multiSelect
            ? IconButton(
                icon: const Icon(Icons.close),
                onPressed: () => setState(() => selected.clear()),
              )
            : null,
        actions: [
          if (!multiSelect)
            IconButton(
              icon: const Icon(Icons.undo),
              onPressed: canUndo ? () => storax.undo() : null,
            ),
          if (!multiSelect)
            IconButton(
              icon: const Icon(Icons.redo),
              onPressed: canRedo ? () => storax.redo() : null,
            ),
          if (multiSelect)
            IconButton(icon: const Icon(Icons.copy), onPressed: _copySelected),
          if (multiSelect)
            IconButton(
              icon: const Icon(Icons.delete),
              onPressed: _deleteSelected,
            ),
        ],
      ),
      body: Column(
        children: [
          if (transferPercent != null)
            LinearProgressIndicator(value: transferPercent),
          Expanded(
            child: loading
                ? const Center(child: CircularProgressIndicator())
                : ListView.builder(
                    itemCount: entries.length,
                    itemBuilder: (_, i) {
                      final e = entries[i];
                      final isSelected = selected.contains(e);

                      return ListTile(
                        selected: isSelected,
                        leading: Icon(
                          e.isDirectory
                              ? Icons.folder
                              : Icons.insert_drive_file,
                        ),
                        title: Text(e.name),
                        subtitle: e.isDirectory
                            ? const Text("Folder")
                            : Text(_formatBytes(e.size)),
                        trailing: multiSelect
                            ? Checkbox(
                                value: isSelected,
                                onChanged: (_) {
                                  setState(() {
                                    isSelected
                                        ? selected.remove(e)
                                        : selected.add(e);
                                  });
                                },
                              )
                            : null,
                        onTap: multiSelect
                            ? () {
                                setState(() {
                                  isSelected
                                      ? selected.remove(e)
                                      : selected.add(e);
                                });
                              }
                            : () {
                                if (e.isDirectory) {
                                  pathStack.add(e.path ?? e.uri!);
                                  _load();
                                } else {
                                  storax.openFile(path: e.path, uri: e.uri);
                                }
                              },
                        onLongPress: () {
                          if (selected.isEmpty) {
                            _showItemMenu(e);
                          } else {
                            setState(() => selected.add(e));
                          }
                        },
                      );
                    },
                  ),
          ),
        ],
      ),
      floatingActionButton: multiSelect
          ? null
          : FloatingActionButton(
              onPressed: () async {
                final controller = TextEditingController();
                final name = await showDialog<String>(
                  context: context,
                  builder: (_) => AlertDialog(
                    title: const Text("New Folder"),
                    content: TextField(controller: controller),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(context),
                        child: const Text("Cancel"),
                      ),
                      ElevatedButton(
                        onPressed: () =>
                            Navigator.pop(context, controller.text),
                        child: const Text("Create"),
                      ),
                    ],
                  ),
                );

                if (name != null) {
                  await storax.create(
                    parent: pathStack.last,
                    name: name,
                    type: 1,
                  );
                  _load();
                }
              },
              child: const Icon(Icons.create_new_folder),
            ),
    );
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    super.dispose();
  }
}

/* ─────────────────────────────────────────────
 * UTIL
 * ───────────────────────────────────────────── */

String _formatBytes(int bytes) {
  if (bytes <= 0) return "0 B";
  const suffixes = ["B", "KB", "MB", "GB", "TB"];
  final i = (math.log(bytes) / math.log(1024)).floor();
  return "${(bytes / math.pow(1024, i)).toStringAsFixed(1)} ${suffixes[i]}";
}
