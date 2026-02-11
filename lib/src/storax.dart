import 'dart:typed_data';

import 'package:storax/src/models/storax_oem.dart';
import 'package:storax/src/models/storax_trash_entry.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'package:storax/src/models/storax_entry.dart';
import 'package:storax/src/models/storax_event.dart';
import 'package:storax/src/platform/storax_method_channel.dart';

import 'platform/storax_platform_interface.dart';

/// Storax
///
/// Public Flutter-facing API for the Storax plugin.
///
/// This class is a thin façade over [StoraxPlatform] that provides
/// a clean, discoverable interface for application code.
///
/// App code should ONLY interact with this class.
/// Platform-specific details (MethodChannel, Android SAF, etc.)
/// are intentionally hidden.
class Storax {
  /// Event stream
  Stream<StoraxEvent> get events => MethodChannelStorax.events;

  Future<String?> getPlatformVersion() {
    return StoraxPlatform.instance.getPlatformVersion();
  }

  Future<int?> getSDKIntVersion() {
    return StoraxPlatform.instance.getSDKIntVersion();
  }

  /// Returns native filesystem roots such as:
  /// - Internal storage
  /// - External SD card
  /// - USB OTG / external hard drives
  /// - Adopted storage
  ///
  /// Each root includes storage statistics like total/free space.
  Future<List<StoraxVolume>> getNativeRoots() {
    return StoraxPlatform.instance.getNativeRoots();
  }

  /// Returns all available roots:
  /// - Native filesystem roots
  /// - SAF (Storage Access Framework) roots selected by the user
  ///
  /// Useful for building a unified "Select storage location" UI.
  Future<List<StoraxVolume>> getAllRoots() {
    return StoraxPlatform.instance.getAllRoots();
  }

  /// Lists immediate children of a directory.
  ///
  /// This is a **non-recursive** operation intended for fast
  /// UI directory browsing.
  ///
  /// [target] may be:
  /// - A native filesystem path
  /// - A SAF URI (content://…)
  ///
  /// [isSaf] must correctly indicate the type of [target].
  ///
  /// Optional [filters] may include:
  /// - minSize / maxSize (bytes)
  /// - modifiedAfter / modifiedBefore (epoch millis)
  /// - extensions (e.g. ["pdf", "jpg"])
  /// - mimeTypes (e.g. ["image/*"])
  Future<List<StoraxEntry>> listDirectory({
    required String target,
    required bool isSaf,
    Map<String, dynamic>? filters,
  }) {
    return StoraxPlatform.instance.listDirectory(
      target: target,
      isSaf: isSaf,
      filters: filters,
    );
  }

