import 'package:flutter/foundation.dart';

@immutable
class StoraxOem {
  final String manufacturer;
  final String brand;
  final String model;
  final int sdk;

  const StoraxOem({
    required this.manufacturer,
    required this.brand,
    required this.model,
    required this.sdk,
  });

  factory StoraxOem.fromMap(Map<String, dynamic> map) {
    return StoraxOem(
      manufacturer: map['manufacturer'] as String? ?? '',
      brand: map['brand'] as String? ?? '',
      model: map['model'] as String? ?? '',
      sdk: int.tryParse(map['sdk']?.toString() ?? '') ?? 0,
    );
  }

  Map<String, dynamic> toMap() => {
    'manufacturer': manufacturer,
    'brand': brand,
    'model': model,
    'sdk': sdk,
  };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is StoraxOem &&
          runtimeType == other.runtimeType &&
          manufacturer == other.manufacturer &&
          brand == other.brand &&
          model == other.model &&
          sdk == other.sdk;

  @override
  int get hashCode => Object.hash(manufacturer, brand, model, sdk);

  @override
  String toString() =>
      'StoraxOem('
      'manufacturer: $manufacturer, '
      'brand: $brand, '
      'model: $model, '
      'sdk: $sdk'
      ')';
}
