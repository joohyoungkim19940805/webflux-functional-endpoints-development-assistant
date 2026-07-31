package com.byeolnaerim.watch;



import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;


/**
 * Base watcher for file-system-driven dev-time generators.
 * <p>This class is responsible only for detecting file changes and exposing them
 * as a reactive event stream. Concrete subclasses define the watch root,
 * generation task, and startup policy.</p>
 * <p>It also provides a utility method that writes files only when content has changed,
 * which helps avoid unnecessary downstream reloads.</p>
 */
public abstract class AbstractWatcher implements AutoCloseable {

	private static final long GENERATED_EVENT_SUPPRESSION_MILLIS = 2_000L;

	private static final ConcurrentMap<Path, Long> RECENT_GENERATED_PATHS = new ConcurrentHashMap<>();

	private final Sinks.Many<FileChange> changeSink = Sinks.many().multicast().onBackpressureBuffer();

	private final AtomicBoolean running = new AtomicBoolean( false );

	private Thread loopThread;

	private WatchService watchService;

	/**
	 * Returns the root directory to be monitored recursively.
	 * 감시 시작 루트 디렉터리
	 * 
	 * @return the watch root directory
	 */
	protected abstract Path root();

	/**
	 * Executes a single generation pass for this watcher.
	 * <p>This method is typically called by the orchestrator when relevant file changes
	 * have been detected.</p>
	 *
	 * @return a {@link Mono} emitting {@code true} if any output was changed
	 */
	public abstract Mono<Boolean> runGenerateTask();

	/**
	 * Executes a single generation pass for this watcher.
	 * <p>This method is typically called by the orchestrator when relevant file changes
	 * have been detected.</p>
	 *
	 * @return a {@link Mono} emitting {@code true} if any output was changed
	 */
	public abstract void startWatching();

	/**
	 * Returns the file-system event kinds to monitor.
	 * <p>The default implementation watches create, modify, and delete events.</p>
	 * 어떤 이벤트를 감시할지 (default: CREATE/MODIFY/DELETE)
	 * 
	 * @return the monitored event kinds
	 */
	protected WatchEvent.Kind<?>[] kinds() {

		return new WatchEvent.Kind<?>[] {
			StandardWatchEventKinds.ENTRY_CREATE,
			StandardWatchEventKinds.ENTRY_MODIFY,
			StandardWatchEventKinds.ENTRY_DELETE
		};

	}

	/**
	 * Writes the given bytes to the target path only when the content has actually changed.
	 * <p>This method performs an atomic replace through a temporary file when possible.</p>
	 *
	 * @param out
	 *            the output file path
	 * @param newBytes
	 *            the new file content
	 * 
	 * @return {@code true} if the file was created or updated, {@code false} if the content was
	 *         unchanged
	 * 
	 * @throws IOException
	 *             if file writing fails
	 */
	public boolean writeIfChanged(
		Path out, byte[] newBytes
	)
		throws IOException {

		byte[] old = Files.exists( out ) ? Files.readAllBytes( out ) : null;
		if (Arrays.equals( old, newBytes ))
			return false;
		Files.createDirectories( out.getParent() );
		Path normalizedOut = out.toAbsolutePath().normalize();
		Path tmp = out.getParent().resolve( out.getFileName() + ".tmp" );
		markGeneratedPath( normalizedOut );
		markGeneratedPath( tmp );
		Files.write( tmp, newBytes );
		Files
			.move(
				tmp,
				out,
				java.nio.file.StandardCopyOption.REPLACE_EXISTING,
				java.nio.file.StandardCopyOption.ATOMIC_MOVE
			);
		return true;

	}

	/**
	 * Returns the externally visible stream of file-change events.
	 * <p>Debouncing is intentionally handled by the orchestrator so create/delete
	 * events from the same burst are not discarded before cache invalidation.</p>
	 *
	 * @return a reactive stream of file changes
	 */
	public Flux<FileChange> events() {

		return changeSink.asFlux();

	}