  /// Recursively traverses a directory tree.
  ///
  /// This operation is:
  /// - Depth-limited
  /// - Filter-aware
  /// - Executed off the UI thread on the native side
  ///
  /// Typical use cases:
  /// - Search
  /// - Media scanning
  /// - Folder analytics
  /// - Index building
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    required bool isSaf,
    int maxDepth = 10,
    Map<String, dynamic>? filters,
  }) {
    return StoraxPlatform.instance.traverseDirectory(
      target: target,
      isSaf: isSaf,
      maxDepth: maxDepth,
      filters: filters,
    );
  }

  // ─────────────────────────────────────────────
  // SAF (Storage Access Framework)
  // ─────────────────────────────────────────────

  /// Opens the system SAF folder picker.
  ///
  /// After the user selects a folder, the native Android plugin
  /// emits an `onSafPicked` callback on the MethodChannel.
  ///
  /// Your app should listen for that event if it needs the URI.
  Future<void> openSafFolderPicker() {
    return StoraxPlatform.instance.openSafFolderPicker();
  }

  // ─────────────────────────────────────────────
  // Permissions
  // ─────────────────────────────────────────────

  /// Returns `true` if the app has full filesystem access:
  /// - MANAGE_EXTERNAL_STORAGE on Android 11+
  /// - Always `true` on older Android versions
  Future<bool> hasAllFilesAccess() {
    return StoraxPlatform.instance.hasAllFilesAccess();
  }

  /// Opens the system settings screen where the user
  /// can grant full filesystem (file manager) access.
  Future<void> requestAllFilesAccess() {
    return StoraxPlatform.instance.requestAllFilesAccess();
  }

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
  Future<StoraxOem?> detectOEM() {
    return StoraxPlatform.instance.detectOEM();
  }

  /// Performs a high-level permission and environment health check.
  ///
  /// Intended for:
  /// - Debug screens
  /// - Support logs
  /// - Play Store reviewer diagnostics
  Future<Map<String, dynamic>> permissionHealthCheck() {
    return StoraxPlatform.instance.permissionHealthCheck();
  }

  /// Creates a new folder.
  Future<void> createFolder({
    required String parent,
    required String name,
    required bool isSaf,
  }) {
    return StoraxPlatform.instance.createFolder(
      parent: parent,
      name: name,
      isSaf: isSaf,
    );
  }

  /// Creates a new file.
  Future<void> createFile({
    required String parent,
    required String name,
    String? mime,
    required bool isSaf,
  }) {
    return StoraxPlatform.instance.createFile(
      parent: parent,
      name: name,
      mime: mime,
      isSaf: isSaf,
    );
  }

  /// Copy file (native or SAF).
  /// Returns a jobId immediately.
  Future<String> copy({
    required String source,
    required String destination,
    required bool isSaf,
  }) {
    return StoraxPlatform.instance.copy(
      source: source,
      destination: destination,
      isSaf: isSaf,
    );
  }

  /// Move file (native or SAF).
  /// Returns a jobId immediately.
  Future<String> move({
    required String source,
    required String destination,
    required bool isSaf,
  }) {
    return StoraxPlatform.instance.move(
      source: source,
      destination: destination,
      isSaf: isSaf,
    );
  }

  /// Rename file or folder.
  Future<void> rename({
    required String target,
    required String newName,
    required bool isSaf,
  }) {
    return StoraxPlatform.instance.rename(
      target: target,
      newName: newName,
      isSaf: isSaf,
    );
  }

  /// Deletes a file or folder.
  Future<void> delete({required String target, required bool isSaf}) {
    return StoraxPlatform.instance.delete(target: target, isSaf: isSaf);
  }

  Future<void> moveToTrash({
    required String target,
    required bool isSaf,
    String? safRootUri,
  }) => StoraxPlatform.instance.moveToTrash(
    target: target,
    isSaf: isSaf,
    safRootUri: safRootUri,
  );

  Future<List<StoraxTrashEntry>> listTrash() =>
      StoraxPlatform.instance.listTrash();

  Future<void> restoreFromTrash(StoraxTrashEntry entry) =>
      StoraxPlatform.instance.restoreFromTrash(entry);

  Future<void> emptyTrash({required bool isSaf, String? safRootUri}) =>
      StoraxPlatform.instance.emptyTrash(isSaf: isSaf, safRootUri: safRootUri);

  /// Opens a file for reading.
  ///
  /// [path] can be:
  /// - A native filesystem path (e.g. `/storage/emulated/0/Download/file.txt`)
  /// - A SAF URI (`content://…`)
  /// - A file:// URI
  ///
  /// [mime] is optional and may be used to override the detected MIME type.
 Future<void> openFile({required String path, String? mime}) {
    String? resolvedPath;
    String? resolvedUri;

    final parsed = Uri.tryParse(path);

    if (parsed != null && parsed.scheme == 'content') {
      // SAF / shared URI
      resolvedUri = path;
    } else {
      // Everything else = filesystem path
      resolvedPath = path;
    }

    return StoraxPlatform.instance.openFile(
      path: resolvedPath,
      uri: resolvedUri,
      mime: mime,
    );
  }

  String formatBytes(int bytes) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    double size = bytes.toDouble();
    int unit = 0;

    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }

    return '${size.toStringAsFixed(2)} ${units[unit]}';
  }

  Future<List<Uint8List>?> generateGifThumbnail({
    required String videoPath,
    int? width,
    int? height,
    int frameCount = 10,
  }) {
    return StoraxPlatform.instance.generateGifThumbnail(
      videoPath: videoPath,
      width: width,
      height: height,
      frameCount: frameCount,
    );
  }

  Future<List<Uint8List>?> generateUniqueFrame({
    required String videoPath,
    int? width,
    int? height,
  }) {
    return StoraxPlatform.instance.generateUniqueFrame(
      videoPath: videoPath,
      width: width,
      height: height,
    );
  }

}
