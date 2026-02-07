import 'package:storax/models/storax_oem.dart';
import 'package:storax/models/storax_volume.dart';
import 'package:storax/models/storax_entry.dart';
import 'package:storax/storax_method_channel.dart';

import 'storax_platform_interface.dart';

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

  /// Opens a file for reading.
  ///
  /// [path] can be:
  /// - A native filesystem path (e.g. `/storage/emulated/0/Download/file.txt`)
  /// - A SAF URI (`content://…`)
  /// - A file:// URI
  ///
  /// [mime] is optional and may be used to override the detected MIME type.
  Future<void> openFile({String? path, String? mime, String? uri}) {
    return StoraxPlatform.instance.openFile(path: path, mime: mime, uri: uri);
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
}
