sealed class StoraxEvent {
  const StoraxEvent();
}

class UsbAttachedEvent extends StoraxEvent {
  const UsbAttachedEvent();
}

class UsbDetachedEvent extends StoraxEvent {
  const UsbDetachedEvent();
}

class SafPickedEvent extends StoraxEvent {
  final String uri;
  const SafPickedEvent(this.uri);
}

class TransferProgressEvent extends StoraxEvent {
  final String jobId;
  final double percent;
  const TransferProgressEvent(this.jobId, this.percent);
}

class UndoStateChangedEvent extends StoraxEvent {
  final bool canUndo;
  final bool canRedo;
  const UndoStateChangedEvent(this.canUndo, this.canRedo);
}
