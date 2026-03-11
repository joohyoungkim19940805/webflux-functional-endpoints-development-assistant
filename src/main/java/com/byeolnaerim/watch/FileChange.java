package com.byeolnaerim.watch;


import java.nio.file.Path;
import java.nio.file.WatchEvent;


/**
 * Returns the file-system event kinds to monitor.
 * <p>The default implementation watches create and modify events.</p>
 *
 * @return the monitored event kinds
 */
public record FileChange(Path path, WatchEvent.Kind<?> kind, String source) {}
