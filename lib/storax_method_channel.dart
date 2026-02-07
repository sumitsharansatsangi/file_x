import 'dart:async';
import 'package:flutter/services.dart';
import 'package:storax/models/storax_oem.dart';
import 'package:storax/models/storax_volume.dart';
import 'package:storax/models/storax_entry.dart';
import 'storax_platform_interface.dart';

/// MethodChannel-based implementation of [StoraxPlatform].
///
/// This class is the Flutter-side client for the native Android
/// StoraxPlugin. It exposes strongly-typed methods that internally
/// communicate with Android via a MethodChannel.
///
/// All heavy work (filesystem, SAF, traversal) happens on the
/// native side. This layer is intentionally thin.
class MethodChannelStorax extends StoraxPlatform {
  /// Channel name **must exactly match** the one used in Android:
  ///
  /// MethodChannel(binding.binaryMessenger, "storax")
  static const MethodChannel _channel = MethodChannel('storax');

  /// Event stream
  static final StreamController<StoraxEvent> _events =
      StreamController<StoraxEvent>.broadcast();
  MethodChannelStorax() {
    _channel.setMethodCallHandler(_handleNativeCallbacks);
  }

  static Stream<StoraxEvent> get events => _events.stream;

  static Future<void> _handleNativeCallbacks(MethodCall call) async {
    switch (call.method) {
      case 'onUsbAttached':
        _events.add(const StoraxEvent(StoraxEventType.usbAttached));
        break;

      case 'onUsbDetached':
        _events.add(const StoraxEvent(StoraxEventType.usbDetached));
        break;

      case 'onSafPicked':
        _events.add(
          StoraxEvent(
            StoraxEventType.safPicked,
            payload: call.arguments as String?,
          ),
        );
        break;
    }
  }
  // ─────────────────────────────────────────────
  // Storage roots
  // ─────────────────────────────────────────────

  /// Returns native filesystem roots such as:
  /// - Internal storage
  /// - SD card
  /// - USB OTG
  /// - Adopted storage
  ///
  /// Each root contains metadata like total/free space.
  @override
  Future<List<StoraxVolume>> getNativeRoots() async {
    final result = await _channel.invokeMethod<List<dynamic>>('getNativeRoots');
    return _parseStorageVolumes(result);
  }

  /// Returns all available roots:
  /// - Native filesystem roots
  /// - SAF (Storage Access Framework) roots picked by the user
  ///
  /// Useful for building a unified "All storage locations" UI.
  @override
  Future<List<StoraxVolume>> getAllRoots() async {
    final result = await _channel.invokeMethod<List<dynamic>>('getAllRoots');
    return _parseStorageVolumes(result);
  }

  // ─────────────────────────────────────────────
  // Directory listing
  // ─────────────────────────────────────────────

  /// Lists immediate children of a directory.
  ///
  /// This is **non-recursive** and optimized for UI browsing.
  ///
  /// [target] can be:
  /// - Native filesystem path (e.g. `/storage/emulated/0/Download`)
  /// - SAF URI (e.g. `content://...`)
  ///
  /// [isSaf] must match the type of [target].
  ///
  /// [filters] is optional and may contain:
  /// - minSize / maxSize (bytes)
  /// - modifiedAfter / modifiedBefore (epoch millis)
  /// - extensions (e.g. ["pdf", "jpg"])
  /// - mimeTypes (e.g. ["image/*"])
  @override
  Future<List<StoraxEntry>> listDirectory({
    required String target,
    required bool isSaf,
    Map<String, dynamic>? filters,
  }) async {
    final result = await _channel.invokeMethod<List<dynamic>>('listDirectory', {
      'target': target,
      'isSaf': isSaf,
      'filters': ?filters,
    });
    return _parseEntries(result);
  }

  // ─────────────────────────────────────────────
  // Recursive traversal
  // ─────────────────────────────────────────────

