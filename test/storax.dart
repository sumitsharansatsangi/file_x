import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'package:storax/src/platform/storax_method_channel.dart';
import 'package:storax/src/platform/storax_platform_interface.dart';
import 'package:storax/storax.dart';

/// Mock implementation matching CURRENT StoraxPlatform interface.
class MockStoraxPlatform
    with MockPlatformInterfaceMixin
    implements StoraxPlatform {
  // ───────────────── ROOTS ─────────────────

  @override
  Future<List<StoraxVolume>> getNativeRoots() async => [
    StoraxVolume(
      mode: StoraxMode.native,
      name: 'Mock storage',
      path: '/mock/path',
      uri: null,
      total: 100,
      free: 50,
      used: 50,
      writable: true,
    ),
  ];

  @override
  Future<List<StoraxVolume>> getSafRoots() async => [];

  @override
  Future<List<StoraxVolume>> getAllRoots() async => getNativeRoots();

  @override
  Future<StoraxDeviceCapabilities?> getDeviceCapabilities() async =>
      const StoraxDeviceCapabilities(
        manufacturer: 'Mock',
        brand: 'MockBrand',
        model: 'MockModel',
        sdk: 34,
        hasAllFilesAccess: true,
        supportsScopedStorage: true,
        usbSupported: true,
        androidVersion: 'Android 14',
      );

  // ───────────────── PERMISSIONS ─────────────────

  @override
  Future<bool> hasAllFilesAccess() async => true;

  @override
  Future<void> requestAllFilesAccess() async {}

  // ───────────────── SAF ─────────────────

  @override
  Future<void> openSafFolderPicker() async {}

  // ───────────────── DIRECTORY ─────────────────

  @override
  Future<List<StoraxEntry>> listDirectory({required String target}) async => [
    StoraxEntry(
      name: 'mock.txt',
      path: '/mock/path/mock.txt',
      uri: null,
      isDirectory: false,
      size: 123,
      lastModified: 1700000000000,
      mode: StoraxMode.native,
    ),
  ];

  @override
  Future<List<StoraxEntry>> traverseDirectory({
    required String target,
    int maxDepth = -1,
  }) async => listDirectory(target: target);

  // ───────────────── CREATE ─────────────────

  @override
  Future<Map<String, dynamic>> create({
    required String parent,
    required String name,
    required int type,
    int conflictPolicy = 0,
    String? manualRename,
  }) async => {"success": true, "finalName": name, "path": "$parent/$name"};

  // ───────────────── COPY ─────────────────

  @override
  Future<dynamic> copy({
    required String source,
    required String destinationParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) async => "job_copy_123";

  @override
  Future<bool> cancelCopy(String jobId) async => true;

  @override
  Future<bool> pauseCopy(String jobId) async => true;

  @override
  Future<bool> resumeCopy(String jobId) async => true;

  // ───────────────── MOVE / RENAME ─────────────────

  @override
  Future<bool> move({
    required String source,
    required String destParent,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) async => true;

  @override
  Future<bool> rename({
    required String source,
    required String newName,
    int conflictPolicy = 0,
    String? manualRename,
  }) async => true;

  // ───────────────── DELETE / TRASH ─────────────────

  @override
  Future<bool> delete({required String target}) async => true;

  @override
  Future<bool> permanentlyDelete({required String path}) async => true;

  @override
  Future<List<StoraxTrashEntry>> listTrash() async => [
    StoraxTrashEntry(
      id: 'mock_trash_1',
      name: 'deleted_photo.jpg',
      isSaf: false,
      isDirectory: false,
      trashedAt: DateTime.now(),
      originalPath: '/storage/emulated/0/DCIM/deleted_photo.jpg',
      trashedPath: '/trash/deleted_photo.jpg',
    ),
  ];

  @override
  Future<bool> restoreFromTrash(StoraxTrashEntry entry) async => true;

  @override
  Future<bool> permanentlyDeleteFromTrash(StoraxTrashEntry entry) async => true;

  @override
  Future<bool> emptyTrash() async => true;

  // ───────────────── UNDO / REDO ─────────────────

  @override
  Future<bool> undo() async => true;

  @override
  Future<bool> redo() async => true;

  @override
  Future<bool> canUndo() async => true;

  @override
  Future<bool> canRedo() async => true;

  @override
  Future<int> undoCount() async => 1;

  @override
  Future<int> redoCount() async => 1;

  @override
  Future<void> clearUndo() async {}

  // ───────────────── FILE OPEN ─────────────────

  @override
  Future<void> openFile({String? path, String? uri, String? mime}) async {}

  // ───────────────── MEDIA ─────────────────

  @override
  Future<List<Uint8List>?> generateVideoThumbnail({
    required String videoPath,
    int? width,
    int? height,
    int frameCount = 5,
  }) async => List.generate(frameCount, (_) => Uint8List.fromList([0, 1, 2]));
}

void main() {
  final StoraxPlatform initialPlatform = StoraxPlatform.instance;
  late MockStoraxPlatform fakePlatform;

  setUp(() {
    fakePlatform = MockStoraxPlatform();
    StoraxPlatform.instance = fakePlatform;
  });

  tearDown(() {
    StoraxPlatform.instance = initialPlatform;
  });

  test('MethodChannelStorax is default platform implementation', () {
    expect(initialPlatform, isInstanceOf<MethodChannelStorax>());
  });

  group('Storax Delegation Tests', () {
    test('File system discovery methods work', () async {
      final roots = await Storax.instance.getNativeRoots();
      expect(roots.length, 1);

      final files = await Storax.instance.listDirectory(target: '/');
      expect(files.first.name, 'mock.txt');
    });

    test('Copy returns jobId', () async {
      final copyId = await Storax.instance.copy(
        source: 's',
        destinationParent: 'd',
        newName: 'n',
      );
      expect(copyId, 'job_copy_123');
    });

    test('DeviceCapabilities works', () async {
      final caps = await Storax.instance.getDeviceCapabilities();
      expect(caps?.manufacturer, 'Mock');
      expect(caps?.sdk, 34);
    });

    test('Trash operations work', () async {
      final trash = await Storax.instance.listTrash();
      expect(trash.length, 1);

      await Storax.instance.restoreFromTrash(trash.first);
      await Storax.instance.emptyTrash();
    });

    test('formatBytes works', () {
      expect(Storax.instance.formatBytes(0), "0 B");
      expect(Storax.instance.formatBytes(1024), "1.00 KB");
    });

    test('Media generation returns mock frames', () async {
      final thumb = await Storax.instance.generateVideoThumbnail(
        videoPath: 'v',
      );
      expect(thumb?.length, 5);
    });
  });
}
