package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.Attachment;

/** Resolves lazy FileRef/Symbol bodies at provider projection time. */
@FunctionalInterface
public interface AttachmentContentPort {
  byte[] body(Attachment attachment);

  static AttachmentContentPort inline() { return Attachment::body; }
}
