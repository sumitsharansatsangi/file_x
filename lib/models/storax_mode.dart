/// How this storage is accessed on Android
/// Must align with:
/// - "storageType" in directory entries
/// - "type" in roots
enum StoraxMode {
  native, // File(path)
  saf, // DocumentFile(content://)
  unknown;

  String get value => name;

  static StoraxMode fromString(String? value) {
    if (value == null) return StoraxMode.unknown;
    return StoraxMode.values.firstWhere(
      (e) => e.name == value,
      orElse: () => StoraxMode.unknown,
    );
  }

  bool get isSaf => this == StoraxMode.saf;
  bool get isNative => this == StoraxMode.native;
}
