import 'dart:math' as math;
import 'dart:typed_data';

import 'package:storax/src/models/storax_entry.dart';
import 'package:storax/src/models/storax_event.dart';
import 'package:storax/src/models/storax_device_capabilities.dart';
import 'package:storax/src/models/storax_trash_entry.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'package:storax/src/platform/storax_method_channel.dart';
import 'platform/storax_platform_interface.dart';

/// Main public API entry point for Storax.
///
/// This class provides a unified, high-level storage API,
/// abstracting native Android complexity.
class Storax {
  Storax._();

  static final Storax _instance = Storax._();

  /// Singleton access.
  static Storax get instance => _instance;

  /// Stream of strongly-typed native events.
  Stream<StoraxEvent> get events => MethodChannelStorax.events;

  // ─────────────────────────────────────────────
  // Roots & Capabilities
  // ─────────────────────────────────────────────

  Future<List<StoraxVolume>> getNativeRoots() =>
      StoraxPlatform.instance.getNativeRoots();

  Future<List<StoraxVolume>> getSafRoots() =>
      StoraxPlatform.instance.getSafRoots();

  Future<List<StoraxVolume>> getAllRoots() =>
      StoraxPlatform.instance.getAllRoots();

  // ─────────────────────────────────────────────
  // Permissions
  // ─────────────────────────────────────────────

  Future<bool> hasAllFilesAccess() =>
      StoraxPlatform.instance.hasAllFilesAccess();

  Future<void> requestAllFilesAccess() =>
      StoraxPlatform.instance.requestAllFilesAccess();

  // ─────────────────────────────────────────────
  // SAF
  // ─────────────────────────────────────────────

  Future<void> openSafFolderPicker() =>
      StoraxPlatform.instance.openSafFolderPicker();

  // ─────────────────────────────────────────────
  // Directory
  // ─────────────────────────────────────────────

  Future<List<StoraxEntry>> listDirectory({required String target}) =>
      StoraxPlatform.instance.listDirectory(target: target);

  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    int maxDepth = -1,
  }) => StoraxPlatform.instance.traverseDirectory(
    target: target,
    maxDepth: maxDepth,
  );

  // ─────────────────────────────────────────────
  // Create
  // ─────────────────────────────────────────────

  Future<Map<String, dynamic>> create({
    required String parent,
    required String name,
    required int type,
    int conflictPolicy = 0,
    String? manualRename,
  }) => StoraxPlatform.instance.create(
    parent: parent,
    name: name,
    type: type,
    conflictPolicy: conflictPolicy,
    manualRename: manualRename,
  );

  // ─────────────────────────────────────────────
  // Copy / Move / Rename
  // ─────────────────────────────────────────────

  Future<dynamic> copy({
    required String source,
    required String destinationParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) => StoraxPlatform.instance.copy(
    source: source,
    destinationParent: destinationParent,
    newName: newName,
    conflictPolicy: conflictPolicy,
    manualRename: manualRename,
  );

  Future<bool> cancelCopy(String jobId) =>
      StoraxPlatform.instance.cancelCopy(jobId);

  Future<bool> pauseCopy(String jobId) =>
      StoraxPlatform.instance.pauseCopy(jobId);

  Future<bool> resumeCopy(String jobId) =>
      StoraxPlatform.instance.resumeCopy(jobId);

  Future<bool> move({
    required String source,
    required String destParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) => StoraxPlatform.instance.move(
    source: source,
    destParent: destParent,
    newName: newName,
    conflictPolicy: conflictPolicy,
    manualRename: manualRename,
  );

  Future<bool> rename({
    required String source,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) => StoraxPlatform.instance.rename(
    source: source,
    newName: newName,
    conflictPolicy: conflictPolicy,
    manualRename: manualRename,
  );

  // ─────────────────────────────────────────────
  // Delete & Trash
  // ─────────────────────────────────────────────

  Future<bool> delete({required String target}) =>
      StoraxPlatform.instance.delete(target: target);

  Future<bool> permanentlyDelete({required String path}) =>
      StoraxPlatform.instance.permanentlyDelete(path: path);

  Future<List<StoraxTrashEntry>> listTrash() =>
      StoraxPlatform.instance.listTrash();

  Future<bool> restoreFromTrash(StoraxTrashEntry entry) =>
      StoraxPlatform.instance.restoreFromTrash(entry);

  Future<bool> permanentlyDeleteFromTrash(StoraxTrashEntry entry) =>
      StoraxPlatform.instance.permanentlyDeleteFromTrash(entry);

  Future<bool> emptyTrash() => StoraxPlatform.instance.emptyTrash();

  // ─────────────────────────────────────────────
  // Undo / Redo
  // ─────────────────────────────────────────────

  Future<bool> undo() => StoraxPlatform.instance.undo();

  Future<bool> redo() => StoraxPlatform.instance.redo();

  Future<bool> canUndo() => StoraxPlatform.instance.canUndo();

  Future<bool> canRedo() => StoraxPlatform.instance.canRedo();

  Future<int> undoCount() => StoraxPlatform.instance.undoCount();

  Future<int> redoCount() => StoraxPlatform.instance.redoCount();

  Future<void> clearUndo() => StoraxPlatform.instance.clearUndo();

  // ─────────────────────────────────────────────
  // File Opening
  // ─────────────────────────────────────────────

  Future<void> openFile({String? path, String? uri, String? mime}) =>
      StoraxPlatform.instance.openFile(path: path, uri: uri, mime: mime);

  // ─────────────────────────────────────────────
  // Media
  // ─────────────────────────────────────────────

  Future<List<Uint8List>?> generateVideoThumbnail({
    required String videoPath,
    int? width,
    int? height,
    int frameCount = 5,
  }) => StoraxPlatform.instance.generateVideoThumbnail(
    videoPath: videoPath,
    width: width,
    height: height,
    frameCount: frameCount,
  );

  // ─────────────────────────────────────────────
  // Diagnostics
  // ─────────────────────────────────────────────

 Future<StoraxDeviceCapabilities?> getDeviceCapabilities() =>
      StoraxPlatform.instance.getDeviceCapabilities();


  // ─────────────────────────────────────────────
  // Utilities
  // ─────────────────────────────────────────────

  /// Converts raw byte count into human-readable string.
  String formatBytes(int bytes, [int decimals = 2]) {
    if (bytes <= 0) return "0 B";

    const suffixes = ["B", "KB", "MB", "GB", "TB", "PB"];

    final i = (math.log(bytes) / math.log(1024)).floor();

    return '${(bytes / math.pow(1024, i)).toStringAsFixed(decimals)} ${suffixes[i]}';
  }
}
