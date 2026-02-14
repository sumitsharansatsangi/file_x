import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storax/src/models/storax_volume.dart';
import 'package:storax/src/models/storax_entry.dart';
import 'package:storax/src/platform/storax_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('storax');
  final MethodChannelStorax platform = MethodChannelStorax();

  MethodCall? lastCall;

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
          lastCall = call;

          switch (call.method) {
            case 'getNativeRoots':
              return [
                {
                  'type': 'native',
                  'name': 'Internal storage',
                  'path': '/storage/emulated/0',
                  'uri': null,
                  'totalBytes': 1000,
                  'freeBytes': 500,
                  'usedBytes': 500,
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
                },
              ];

            case 'copy':
            case 'move':
              return 'job_id_123';

            case 'rename':
              return true;

            case 'delete':
            case 'permanentlyDelete':
              return true;

            case 'undo':
            case 'redo':
            case 'canUndo':
            case 'canRedo':
              return true;

            case 'undoCount':
            case 'redoCount':
              return 1;

            case 'clearUndo':
              return null;

            case 'getDeviceCapabilities':
              return {
                'manufacturer': 'Samsung',
                'brand': 'Galaxy',
                'model': 'S23',
                'sdk': 33,
                'hasAllFilesAccess': true,
                'supportsScopedStorage': true,
                'usbSupported': true,
                'android': 'Android 13',
              };

            case 'openFile':
              return null;

            default:
              return null;
          }
        });
  });

  tearDown(() {
    lastCall = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('MethodChannelStorax Communication', () {
    test('getNativeRoots parses response correctly', () async {
      final roots = await platform.getNativeRoots();

      expect(roots, isA<List<StoraxVolume>>());
      expect(roots.length, 1);
      expect(roots.first.name, 'Internal storage');
    });

    test('listDirectory sends correct arguments', () async {
      const targetPath = '/storage/emulated/0/Download';

      final files = await platform.listDirectory(target: targetPath);

      expect(lastCall?.method, 'listDirectory');
      expect(lastCall?.arguments['target'], targetPath);

      expect(files.first, isA<StoraxEntry>());
    });

    test('copy returns jobId', () async {
      final jobId = await platform.copy(
        source: '/src.txt',
        destinationParent: '/storage/emulated/0',
        newName: 'dest.txt',
      );

      expect(lastCall?.method, 'copy');
      expect(lastCall?.arguments['source'], '/src.txt');
      expect(jobId, 'job_id_123');
    });

    test('delete sends correct target', () async {
      await platform.delete(target: '/trash/me.txt');

      expect(lastCall?.method, 'delete');
      expect(lastCall?.arguments['target'], '/trash/me.txt');
    });

    test('rename calls native correctly', () async {
      final result = await platform.rename(source: '/a.txt', newName: 'b.txt');

      expect(lastCall?.method, 'rename');
      expect(result, true);
    });

    test('undo triggers native', () async {
      final result = await platform.undo();

      expect(lastCall?.method, 'undo');
      expect(result, true);
    });

    test('device capabilities parsing works', () async {
      final caps = await platform.getDeviceCapabilities();

      expect(caps?.manufacturer, 'Samsung');
      expect(caps?.sdk, 33);
    });

    test('openFile passes arguments correctly', () async {
      await platform.openFile(path: '/some/path', mime: 'text/plain');

      expect(lastCall?.method, 'openFile');
      expect(lastCall?.arguments['path'], '/some/path');
      expect(lastCall?.arguments['uri'], isNull);
    });
  });
}