  /// Recursively traverses a directory tree.
  ///
  /// This is **depth-limited** to prevent runaway recursion.
  ///
  /// Common use cases:
  /// - Search
  /// - Media scanning
  /// - Index building
  /// - Folder analytics
  @override
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    required bool isSaf,
    int maxDepth = 10,
    Map<String, dynamic>? filters,
  }) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'traverseDirectory',
      {
        'target': target,
        'isSaf': isSaf,
        'maxDepth': maxDepth,
        'filters': ?filters,
      },
    );
    return _parseEntries(result);
  }

  // ─────────────────────────────────────────────
  // SAF
  // ─────────────────────────────────────────────

  /// Opens the system SAF folder picker.
  ///
  /// After the user selects a folder, Android will invoke
  /// the `onSafPicked` callback on the MethodChannel.
  @override
  Future<void> openSafFolderPicker() async {
    await _channel.invokeMethod('openSafFolderPicker');
  }

  // ─────────────────────────────────────────────
  // Permissions
  // ─────────────────────────────────────────────

  /// Returns `true` if MANAGE_EXTERNAL_STORAGE is granted
  /// (or not required on older Android versions).
  @override
  Future<bool> hasAllFilesAccess() async {
    final result = await _channel.invokeMethod<bool>('hasAllFilesAccess');
    return result ?? false;
  }

  /// Opens the system settings screen where the user
  /// can grant full filesystem access.
  @override
  Future<void> requestAllFilesAccess() async {
    await _channel.invokeMethod('requestAllFilesAccess');
  }

  // ─────────────────────────────────────────────
  // Diagnostics
  // ─────────────────────────────────────────────

  /// Returns OEM/device information such as:
  /// - manufacturer
  /// - brand
  /// - model
  /// - SDK level
  ///
  /// Useful for debugging OEM-specific storage issues.
  @override
  Future<StoraxOem?> detectOEM() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'detectOEM',
    );
    return _parseOem(result);
  }

  /// Performs a lightweight permission & environment health check.
  ///
  /// Intended for:
  /// - Debug screens
  /// - Support logs
  /// - Play Store reviewer diagnostics
  @override
  Future<Map<String, dynamic>> permissionHealthCheck() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'permissionHealthCheck',
    );
    return Map<String, dynamic>.from(result ?? {});
  }

  // ─────────────────────────────────────────────
  // Utilities
  // ─────────────────────────────────────────────

  /// Casts a platform list into a strongly-typed Dart list.
  ///
  /// Platform channels return `List<dynamic>`; this helper
  /// ensures consistent `List<Map<String, dynamic>>` output.
  List<StoraxVolume> _parseStorageVolumes(List<dynamic>? data) {
    if (data == null) return const [];

    return List<StoraxVolume>.unmodifiable(
      data.whereType<Map>().map(
        (e) => StoraxVolume.fromMap(Map<String, dynamic>.from(e)),
      ),
    );
  }

  List<StoraxEntry> _parseEntries(List<dynamic>? data) {
    if (data == null) return const [];

    return List.unmodifiable(
      data.map((e) => StoraxEntry.fromMap(Map<String, dynamic>.from(e))),
    );
  }
 
 StoraxOem? _parseOem(Map<dynamic, dynamic>? data) {
    if (data == null) return null;

    return StoraxOem.fromMap(Map<String, dynamic>.from(data));
  }

  /// Opens a file for reading.
  ///
  /// [path] can be:
  /// - A native filesystem path (e.g. `/storage/emulated/0/Download/file.txt`)
  /// - A SAF URI (`content://…`)
  /// - A file:// URI
  ///
  /// [mime] is optional and may be used to override the detected MIME type.
  @override
  Future<void> openFile({String? path, String? mime, String? uri}) async {
    assert(
      (path != null) ^ (uri != null),
      'Exactly one of "path" or "uri" must be provided',
    );
    await _channel.invokeMethod('openFile', {
      'path': path,
      'mime': mime,
      'uri': uri,
    });
  }
}

enum StoraxEventType { usbAttached, usbDetached, safPicked }

class StoraxEvent {
  final StoraxEventType type;
  final String? payload;

  const StoraxEvent(this.type, {this.payload});
}
