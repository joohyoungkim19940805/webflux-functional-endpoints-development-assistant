package com.starbearing.watch;


import java.nio.file.Path;
import java.nio.file.WatchEvent;


public record FileChange(Path path, WatchEvent.Kind<?> kind, String source) {}
