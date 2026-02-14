import 'dart:async';
import 'dart:io';
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
                          root: (r.uri ?? r.path)!,
                        
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

enum ViewMode { list, grid }

enum SortOption { name, date, size }

class FileManagerPage extends StatefulWidget {
  final String root;
  const FileManagerPage({super.key, required this.root});

  @override
  State<FileManagerPage> createState() => _FileManagerPageState();
}
class _FileManagerPageState extends State<FileManagerPage>
    with TickerProviderStateMixin {
  final storax = Storax.instance;

  final List<String> pathStack = [];
  List<StoraxEntry> entries = [];
  List<StoraxEntry> filtered = [];

  final Set<String> selectedPaths = {};

  bool loading = false;
  bool showPreview = false;
  StoraxEntry? previewItem;

  ViewMode viewMode = ViewMode.list;
  SortOption sortOption = SortOption.name;
  bool ascending = true;
  String filterType = "all";

  final Map<String, double> jobProgress = {};
  StreamSubscription? jobSub;

  @override
  void initState() {
    super.initState();
    pathStack.add(widget.root);
    _load();
    _listenJobs();
  }

  void _listenJobs() {
    jobSub = storax.events.listen((event) {
      if (event is TransferProgressEvent) {
        setState(() {
          jobProgress[event.jobId] = event.percent;
        });
      }
    });
  }

  @override
  void dispose() {
    jobSub?.cancel();
    super.dispose();
  }

  /* ───────────────── LOAD ───────────────── */

  Future<void> _load() async {
    setState(() => loading = true);
    final data = await storax.listDirectory(target: pathStack.last);
    entries = data;
    _applyFilters();
    setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: _buildAppBar(),
      body: Stack(
        children: [
          Column(
            children: [
              _buildBreadcrumb(),
              _buildToolbar(),
              Expanded(
                child: loading
                    ? const Center(child: CircularProgressIndicator())
                    : AnimatedSwitcher(
                        duration: const Duration(milliseconds: 300),
                        child: viewMode == ViewMode.list
                            ? _buildList()
                            : _buildGrid(),
                      ),
              ),
            ],
          ),

          // Transfer Progress Overlay
          _buildTransferPanel(),

          // Preview Drawer
          _buildPreviewDrawer(),
        ],
      ),
    );
  }
PreferredSizeWidget _buildAppBar() {
    return AppBar(
      title: Text(
        pathStack.last.split('/').last.isEmpty
            ? "/"
            : pathStack.last.split('/').last,
      ),
      actions: [
        if (selectedPaths.isNotEmpty)
          IconButton(
            icon: const Icon(Icons.delete),
            onPressed: () async {
              for (final path in selectedPaths) {
                await storax.delete(target: path);
              }
              selectedPaths.clear();
              _load();
            },
          ),
        IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
      ],
    );
  }
Widget _buildBreadcrumb() {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: List.generate(pathStack.length, (i) {
          final name = pathStack[i].split('/').last;
          return Row(
            children: [
              TextButton(
                onPressed: () => _popTo(i),
                child: Text(name.isEmpty ? "/" : name),
              ),
              if (i != pathStack.length - 1) const Icon(Icons.chevron_right),
            ],
          );
        }),
      ),
    );
  }

