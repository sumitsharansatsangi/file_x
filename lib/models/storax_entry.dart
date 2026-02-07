import 'package:flutter/foundation.dart';
import 'package:storax/models/storax_mode.dart';

/// Represents a file or directory returned by listDirectory / traverseDirectory
@immutable
class StoraxEntry {
  final String name;

  /// Native filesystem path (native only)
  final String? path;

  /// SAF document URI (SAF only)
  final String? uri;

  final bool isDirectory;
  final int size; // bytes (0 for directories)
  final int lastModified; // epoch millis
  final String? mime;

  final StoraxMode mode;

  const StoraxEntry({
    required this.name,
    required this.isDirectory,
    required this.size,
    required this.lastModified,
    required this.mode,
    this.path,
    this.uri,
    this.mime,
  }) : assert(
         (path == null) ^ (uri == null),
         'Exactly one of path or uri must be set',
       );

  /// Convenience
  bool get isSaf => mode.isSaf;
  bool get isNative => mode.isNative;

  /// Stable unique identifier (safe for lists, caching, selection)
  String get id => uri ?? path!;

  factory StoraxEntry.fromMap(Map<String, dynamic> map) {
    return StoraxEntry(
      name: map['name'] as String? ?? '',
      path: map['path'] as String?,
      uri: map['uri'] as String?,
      isDirectory: map['isDirectory'] as bool? ?? false,
      size: (map['size'] ?? 0) as int,
      lastModified: (map['lastModified'] ?? 0) as int,
      mime: map['mime'] as String?,
      mode: StoraxMode.fromString(map['storageType']),
    );
  }

  Map<String, dynamic> toMap() => {
    'name': name,
    'path': path,
    'uri': uri,
    'isDirectory': isDirectory,
    'size': size,
    'lastModified': lastModified,
    'mime': mime,
    'storageType': mode.value,
  };

  StoraxEntry copyWith({
    String? name,
    String? path,
    String? uri,
    bool? isDirectory,
    int? size,
    int? lastModified,
    String? mime,
    StoraxMode? accessType,
  }) {
    return StoraxEntry(
      name: name ?? this.name,
      path: path ?? this.path,
      uri: uri ?? this.uri,
      isDirectory: isDirectory ?? this.isDirectory,
      size: size ?? this.size,
      lastModified: lastModified ?? this.lastModified,
      mime: mime ?? this.mime,
      mode: accessType ?? this.mode,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is StoraxEntry &&
          runtimeType == other.runtimeType &&
          name == other.name &&
          path == other.path &&
          uri == other.uri &&
          isDirectory == other.isDirectory &&
          size == other.size &&
          lastModified == other.lastModified &&
          mime == other.mime &&
          mode == other.mode;

  @override
  int get hashCode =>
      Object.hash(name, path, uri, isDirectory, size, lastModified, mime, mode);

  @override
  String toString() =>
      'StorageEntry('
      'name: $name, '
      'path: $path, '
      'uri: $uri, '
      'isDirectory: $isDirectory, '
      'size: $size, '
      'lastModified: $lastModified, '
      'mime: $mime, '
      'accessType: $mode'
      ')';
}
