# storax

Android storage access for Flutter — **SAF-aware, OEM-safe, and honest**.

[![Pub Version](https://img.shields.io/pub/v/storax.svg)](https://pub.dev/packages/storax)
[![License](https://img.shields.io/github/license/sumitsharansatsangi/storax.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/sumitsharansatsangi/storax.svg?style=social)](https://github.com/sumitsharansatsangi/storax)

`storax` is an **Android-only Flutter plugin** that provides a **correct, OEM-aware, SAF-compliant storage layer** for real-world apps.

It is designed for developers who need **truthful filesystem behavior**, not shortcuts that break across Android versions or OEMs.

> This is **not** a file picker wrapper.
> It is a storage abstraction that understands Android’s real security model.

---

## Why storax exists

On modern Android:

* Paths may exist but be unreadable
* File explorers can see files your app cannot
* USB behaves differently across OEMs
* “All Files Access” does **not** mean *all files*
* Native paths and SAF must coexist

Most plugins **hide these realities**.
`storax` exposes them explicitly and predictably.

---

## Core Capabilities

### 🔹 Unified Storage Roots

Retrieve a merged list of storage roots as `StoraxVolume`:

* Internal storage
* External SD card (where allowed)
* USB / OTG devices
* Adopted storage
* User-selected SAF folders

```dart
final roots = await storax.getAllRoots();
```

Each `StoraxVolume` includes:

* `mode` (`native` / `saf`)
* Native `path` **or** SAF `uri`
* Writable capability
* Storage statistics (native only)

---

### 🔹 Native + SAF Directory Browsing

List directory contents and receive `StoraxEntry` objects:

```dart
await storax.listDirectory(
  target: pathOrUri,
  isSaf: trueOrFalse,
);
```

✔ Non-recursive
✔ UI-safe
✔ OEM-tolerant
✔ Path-based **and** URI-based access

---

### 🔹 Recursive Traversal (Off UI Thread)

Used for search, indexing, and analytics:

```dart
await storax.traverseDirectory(
  target: pathOrUri,
  isSaf: true,
  maxDepth: 5,
  filters: {
    "extensions": ["pdf", "jpg"],
    "minSize": 1024,
  },
);
```

* Depth-limited
* Filter-aware
* Executed on a native worker thread (no ANRs)

---

### 🔹 Path → SAF Resolution (Critical)

When opening a file by **path**, `storax`:

1. Detects whether the path belongs to a persisted SAF tree
2. Resolves it transparently to a SAF document URI
3. Falls back to FileProvider **only when valid**

This avoids common crashes on Android 11+.

```dart
await storax.openFile(path: "/storage/...");
```

---

### 🔹 File Opening (User-Safe)

Supports:

* Native filesystem paths
* SAF document URIs
* `file://` URIs (where valid)

With:

* MIME type detection
* URI permission propagation
* Chooser-based opening

```dart
await storax.openFile(
  path: filePath,
  mime: "application/pdf",
);
```

---

### 🔹 SAF Folder Picker

Used when native access is restricted:

```dart
await storax.openSafFolderPicker();
```

* Persisted permissions
* Emits selection events
* Required for many SD / USB scenarios

---

### 🔹 USB Attach / Detach Events

Detects:

* USB device attach
* USB removal
* Filesystem mount / unmount

```dart
storax.events.listen((event) {
  if (event.type == StoraxEventType.usbAttached) {
    // Refresh roots or request SAF access
  }
});
```

⚠️ USB access is **never automatic** — user permission is always required.

---

### 🔹 Permission Handling (Honest)

```dart
final hasAccess = await storax.hasAllFilesAccess();
await storax.requestAllFilesAccess();
```

* Correct for Android 11+
* Does **not** assume permission equals access
* SAF remains authoritative where required

---

### 🔹 OEM Diagnostics

```dart
final oem = await storax.detectOEM(); // StoraxOem
final health = await storax.permissionHealthCheck();
```

Useful for:

* Debug screens
* Support logs
* OEM-specific behavior analysis

---

## Architecture Highlights

* Single-threaded native IO executor
* Zero blocking on UI thread
* Defensive OEM handling
* SAF permission persistence
* Cache-assisted SAF path resolution
* Explicit error reporting (no silent failures)

---

## What storax does NOT do

* ❌ No background filesystem scanning
* ❌ No silent access to USB storage
* ❌ No bypass of `/Android/data` or `/Android/obb`
* ❌ No fake “Recent files” reconstruction
* ❌ No OEM-only privileged APIs

These are **system-level restrictions** and cannot be bypassed safely.

---

## Known Unavoidable Android / OEM Limitations

Some restrictions are enforced at:

* SELinux
* Kernel
* Privileged system app level

Including:

* Access to `/Android/data`
* Automatic USB enumeration
* Background traversal limits
* OEM file-manager inconsistencies

`storax` avoids unsafe or brittle workarounds by design.

---

## Supported Platforms

| Platform | Support         |
| -------- | --------------- |
| Android  | ✅ Yes           |
| iOS      | ❌ Not supported |
| Web      | ❌ Not supported |
| Desktop  | ❌ Not supported |

This plugin is **intentionally Android-only**.

---

## Who should use this plugin

* File managers
* Backup / restore tools
* Document-heavy apps
* Media utilities
* OEM / enterprise tooling
* Power-user utilities

If your app needs **correct storage behavior**, not illusions — this is for you.

---

## License

MIT

---

## Philosophy

> Respect the OS.
> Fail loudly.
> Never lie about access.

---

## 👨‍💻 Author

[![Sumit Kumar](https://github.com/sumitsharansatsangi.png?size=100)](https://github.com/sumitsharansatsangi)
**[Sumit Kumar](https://github.com/sumitsharansatsangi)**

---
