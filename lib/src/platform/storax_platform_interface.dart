import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:storax/src/models/storax_oem.dart';
import 'package:storax/src/models/storax_trash_entry.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'package:storax/src/models/storax_entry.dart';

import 'storax_method_channel.dart';

/// Platform interface for the Storax plugin.
///
/// This class defines the **contract** that all platform-specific
/// implementations (Android, iOS, desktop, web) must follow.
///
/// The default implementation is [MethodChannelStorax], which talks
/// to the native Android plugin via a MethodChannel.
///
/// ⚠️ Do NOT add implementation logic here.
/// This class must remain abstract.
abstract class StoraxPlatform extends PlatformInterface {
  StoraxPlatform() : super(token: _token);

  static final Object _token = Object();

  static StoraxPlatform _instance = MethodChannelStorax();

  /// The active platform implementation.
  ///
  /// By default this is [MethodChannelStorax].
  /// Platform-specific implementations (e.g. desktop, web)
  /// may override this during registration.
  static StoraxPlatform get instance => _instance;

  /// Sets a new platform implementation.
  ///
  /// This is typically called by platform-specific registration code.
  static set instance(StoraxPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  // ─────────────────────────────────────────────
  // Storage roots
  // ─────────────────────────────────────────────

  /// Returns native filesystem roots such as:
  /// - Internal storage
  /// - External SD card
  /// - USB OTG
  /// - Adopted storage
  ///
  /// Each entry includes storage statistics like total and free space.
  Future<List<StoraxVolume>> getNativeRoots();

  /// Returns all available storage roots:
  /// - Native filesystem roots
  /// - SAF (Storage Access Framework) roots selected by the user
  ///
  /// Useful for building a unified storage selector UI.
  Future<List<StoraxVolume>> getAllRoots();

  // ─────────────────────────────────────────────
  // Directory listing
  // ─────────────────────────────────────────────

  /// Lists immediate children of a directory.
  ///
  /// This is a **non-recursive** operation intended for
  /// fast UI folder browsing.
  ///
  /// [target] can be:
  /// - A native filesystem path (e.g. `/storage/emulated/0/Download`)
  /// - A SAF URI (`content://…`)
  ///
  /// [isSaf] must correctly indicate the type of [target].
  ///
  /// [filters] is optional and may contain:
  /// - minSize / maxSize (bytes)
  /// - modifiedAfter / modifiedBefore (epoch millis)
  /// - extensions (e.g. ["pdf", "jpg"])
  /// - mimeTypes (e.g. ["image/*"])
  Future<List<StoraxEntry>> listDirectory({
    required String target,
    required bool isSaf,
    Map<String, dynamic>? filters,
  });

  // ─────────────────────────────────────────────
  // Recursive traversal
  // ─────────────────────────────────────────────

  /// Recursively traverses a directory tree.
  ///
  /// This method is **depth-limited** to prevent runaway recursion.
  ///
  /// Common use cases:
  /// - File search
  /// - Media scanning
  /// - Indexing
  /// - Folder analytics
  ///
  /// [maxDepth] controls how deep recursion may go.
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    required bool isSaf,
    int maxDepth,
    Map<String, dynamic>? filters,
  });

  // ─────────────────────────────────────────────
  // SAF (Storage Access Framework)
  // ─────────────────────────────────────────────

  /// Opens the system SAF folder picker.
  ///
  /// After the user selects a folder, the native platform
  /// will emit an `onSafPicked` callback on the MethodChannel.
  Future<void> openSafFolderPicker();

  // ─────────────────────────────────────────────
  // Permissions
  // ─────────────────────────────────────────────

  /// Returns `true` if the app has full filesystem access:
  /// - MANAGE_EXTERNAL_STORAGE on Android 11+
  /// - Always `true` on older Android versions
  Future<bool> hasAllFilesAccess();

  /// Opens the appropriate system settings screen
  /// where the user can grant full filesystem access.
  Future<void> requestAllFilesAccess();

  // ─────────────────────────────────────────────
  // Diagnostics
  // ─────────────────────────────────────────────

  /// Returns OEM and device information such as:
  /// - manufacturer
  /// - brand
  /// - model
  /// - SDK level
  ///
  /// Useful for debugging OEM-specific storage behavior.
  Future<StoraxOem?> detectOEM();

  /// Performs a high-level permission and environment health check.
  ///
  /// Intended for:
  /// - Debug screens
  /// - Support logs
  /// - Play Store reviewer diagnostics
  Future<Map<String, dynamic>> permissionHealthCheck();

  /// Creates a new folder.
  Future<void> createFolder({
    required String parent,
    required String name,
    required bool isSaf,
  });

  /// Creates a new file.
  Future<void> createFile({
    required String parent,
    required String name,
    String? mime,
    required bool isSaf,
  });

  /// Copy file (native or SAF).
  /// Returns a jobId immediately.
  Future<String> copy({
    required String source,
    required String destination,
    required bool isSaf,
  });

  /// Move file (native or SAF).
  /// Returns a jobId immediately.
  Future<String> move({
    required String source,
    required String destination,
    required bool isSaf,
  });

  /// Rename file or folder.
  Future<void> rename({
    required String target,
    required String newName,
    required bool isSaf,
  });

  /// Deletes a file or folder.
  Future<void> delete({required String target, required bool isSaf});

  /// Moves a file or folder to trash.
  Future<void> moveToTrash({
    required String target,
    required bool isSaf,
    String? safRootUri,
  });

  /// Lists trash entries.
  Future<List<StoraxTrashEntry>> listTrash();

  /// Restores a trashed file or folder.
  Future<void> restoreFromTrash(StoraxTrashEntry entry);

  /// Empties trash.
  Future<void> emptyTrash({required bool isSaf, String? safRootUri});

  /// Opens a file for reading.
  ///
  /// [path] can be:
  /// - A native filesystem path (e.g. `/storage/emulated/0/Download/file.txt`)
  /// - A SAF URI (`content://…`)
  /// - A file:// URI
  ///
  /// [mime] is optional and may be used to override the detected MIME type.
  Future<void> openFile({String? path, String? mime, String? uri});
}
