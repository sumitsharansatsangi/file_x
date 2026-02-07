import 'package:flutter_test/flutter_test.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:storax/models/storax_mode.dart';
import 'package:storax/models/storax_oem.dart';
import 'package:storax/models/storax_volume.dart';
import 'package:storax/models/storax_entry.dart';
import 'package:storax/storax_method_channel.dart';
import 'package:storax/storax_platform_interface.dart';
import 'package:storax/storax.dart';

/// A mock platform implementation used to verify that
/// [Storax] delegates calls to [StoraxPlatform.instance].
class MockStoraxPlatform
    with MockPlatformInterfaceMixin
    implements StoraxPlatform {
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
    return await getNativeRoots();
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
    return await listDirectory(target: target, isSaf: isSaf);
  }

  @override
  Future<void> openSafFolderPicker() async {}

  @override
  Future<bool> hasAllFilesAccess() async => true;

  @override
  Future<void> requestAllFilesAccess() async {}

  @override
  Future<StoraxOem?> detectOEM() async {
    return  StoraxOem(
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

  @override
  Future<void> openFile({String? path, String? mime, String? uri}) {
    return Future.value();
  }
}

void main() {
  final StoraxPlatform initialPlatform = StoraxPlatform.instance;

  test('$MethodChannelStorax is the default platform implementation', () {
    expect(initialPlatform, isInstanceOf<MethodChannelStorax>());
  });

  test('Storax delegates calls to the platform interface', () async {
    final storax = Storax();
    final fakePlatform = MockStoraxPlatform();

    // Inject mock platform
    StoraxPlatform.instance = fakePlatform;

    final roots = await storax.getNativeRoots();
    final files = await storax.listDirectory(
      target: '/mock/path',
      isSaf: false,
    );
    final hasAccess = await storax.hasAllFilesAccess();
    final oem = await storax.detectOEM();

    expect(roots.first.name, 'Mock storage');
    expect(files.first.name, 'mock.txt');
    expect(hasAccess, isTrue);
    expect(oem?.manufacturer, 'Mock');

    // Restore original platform
    StoraxPlatform.instance = initialPlatform;
  });
}
