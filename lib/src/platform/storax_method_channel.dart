import 'dart:async';
import 'package:flutter/services.dart';
import 'package:storax/src/models/storax_device_capabilities.dart';
import 'package:storax/src/models/storax_entry.dart';
import 'package:storax/src/models/storax_event.dart';
import 'package:storax/src/models/storax_trash_entry.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'storax_platform_interface.dart';

/// MethodChannel-based implementation of [StoraxPlatform].
///
/// Strict bridge layer between Flutter and native Android.
/// All filesystem logic is handled on the native side.
///
/// This class:
/// - Parses native results
/// - Broadcasts native async events
/// - Ensures strict type handling
class MethodChannelStorax extends StoraxPlatform {
  static const MethodChannel _channel = MethodChannel('storax');

  static final StreamController<StoraxEvent> _events =
      StreamController<StoraxEvent>.broadcast();

  MethodChannelStorax() {
    _channel.setMethodCallHandler(_handleNativeCallbacks);
  }

  static Stream<StoraxEvent> get events => _events.stream;

  // ─────────────────────────────────────────────
  // Native Event Handling
  // ─────────────────────────────────────────────

  static Future<void> _handleNativeCallbacks(MethodCall call) async {
    switch (call.method) {
      case 'onUsbAttached':
        _events.add(UsbAttachedEvent());
        break;

      case 'onUsbDetached':
        _events.add(UsbDetachedEvent());
        break;

      case 'onSafPicked':
        final uri = call.arguments as String?;
        if (uri != null) {
          _events.add(SafPickedEvent(uri));
        }
        break;

      case 'onTransferProgress':
        if (call.arguments is Map) {
          final data = Map<String, dynamic>.from(call.arguments as Map);

          final jobId = data['jobId'] as String?;
          final percentRaw = data['percent'];

          if (jobId != null && percentRaw != null) {
            final percent = percentRaw is int
                ? percentRaw.toDouble()
                : (percentRaw as num).toDouble();

            _events.add(TransferProgressEvent(jobId, percent));
          }
        }
        break;

      case 'onUndoStateChanged':
        if (call.arguments is Map) {
          final data = Map<String, dynamic>.from(call.arguments as Map);

          final canUndo = data['canUndo'] as bool? ?? false;
          final canRedo = data['canRedo'] as bool? ?? false;

          _events.add(UndoStateChangedEvent(canUndo, canRedo));
        }
        break;
    }
  }

  // ─────────────────────────────────────────────
  // Roots
  // ─────────────────────────────────────────────

  @override
  Future<List<StoraxVolume>> getNativeRoots() async =>
      _parseVolumes(await _channel.invokeMethod<List>('getNativeRoots'));

  @override
  Future<List<StoraxVolume>> getSafRoots() async =>
      _parseVolumes(await _channel.invokeMethod<List>('getSafRoots'));

  @override
  Future<List<StoraxVolume>> getAllRoots() async =>
      _parseVolumes(await _channel.invokeMethod<List>('getAllRoots'));

  @override
  Future<StoraxDeviceCapabilities?> getDeviceCapabilities() async {
    final result = await _channel.invokeMethod<Map>('getDeviceCapabilities');

    if (result == null) return null;

    return StoraxDeviceCapabilities.fromMap(Map<String, dynamic>.from(result));
  }


  // ─────────────────────────────────────────────
  // Permissions
  // ─────────────────────────────────────────────

  @override
  Future<bool> hasAllFilesAccess() async =>
      await _channel.invokeMethod<bool>('hasAllFilesAccess') ?? false;

  @override
  Future<void> requestAllFilesAccess() async =>
      _channel.invokeMethod('requestAllFilesAccess');

  // ─────────────────────────────────────────────
  // SAF
  // ─────────────────────────────────────────────

  @override
  Future<void> openSafFolderPicker() async =>
      _channel.invokeMethod('openSafFolderPicker');

  // ─────────────────────────────────────────────
  // Directory operations
  // ─────────────────────────────────────────────

  @override
  Future<List<StoraxEntry>> listDirectory({required String target}) async {
    final result = await _channel.invokeMethod<List>('listDirectory', {
      'target': target,
    });
    return _parseEntries(result);
  }