	/**
	 * Starts recursive file watching from the configured root directory.
	 * <p>This method registers the root and all existing subdirectories, starts the internal
	 * watch loop thread, and emits {@link FileChange} events for relevant file-system updates.</p>
	 *
	 * @throws IOException
	 *             if watcher initialization fails
	 */
	public synchronized void start() throws IOException {

		if (running.get())
			return;
		Path base = Objects.requireNonNull( root(), "root path must not be null" );
		watchService = FileSystems.getDefault().newWatchService();

		// 루트 + 모든 하위 디렉터리 등록
		Files.walkFileTree( base, new SimpleFileVisitor<>() {

			@Override
			public FileVisitResult preVisitDirectory(
				Path dir, BasicFileAttributes attrs
			)
				throws IOException {

				dir.register( watchService, kinds() );
				return FileVisitResult.CONTINUE;

			}

		} );

		running.set( true );
		loopThread = new Thread( () -> {

			try {

				while (running.get()) {
					WatchKey key = watchService.take(); // block
					Path dir = (Path) key.watchable();

					for (WatchEvent<?> event : key.pollEvents()) {
						WatchEvent.Kind<?> kind = event.kind();

						if (kind == StandardWatchEventKinds.OVERFLOW) {
							changeSink.tryEmitNext( new FileChange( base, kind, getClass().getSimpleName() ) );
							continue;

						}

						@SuppressWarnings("unchecked")
						Path name = ((WatchEvent<Path>) event).context();
						Path child = dir.resolve( name );

						// 새 디렉터리는 재귀 등록
						if (kind == StandardWatchEventKinds.ENTRY_CREATE) {

							try {

								if (Files.isDirectory( child )) {
									Files.walkFileTree( child, new SimpleFileVisitor<>() {

										@Override
										public FileVisitResult preVisitDirectory(
											Path d, BasicFileAttributes a
										)
											throws IOException {

											d.register( watchService, kinds() );
											return FileVisitResult.CONTINUE;

										}

									} );

								}

							} catch (IOException ignore) {}

						}

						if (! shouldSuppressEvent( child )) {
							changeSink.tryEmitNext( new FileChange( child, kind, getClass().getSimpleName() ) );

						}

					}

					if (! key.reset()) {
						changeSink.tryEmitNext(
							new FileChange( dir, StandardWatchEventKinds.ENTRY_DELETE, getClass().getSimpleName() )
						);

						if (dir.equals( base )) {
							break;

						}

					}

				}

			} catch (InterruptedException ignore) {
				Thread.currentThread().interrupt();

			} finally {

				try {
					watchService.close();

				} catch (IOException ignore) {}

			}

		}, getClass().getSimpleName() + "-watch-loop" );
		loopThread.setDaemon( true );
		loopThread.start();

	}

	private static void markGeneratedPath(
		Path path
	) {

		if (path == null)
			return;

		long now = System.currentTimeMillis();
		RECENT_GENERATED_PATHS.entrySet().removeIf( entry -> entry.getValue() < now );
		RECENT_GENERATED_PATHS.put( path.toAbsolutePath().normalize(), now + GENERATED_EVENT_SUPPRESSION_MILLIS );

	}

	private static boolean shouldSuppressEvent(
		Path path
	) {

		if (path == null)
			return false;

		Path normalized = path.toAbsolutePath().normalize();
		String fileName = normalized.getFileName() == null ? "" : normalized.getFileName().toString();

		if (fileName.endsWith( ".tmp" ) || fileName.endsWith( "~" )) {
			return true;

		}

		long now = System.currentTimeMillis();
		Long suppressUntil = RECENT_GENERATED_PATHS.get( normalized );

		if (suppressUntil == null)
			return false;

		if (suppressUntil >= now) {
			return true;

		}

		RECENT_GENERATED_PATHS.remove( normalized, suppressUntil );
		return false;

	}

	/**
	 * Stops the watcher and releases the underlying {@link java.nio.file.WatchService}.
	 */
	@Override
	public synchronized void close() {

		running.set( false );
		if (loopThread != null)
			loopThread.interrupt();

		try {
			if (watchService != null)
				watchService.close();

		} catch (IOException ignore) {}

	}

}
