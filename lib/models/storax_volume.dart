import 'package:flutter/foundation.dart';
import 'package:storax/models/storax_mode.dart';

/// Represents a storage root (internal / SD / USB / SAF-picked folder)
@immutable
class StoraxVolume {
  final StoraxMode mode;
  final String name;

  /// Native filesystem path (ONLY for native access)
  final String? path;

  /// SAF tree/document URI (ONLY for SAF access)
  final String? uri;

  final int total;
  final int free;
  final int used;
  final bool writable;

  const StoraxVolume({
    required this.mode,
    required this.name,
    this.path,
    this.uri,
    required this.total,
    required this.free,
    required this.used,
    required this.writable,
  }) : assert(
         (path == null) ^ (uri == null),
         'Exactly one of path or uri must be set',
       );

  /// Convenience flags
  bool get isSaf => mode.isSaf;
  bool get isNative => mode.isNative;

  factory StoraxVolume.fromMap(Map<String, dynamic> map) {
    return StoraxVolume(
      mode: StoraxMode.fromString(map['type'] ?? map['storageType']),
      name: map['name'] as String? ?? '',
      path: map['path'] as String?,
      uri: map['uri'] as String?,
      total: (map['total'] ?? 0) as int,
      free: (map['free'] ?? 0) as int,
      used: (map['used'] ?? 0) as int,
      writable: (map['writable'] ?? false) as bool,
    );
  }

  Map<String, dynamic> toMap() => {
    'type': mode.value,
    'name': name,
    'path': path,
    'uri': uri,
    'total': total,
    'free': free,
    'used': used,
    'writable': writable,
  };

  StoraxVolume copyWith({
    StoraxMode? accessType,
    String? name,
    String? path,
    String? uri,
    int? total,
    int? free,
    int? used,
    bool? writable,
  }) {
    return StoraxVolume(
      mode: accessType ?? this.mode,
      name: name ?? this.name,
      path: path ?? this.path,
      uri: uri ?? this.uri,
      total: total ?? this.total,
      free: free ?? this.free,
      used: used ?? this.used,
      writable: writable ?? this.writable,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is StoraxVolume &&
          runtimeType == other.runtimeType &&
          mode == other.mode &&
          name == other.name &&
          path == other.path &&
          uri == other.uri &&
          total == other.total &&
          free == other.free &&
          used == other.used &&
          writable == other.writable;

  @override
  int get hashCode =>
      Object.hash(mode, name, path, uri, total, free, used, writable);

  @override
  String toString() =>
      'StorageVolume('
      'accessType: $mode, '
      'name: $name, '
      'path: $path, '
      'uri: $uri, '
      'total: $total, '
      'free: $free, '
      'used: $used, '
      'writable: $writable'
      ')';
}
