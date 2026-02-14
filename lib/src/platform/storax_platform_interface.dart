import 'dart:typed_data';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:storax/src/models/storax_device_capabilities.dart';
import 'package:storax/src/models/storax_entry.dart';
import 'package:storax/src/models/storax_trash_entry.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'storax_method_channel.dart';

/// Platform interface for the Storax plugin.
///
/// Defines the contract that all platform implementations must follow.
///
/// This interface mirrors the latest native Android implementation.
///
/// ⚠️ Do NOT add implementation logic here.
abstract class StoraxPlatform extends PlatformInterface {
  StoraxPlatform() : super(token: _token);

  static final Object _token = Object();
  static StoraxPlatform _instance = MethodChannelStorax();

  /// Current platform implementation.
  static StoraxPlatform get instance => _instance;

  static set instance(StoraxPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  // ─────────────────────────────────────────────
  // ROOT DISCOVERY
  // ─────────────────────────────────────────────

  /// Returns native filesystem roots
  /// (internal storage, SD, USB, etc).
  Future<List<StoraxVolume>> getNativeRoots();

  /// Returns SAF roots selected by user.
  Future<List<StoraxVolume>> getSafRoots();

  /// Returns unified list of all available roots.
  Future<List<StoraxVolume>> getAllRoots();

  /// Returns detailed device + environment capabilities.
  Future<StoraxDeviceCapabilities?> getDeviceCapabilities();

  // ─────────────────────────────────────────────
  // PERMISSIONS
  // ─────────────────────────────────────────────

  Future<bool> hasAllFilesAccess();

  Future<void> requestAllFilesAccess();

  // ─────────────────────────────────────────────
  // SAF
  // ─────────────────────────────────────────────

  Future<void> openSafFolderPicker();

  // ─────────────────────────────────────────────
  // DIRECTORY OPERATIONS
  // ─────────────────────────────────────────────

  /// Lists immediate children of a directory.
  ///
  /// [target] may be either:
  /// - Native filesystem path
  /// - SAF content:// URI
  Future<List<StoraxEntry>> listDirectory({required String target});

  /// Recursively traverses a directory.
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    int maxDepth = -1,
  });

  // ─────────────────────────────────────────────
  // CREATE
  // ─────────────────────────────────────────────

  /// Creates a file or folder.
  ///
  /// Returns:
  /// {
  ///   success: bool,
  ///   finalName: String,
  ///   path: String
  /// }
  Future<Map<String, dynamic>> create({
    required String parent,
    required String name,
    required int type, // 0 = file, 1 = folder
    int conflictPolicy = 0,
    String? manualRename,
  });

  // ─────────────────────────────────────────────
  // COPY / MOVE
  // ─────────────────────────────────────────────

  /// Adaptive copy.
  ///
  /// May return:
  /// - bool (immediate small copy)
  /// - String (jobId for transactional copy)
  Future<dynamic> copy({
    required String source,
    required String destinationParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  });

  Future<bool> cancelCopy(String jobId);
  Future<bool> pauseCopy(String jobId);
  Future<bool> resumeCopy(String jobId);

  /// Move operation.
  Future<bool> move({
    required String source,
    required String destParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  });

  /// Rename operation.
  Future<bool> rename({
    required String source,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  });

  // ─────────────────────────────────────────────
  // DELETE / TRASH
  // ─────────────────────────────────────────────

  /// Moves file/folder to trash.
  Future<bool> delete({required String target});

  /// Permanently deletes without trash.
  Future<bool> permanentlyDelete({required String path});

  Future<List<StoraxTrashEntry>> listTrash();

  Future<bool> restoreFromTrash(StoraxTrashEntry entry);

  Future<bool> permanentlyDeleteFromTrash(StoraxTrashEntry entry);

  Future<bool> emptyTrash();

  // ─────────────────────────────────────────────
  // UNDO / REDO
  // ─────────────────────────────────────────────

  Future<bool> undo();
  Future<bool> redo();

  Future<bool> canUndo();
  Future<bool> canRedo();

  Future<int> undoCount();
  Future<int> redoCount();

  Future<void> clearUndo();

  // ─────────────────────────────────────────────
  // FILE OPENING
  // ─────────────────────────────────────────────

  Future<void> openFile({String? path, String? uri, String? mime});

  // ─────────────────────────────────────────────
  // MEDIA
  // ─────────────────────────────────────────────

  Future<List<Uint8List>?> generateVideoThumbnail({
    required String videoPath,
    int? width,
    int? height,
    int frameCount = 5,
  });
}
