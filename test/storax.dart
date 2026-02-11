import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'package:storax/src/models/storax_mode.dart';
import 'package:storax/src/models/storax_oem.dart';
import 'package:storax/src/models/storax_trash_entry.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'package:storax/src/models/storax_entry.dart';
import 'package:storax/src/platform/storax_method_channel.dart';
import 'package:storax/src/platform/storax_platform_interface.dart';
import 'package:storax/src/storax.dart';

/// A mock platform implementation used to verify that
/// [Storax] correctly delegates calls to [StoraxPlatform.instance].
class MockStoraxPlatform
    with MockPlatformInterfaceMixin
    implements StoraxPlatform {
  @override
  Future<String?> getPlatformVersion() async {
    return 'Android 11';
  }

  @override
  Future<int?> getSDKIntVersion() async {
    return 30;
  }

  @override
  Future<List<StoraxVolume>> getNativeRoots() async {
    return [
      StoraxVolume(
        mode: StoraxMode.native,
        name: 'Mock storage',
        path: '/mock/path',
        total: 100,
        free: 50,
        used: 50,
        writable: true,
      ),
    ];
  }

  @override
  Future<List<StoraxVolume>> getAllRoots() async {
    return getNativeRoots();
  }

  @override
  Future<List<StoraxEntry>> listDirectory({
    required String target,
    required bool isSaf,
    Map<String, dynamic>? filters,
  }) async {
    return [
      StoraxEntry(
        name: 'mock.txt',
        path: '/mock/path/mock.txt',
        uri: null,
        isDirectory: false,
        size: 123,
        lastModified: 1700000000000,
        mime: 'text/plain',
        mode: StoraxMode.native,
      ),
    ];
  }

  @override
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    required bool isSaf,
    int maxDepth = 10,
    Map<String, dynamic>? filters,
  }) async {
    return listDirectory(target: target, isSaf: isSaf);
  }

  @override
  Future<void> openSafFolderPicker() async {}

  @override
  Future<bool> hasAllFilesAccess() async => true;

  @override
  Future<void> requestAllFilesAccess() async {}

  @override
  Future<StoraxOem?> detectOEM() async {
    return StoraxOem(
      manufacturer: 'Mock',
      brand: 'MockBrand',
      model: 'MockModel',
      sdk: 34,
    );
  }

  @override
  Future<Map<String, dynamic>> permissionHealthCheck() async {
    return {'allFilesAccess': true, 'sdk': 34, 'oem': 'Mock'};
  }

  // ─────────────────────────────────────────────
  // File operations
  // ─────────────────────────────────────────────

  @override
  Future<void> createFolder({
    required String parent,
    required String name,
    required bool isSaf,
  }) async {}

  @override
  Future<void> createFile({
    required String parent,
    required String name,
    String? mime,
    required bool isSaf,
  }) async {}

  @override
  Future<String> copy({
    required String source,
    required String destination,
    required bool isSaf,
  }) async {
    return 'job_copy_123';
  }

  @override
  Future<String> move({
    required String source,
    required String destination,
    required bool isSaf,
  }) async {
    return 'job_move_123';
  }

  @override
  Future<void> rename({
    required String target,
    required String newName,
    required bool isSaf,
  }) async {}

  @override
  Future<void> delete({required String target, required bool isSaf}) async {}

  @override
  Future<void> moveToTrash({
    required String target,
    required bool isSaf,
    String? safRootUri,
  }) async {}

  @override
  Future<List<StoraxTrashEntry>> listTrash() async {
    return [];
  }

  @override
  Future<void> restoreFromTrash(StoraxTrashEntry entry) async {}

  @override
  Future<void> emptyTrash({required bool isSaf, String? safRootUri}) async {}

  @override
  Future<void> openFile({String? path, String? mime, String? uri}) async {}

  @override
  Future<List<Uint8List>?> generateGifThumbnail({
    required String videoPath,
    int? width,
    int? height,
    int frameCount = 10,
  }) async {
    return null;
  }


  @override
  Future<List<Uint8List>?> generateUniqueFrame({
    required String videoPath,
    int? width,
    int? height,
  }) async {
    return null;
  }

}

void main() {
  final StoraxPlatform initialPlatform = StoraxPlatform.instance;

  test('MethodChannelStorax is the default platform implementation', () {
    expect(StoraxPlatform.instance, isInstanceOf<MethodChannelStorax>());
  });

  test('Storax delegates all calls to the platform interface', () async {
    final storax = Storax();
    final fakePlatform = MockStoraxPlatform();

    // Inject mock platform
    StoraxPlatform.instance = fakePlatform;

    // Roots
    final roots = await storax.getNativeRoots();
    expect(roots.length, 1);
    expect(roots.first.name, 'Mock storage');

    // Directory listing
    final files = await storax.listDirectory(
      target: '/mock/path',
      isSaf: false,
    );
    expect(files.length, 1);
    expect(files.first.name, 'mock.txt');

    // Permissions
    final hasAccess = await storax.hasAllFilesAccess();
    expect(hasAccess, isTrue);

    final health = await storax.permissionHealthCheck();
    expect(health['allFilesAccess'], true);

    // OEM
    final oem = await storax.detectOEM();
    expect(oem?.manufacturer, 'Mock');

    // Copy
    final copyJobId = await storax.copy(
      source: '/a',
      destination: '/b',
      isSaf: false,
    );
    expect(copyJobId, 'job_copy_123');

    // Move
    final moveJobId = await storax.move(
      source: '/a',
      destination: '/b',
      isSaf: false,
    );
    expect(moveJobId, 'job_move_123');

    // Rename
    await storax.rename(
      target: '/a/file.txt',
      newName: 'renamed.txt',
      isSaf: false,
    );

    // Delete
    await storax.delete(target: '/a/file.txt', isSaf: false);

    // Restore original platform
    StoraxPlatform.instance = initialPlatform;
  });

  test('generateGifThumbnail', () async {
    final frames = await Storax().generateGifThumbnail(
      videoPath: '/storage/emulated/0/Download/video.mp4',
      width: 100,
      height: 100,
      frameCount: 10,
    );
    expect(frames, isNotNull);
    expect(frames!.length, 10);
  });

}
