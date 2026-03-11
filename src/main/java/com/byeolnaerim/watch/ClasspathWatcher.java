package com.byeolnaerim.watch;


import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/**
 * Watches classpath-related outputs and configuration resources that may require
 * an application reload.
 * <p>This watcher filters raw file-system events down to relevant runtime artifacts
 * such as compiled classes, configuration files, and JAR files.</p>
 */
public final class ClasspathWatcher extends AbstractWatcher {

	private final Path root;

	private final Path triggerPath;

	/**
	 * Creates a classpath watcher.
	 *
	 * @param root
	 *            the classpath root to watch
	 * @param triggerPath
	 *            the reload-trigger file to ignore in order to prevent self-trigger loops
	 */
	public ClasspathWatcher(
							Path root,
							Path triggerPath
	) {

		this.root = root.toAbsolutePath().normalize();
		this.triggerPath = triggerPath == null ? null : triggerPath.toAbsolutePath().normalize();

	}

	@Override
	protected Path root() {

		return root;

	}

	@Override
	protected WatchEvent.Kind<?>[] kinds() {

		return new WatchEvent.Kind<?>[] {
			StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE
		};

	}

	/**
	 * Returns only classpath-relevant file changes.
	 *
	 * @return a filtered stream of classpath-related file changes
	 */
	@Override
	public Flux<FileChange> events() {

		return super.events()
			.filter( fc -> isRelevant( fc.path() ) );

	}

	private boolean isRelevant(
		Path p
	) {

		if (p == null)
			return false;

		Path n = p.toAbsolutePath().normalize();
		if (triggerPath != null && triggerPath.equals( n ))
			return false; // self-trigger loop 방지

		String s = n.toString().replace( '\\', '/' );

		// 임시 파일류 제외
		if (s.endsWith( ".tmp" ) || s.endsWith( "~" ))
			return false;

		if (s.endsWith( ".class" ))
			return true;

		if (s.endsWith( ".properties" ) || s.endsWith( ".yml" ) || s.endsWith( ".yaml" ))
			return true;

		if (s.endsWith( ".jar" ))
			return true;

		return false;

	}

	/**
	 * Returns a no-op generation result.
	 * <p>This watcher exists only to detect classpath changes and trigger reload signaling.</p>
	 *
	 * @return a {@link Mono} emitting {@code false}
	 */
	@Override
	public Mono<Boolean> runGenerateTask() {

		return Mono.just( false );

	}

	/**
	 * Starts watching the configured classpath root.
	 */
	@Override
	public void startWatching() {

		try {
			super.start();

		} catch (IOException e) {
			throw new RuntimeException( e );

		}

	}

}
