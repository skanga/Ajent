package com.github.skanga.ajent.tools.attachment;

import com.github.skanga.ajent.domain.Attachment;
import java.util.Optional;

/** Clipboard boundary used by Ajent's image-first smart-paste action. */
public interface ClipboardReader {
  Optional<Attachment> image();
  Optional<String> text();
}