  @override
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    int maxDepth = -1,
  }) async {
    final result = await _channel.invokeMethod<List>('traverseDirectory', {
      'target': target,
      'maxDepth': maxDepth,
    });
    return _parseEntries(result);
  }

  // ─────────────────────────────────────────────
  // Create
  // ─────────────────────────────────────────────

  @override
  Future<Map<String, dynamic>> create({
    required String parent,
    required String name,
    required int type,
    int conflictPolicy = 0,
    String? manualRename,
  }) async {
    final result = await _channel.invokeMethod<Map>('create', {
      'parent': parent,
      'name': name,
      'type': type,
      'conflictPolicy': conflictPolicy,
      'manualRename': manualRename,
    });

    return result?.cast<String, dynamic>() ?? {};
  }

  // ─────────────────────────────────────────────
  // Copy / Move / Rename
  // ─────────────────────────────────────────────

  @override
  Future<dynamic> copy({
    required String source,
    required String destinationParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) => _channel.invokeMethod('copy', {
    'source': source,
    'destinationParent': destinationParent,
    'newName': newName,
    'conflictPolicy': conflictPolicy,
    'manualRename': manualRename,
  });

  @override
  Future<bool> move({
    required String source,
    required String destParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) async =>
      await _channel.invokeMethod<bool>('move', {
        'source': source,
        'destParent': destParent,
        'newName': newName,
        'conflictPolicy': conflictPolicy,
        'manualRename': manualRename,
      }) ??
      false;

  @override
  Future<bool> rename({
    required String source,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) async =>
      await _channel.invokeMethod<bool>('rename', {
        'source': source,
        'newName': newName,
        'conflictPolicy': conflictPolicy,
        'manualRename': manualRename,
      }) ??
      false;

  @override
  Future<bool> cancelCopy(String jobId) async =>
      await _channel.invokeMethod<bool>('cancelCopy', {'jobId': jobId}) ??
      false;

  @override
  Future<bool> pauseCopy(String jobId) async =>
      await _channel.invokeMethod<bool>('pauseCopy', {'jobId': jobId}) ?? false;

  @override
  Future<bool> resumeCopy(String jobId) async =>
      await _channel.invokeMethod<bool>('resumeCopy', {'jobId': jobId}) ??
      false;

  // ─────────────────────────────────────────────
  // Delete / Trash
  // ─────────────────────────────────────────────

  @override
  Future<bool> delete({required String target}) async =>
      await _channel.invokeMethod<bool>('delete', {'target': target}) ?? false;

  @override
  Future<bool> permanentlyDelete({required String path}) async =>
      await _channel.invokeMethod<bool>('permanentlyDelete', {'path': path}) ??
      false;

  @override
  Future<List<StoraxTrashEntry>> listTrash() async {
    final result = await _channel.invokeMethod<List>('listTrash');

    return (result ?? [])
        .map((e) => StoraxTrashEntry.fromMap(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  @override
  Future<bool> restoreFromTrash(StoraxTrashEntry entry) async =>
      await _channel.invokeMethod<bool>('restoreFromTrash', {
        'entry': entry.toMap(),
      }) ??
      false;

  @override
  Future<bool> permanentlyDeleteFromTrash(StoraxTrashEntry entry) async =>
      await _channel.invokeMethod<bool>('permanentlyDeleteFromTrash', {
        'entry': entry.toMap(),
      }) ??
      false;

  @override
  Future<bool> emptyTrash() async =>
      await _channel.invokeMethod<bool>('emptyTrash') ?? false;

  // ─────────────────────────────────────────────
  // Undo / Redo
  // ─────────────────────────────────────────────

  @override
  Future<bool> undo() async =>
      await _channel.invokeMethod<bool>('undo') ?? false;

  @override
  Future<bool> redo() async =>
      await _channel.invokeMethod<bool>('redo') ?? false;

  @override
  Future<bool> canUndo() async =>
      await _channel.invokeMethod<bool>('canUndo') ?? false;

  @override
  Future<bool> canRedo() async =>
      await _channel.invokeMethod<bool>('canRedo') ?? false;

  @override
  Future<int> undoCount() async =>
      await _channel.invokeMethod<int>('undoCount') ?? 0;

  @override
  Future<int> redoCount() async =>
      await _channel.invokeMethod<int>('redoCount') ?? 0;

  @override
  Future<void> clearUndo() async => _channel.invokeMethod('clearUndo');

  // ─────────────────────────────────────────────
  // File Open
  // ─────────────────────────────────────────────

  @override
  Future<void> openFile({String? path, String? uri, String? mime}) async {
    if ((path == null && uri == null) || (path != null && uri != null)) {
      throw ArgumentError('Exactly one of "path" or "uri" must be provided');
    }

    await _channel.invokeMethod('openFile', {
      'path': path,
      'uri': uri,
      'mime': mime,
    });
  }

  // ─────────────────────────────────────────────
  // Media
  // ─────────────────────────────────────────────

  @override
  Future<List<Uint8List>?> generateVideoThumbnail({
    required String videoPath,
    int? width,
    int? height,
    int frameCount = 5,
  }) async {
    final result = await _channel.invokeMethod<List>('generateVideoThumbnail', {
      'path': videoPath,
      'width': width,
      'height': height,
      'frameCount': frameCount,
    });

    return result
        ?.map((e) => Uint8List.fromList(List<int>.from(e)))
        .toList(growable: false);
  }

  // ─────────────────────────────────────────────
  // Parsing Helpers
  // ─────────────────────────────────────────────

  List<StoraxVolume> _parseVolumes(List<dynamic>? data) {
    if (data == null) return const [];
    return data
        .map((e) => StoraxVolume.fromMap(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  List<StoraxEntry> _parseEntries(List<dynamic>? data) {
    if (data == null) return const [];
    return data
        .map((e) => StoraxEntry.fromMap(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }
}
