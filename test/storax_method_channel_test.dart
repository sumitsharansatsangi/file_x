import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storax/models/storax_volume.dart';
import 'package:storax/storax_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('storax');
  final MethodChannelStorax platform = MethodChannelStorax();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
          switch (call.method) {
            case 'getNativeRoots':
              return [
                {
                  'type': 'native',
                  'name': 'Internal storage',
                  'path': '/storage/emulated/0',
                  'total': 1000,
                  'free': 500,
                  'used': 500,
                  'writable': true,
                },
              ];

            case 'hasAllFilesAccess':
              return true;

            case 'listDirectory':
              return [
                {
                  'name': 'file.txt',
                  'path': '/storage/emulated/0/file.txt',
                  'uri': null,
                  'isDirectory': false,
                  'size': 123,
                  'lastModified': 1700000000000,
                  'mime': 'text/plain',
                  'storageType': 'native',
                },
              ];

            default:
              throw PlatformException(
                code: 'UNIMPLEMENTED',
                message: 'Method ${call.method} not mocked',
              );
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('MethodChannelStorax', () {
    test('getNativeRoots returns parsed roots', () async {
      final roots = await platform.getNativeRoots();

      expect(roots, isA<List<StoraxVolume>>());
      expect(roots.length, 1);
      expect(roots.first.name, 'Internal storage');
      expect(roots.first.path, '/storage/emulated/0');
    });

    test('hasAllFilesAccess returns true', () async {
      final hasAccess = await platform.hasAllFilesAccess();
      expect(hasAccess, isTrue);
    });

    test('listDirectory returns directory entries', () async {
      final files = await platform.listDirectory(
        target: '/storage/emulated/0',
        isSaf: false,
      );

      expect(files.length, 1);
      expect(files.first.name, 'file.txt');
      expect(files.first.isDirectory, false);
    });
  });
}
