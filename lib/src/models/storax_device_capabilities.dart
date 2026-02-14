import 'package:flutter/material.dart';

/// Represents device-level storage and environment capabilities.
///
/// Mirrors the Kotlin `DeviceCapabilities` data class
/// from the native Android layer.
///
/// This model describes the device hardware, Android version,
/// and storage-related behavior flags.
@immutable
class StoraxDeviceCapabilities {
  /// Manufacturer (e.g. "Samsung", "Xiaomi")
  final String manufacturer;

  /// Brand (e.g. "samsung", "redmi")
  final String brand;

  /// Device model (e.g. "SM-G991B")
  final String model;

  /// Android SDK integer (e.g. 33)
  final int sdk;

  /// Whether MANAGE_EXTERNAL_STORAGE is granted
  final bool hasAllFilesAccess;

  /// Whether scoped storage restrictions apply
  /// (true for Android 10+)
  final bool supportsScopedStorage;

  /// Whether USB OTG is supported on this device
  final bool usbSupported;

  /// Android version string (e.g. "13")
  final String androidVersion;

  const StoraxDeviceCapabilities({
    required this.manufacturer,
    required this.brand,
    required this.model,
    required this.sdk,
    required this.hasAllFilesAccess,
    required this.supportsScopedStorage,
    required this.usbSupported,
    required this.androidVersion,
  });

  /// Creates instance from native Map.
  factory StoraxDeviceCapabilities.fromMap(Map<String, dynamic> map) {
    return StoraxDeviceCapabilities(
      manufacturer: map['manufacturer'] ?? '',
      brand: map['brand'] ?? '',
      model: map['model'] ?? '',
      sdk: map['sdk'] ?? 0,
      hasAllFilesAccess: map['hasAllFilesAccess'] ?? false,
      supportsScopedStorage: map['supportsScopedStorage'] ?? false,
      usbSupported: map['usbSupported'] ?? false,
      androidVersion: map['android'] ?? '',
    );
  }

  /// Converts object back to Map.
  Map<String, dynamic> toMap() => {
    'manufacturer': manufacturer,
    'brand': brand,
    'model': model,
    'sdk': sdk,
    'hasAllFilesAccess': hasAllFilesAccess,
    'supportsScopedStorage': supportsScopedStorage,
    'usbSupported': usbSupported,
    'android': androidVersion,
  };
}
