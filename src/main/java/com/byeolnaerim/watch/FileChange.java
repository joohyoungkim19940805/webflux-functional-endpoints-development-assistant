package com.byeolnaerim.watch;


import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;


/**
 * Immutable file-system change detected by a watcher.
 */
public record FileChange(Path path, WatchEvent.Kind<?> kind, String source) {

	public FileChange {

		if (path != null) {
			path = path.toAbsolutePath().normalize();

		}

	}

	public boolean isCreate() {

		return kind == StandardWatchEventKinds.ENTRY_CREATE;

	}

	public boolean isModify() {

		return kind == StandardWatchEventKinds.ENTRY_MODIFY;

	}

	public boolean isDelete() {

		return kind == StandardWatchEventKinds.ENTRY_DELETE;

	}

	public boolean isOverflow() {

		return kind == StandardWatchEventKinds.OVERFLOW;

	}

	public boolean isJavaSource() {

		if (path == null || path.getFileName() == null) {
			return false;

		}

		return path.getFileName().toString().toLowerCase().endsWith( ".java" );

	}

	/**
	 * Returns whether the change can alter the set or location of source types.
	 * Directory create/delete events are structural even though the deleted path
	 * can no longer be inspected with {@code Files.isDirectory}.
	 */
	public boolean isStructuralSourceChange() {

		if (isOverflow()) {
			return true;

		}

		if (! isCreate() && ! isDelete()) {
			return false;

		}

		if (isJavaSource()) {
			return true;

		}

		if (path == null || path.getFileName() == null) {
			return true;

		}

		String fileName = path.getFileName().toString();
		return ! fileName.contains( "." );

	}

}
