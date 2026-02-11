## 0.0.8

* New: Added `generateUniqueFrame` method
* New: Added `generateGifThumbnail` method

## 0.0.7

* Breaking change:
   *  `openFile` now takes `path` and `mime` parameters only.

## 0.0.6

* New: Added `getPlatformVersion` and `getSDKIntVersion` methods

## 0.0.5

* New: Added `moveToTrash`, `listTrash`, `restoreFromTrash`, `emptyTrash` methods
* New: Added `copy`, `rename`, `createFolder`, `createFile`, `delete` methods

## 0.0.4

* Fix: Limit public API visibility to prevent unintended usage

## 0.0.3

* Breaking change:
   *  `getNativeRoots` now returns `StoraxVolume` instead of `Map<String, dynamic>`
   *  `getAllRoots` now returns `StoraxVolume` instead of `Map<String, dynamic>`
   *  `listDirectory` now returns `StoraxEntry` instead of `Map<String, dynamic>`
   *  `traverseDirectory` now returns `StoraxEntry` instead of `Map<String, dynamic>`
   *  `detectOEM` now returns `StoraxOem` instead of `Map<String, dynamic>`

## 0.0.2

* Dependency updates.

## 0.0.1

* Initial release.
