class StoraxTrashEntry {
  final String id;
  final String name;
  final bool isSaf;
  final bool isDirectory;
  final DateTime trashedAt;
  final int? size;

  // Native
  final String? originalPath;
  final String? trashedPath;

  // SAF
  final String? originalUri;
  final String? trashedUri;
  final String? safRootUri;

  StoraxTrashEntry({
    required this.id,
    required this.name,
    required this.isSaf,
    required this.isDirectory,
    required this.trashedAt,
    this.size ,
    this.originalPath,
    this.trashedPath,
    this.originalUri,
    this.trashedUri,
    this.safRootUri,
  });

  factory StoraxTrashEntry.fromMap(Map<String, dynamic> map) {
    return StoraxTrashEntry(
      id: map['id'] as String,
      name: map['name'] as String,
      isSaf: map['isSaf'] as bool,
      isDirectory: map['isDirectory'] as bool,
      trashedAt: DateTime.fromMillisecondsSinceEpoch(map['trashedAt'] as int),
      size: map['size'] as int?,
      originalPath: map['originalPath'] as String?,
      trashedPath: map['trashedPath'] as String?,
      originalUri: map['originalUri'] as String?,
      trashedUri: map['trashedUri'] as String?,
      safRootUri: map['safRootUri'] as String?,
    );
  }

  Map<String, dynamic> toMap() => {
    'id': id,
    'name': name,
    'isSaf': isSaf,
    'isDirectory': isDirectory,
    'trashedAt': trashedAt.millisecondsSinceEpoch,
    'size': size,
    'originalPath': originalPath,
    'trashedPath': trashedPath,
    'originalUri': originalUri,
    'trashedUri': trashedUri,
    'safRootUri': safRootUri,
  };
}