Widget _buildToolbar() {
    return Row(
      children: [
        PopupMenuButton<SortOption>(
          icon: const Icon(Icons.sort),
          onSelected: (v) {
            setState(() {
              if (sortOption == v) ascending = !ascending;
              sortOption = v;
              _applyFilters();
            });
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: SortOption.name, child: Text("Name")),
            PopupMenuItem(value: SortOption.date, child: Text("Date")),
            PopupMenuItem(value: SortOption.size, child: Text("Size")),
          ],
        ),
        PopupMenuButton<String>(
          icon: const Icon(Icons.filter_list),
          onSelected: (v) {
            setState(() {
              filterType = v;
              _applyFilters();
            });
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: "all", child: Text("All")),
            PopupMenuItem(value: "images", child: Text("Images")),
            PopupMenuItem(value: "videos", child: Text("Videos")),
            PopupMenuItem(value: "docs", child: Text("Documents")),
          ],
        ),
        IconButton(
          icon: Icon(
            viewMode == ViewMode.list ? Icons.grid_view : Icons.view_list,
          ),
          onPressed: () {
            setState(() {
              viewMode = viewMode == ViewMode.list
                  ? ViewMode.grid
                  : ViewMode.list;
            });
          },
        ),
      ],
    );
  }


  /* ───────────────── FILTER & SORT ───────────────── */

  void _applyFilters() {
    filtered = entries.where((e) {
      if (filterType == "all") return true;
      if (filterType == "images") return e.mime?.contains("image") ?? false;
      if (filterType == "videos") return e.mime?.contains("video") ?? false;
      if (filterType == "docs") {
        return (e.mime?.contains("pdf") ?? false) ||
            e.name.endsWith(".doc") ||
            e.name.endsWith(".txt");
      }
      return true;
    }).toList();

    filtered.sort((a, b) {
      if (a.isDirectory && !b.isDirectory) return -1;
      if (!a.isDirectory && b.isDirectory) return 1;

      int cmp;
      switch (sortOption) {
        case SortOption.date:
          cmp = a.lastModified.compareTo(b.lastModified);
          break;
        case SortOption.size:
          cmp = (a.size).compareTo(b.size);
          break;
        default:
          cmp = a.name.toLowerCase().compareTo(b.name.toLowerCase());
      }
      return ascending ? cmp : -cmp;
    });
  }

  /* ───────────────── SELECTION ───────────────── */

  bool _isSelected(StoraxEntry e) {
    return selectedPaths.contains(e.path ?? e.uri);
  }

  void _toggleSelect(StoraxEntry e) {
    final key = e.path ?? e.uri;
    if (key == null) return;

    setState(() {
      selectedPaths.contains(key)
          ? selectedPaths.remove(key)
          : selectedPaths.add(key);
    });
  }

  /* ───────────────── NAVIGATION ───────────────── */

  void _openEntry(StoraxEntry e) {
    final target = e.path ?? e.uri;
    if (target == null) return;

    if (e.isDirectory) {
      setState(() => pathStack.add(target));
      _load();
    } else {
      storax.openFile(path: target);
    }
  }

  void _popTo(int index) {
    setState(() {
      pathStack.removeRange(index + 1, pathStack.length);
    });
    _load();
  }

  /* ───────────────── FILE TILE ───────────────── */

  Widget _fileTile(StoraxEntry e) {
    final selected = _isSelected(e);

    return GestureDetector(
      onTap: () {
        if (selectedPaths.isNotEmpty) {
          _toggleSelect(e);
        } else {
          _openEntry(e);
        }
      },
      onLongPress: () => _toggleSelect(e),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: selected
              ? Theme.of(context).colorScheme.primaryContainer
              : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
        ),
        child: viewMode == ViewMode.grid
            ? _gridTileContent(e)
            : _listTileContent(e),
      ),
    );
  }

  Widget _listTileContent(StoraxEntry e) {
    return Row(
      children: [
        Icon(_iconFor(e),
            color: e.isDirectory ? Colors.amber : Colors.blueGrey),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(e.name, overflow: TextOverflow.ellipsis),
              Text(
                _formatBytes(e.size),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        if (selectedPaths.isNotEmpty)
          Checkbox(value: _isSelected(e), onChanged: (_) => _toggleSelect(e)),
        IconButton(
          icon: const Icon(Icons.info_outline),
          onPressed: () => _openPreview(e),
        ),
      ],
    );
  }

  Widget _gridTileContent(StoraxEntry e) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(_iconFor(e),
            size: 40,
            color: e.isDirectory ? Colors.amber : Colors.blueGrey),
        const SizedBox(height: 8),
        Text(
          e.name,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  IconData _iconFor(StoraxEntry e) {
    if (e.isDirectory) return Icons.folder;
    final mime = e.mime ?? '';
    if (mime.startsWith("image")) return Icons.image;
    if (mime.startsWith("video")) return Icons.movie;
    if (mime.startsWith("audio")) return Icons.music_note;
    return Icons.insert_drive_file;
  }

  /* ───────────────── GRID / LIST BUILDERS ───────────────── */

  Widget _buildList() {
    return ListView.builder(
      itemCount: filtered.length,
      itemBuilder: (_, i) => _fileTile(filtered[i]),
    );
  }

  Widget _buildGrid() {
    return GridView.builder(
      gridDelegate:
          const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 3),
      itemCount: filtered.length,
      itemBuilder: (_, i) => _fileTile(filtered[i]),
    );
  }

  /* ───────────────── PREVIEW ───────────────── */

  void _openPreview(StoraxEntry e) {
    setState(() {
      previewItem = e;
      showPreview = true;
    });
  }

  Widget _buildPreviewDrawer() {
    if (!showPreview || previewItem == null) return const SizedBox();

    return Align(
      alignment: Alignment.centerRight,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        width: 350,
        color: Colors.white,
        child: Column(
          children: [
            ListTile(
              title: Text(previewItem!.name),
              trailing: IconButton(
                icon: const Icon(Icons.close),
                onPressed: () => setState(() => showPreview = false),
              ),
            ),
            Expanded(
              child: previewItem!.mime?.contains("image") ?? false
                  ? Image.file(File(previewItem!.path!), fit: BoxFit.contain)
                  : const Center(child: Text("Preview not supported")),
            ),
          ],
        ),
      ),
    );
  }

  /* ───────────────── TRANSFER PANEL ───────────────── */

  Widget _buildTransferPanel() {
    if (jobProgress.isEmpty) return const SizedBox();

    return Positioned(
      right: 12,
      bottom: 12,
      child: Card(
        child: SizedBox(
          width: 260,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: jobProgress.entries.map((e) {
              return ListTile(
                title: Text(e.key),
                subtitle: LinearProgressIndicator(
                  value: e.value / 100,
                ),
              );
            }).toList(),
          ),
        ),
      ),
    );
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

