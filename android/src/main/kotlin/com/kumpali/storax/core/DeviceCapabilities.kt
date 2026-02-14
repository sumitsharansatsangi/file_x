package com.kumpali.storax.core

data class DeviceCapabilities(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val sdk: Int,
    val hasAllFilesAccess: Boolean,
    val supportsScopedStorage: Boolean,
    val usbSupported: Boolean,
    val androidVersion: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "manufacturer" to manufacturer,
        "brand" to brand,
        "model" to model,
        "sdk" to sdk,
        "hasAllFilesAccess" to hasAllFilesAccess,
        "supportsScopedStorage" to supportsScopedStorage,
        "usbSupported" to usbSupported,
        "android" to androidVersion
    )
}
