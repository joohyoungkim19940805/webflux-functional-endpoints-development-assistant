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
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;


/**
 * 변경 감지만 담당. 실제 작업은 오케스트레이터가 수행.
 */
public abstract class AbstractWatcher implements AutoCloseable {

	private final Sinks.Many<FileChange> changeSink = Sinks.many().multicast().onBackpressureBuffer();

	private final AtomicBoolean running = new AtomicBoolean( false );

	private Thread loopThread;

	private WatchService watchService;

	/** 감시 시작 루트 디렉터리 */
	protected abstract Path root();

	/** 어떤 이벤트를 감시할지 (기본: CREATE/MODIFY) */
	protected WatchEvent.Kind<?>[] kinds() {

		return new WatchEvent.Kind<?>[] {
			StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY
		};

	}

	public boolean writeIfChanged(
		Path out, byte[] newBytes
	)
		throws IOException {

		byte[] old = Files.exists( out ) ? Files.readAllBytes( out ) : null;
		if (Arrays.equals( old, newBytes ))
			return false;
		Files.createDirectories( out.getParent() );
		Path tmp = out.getParent().resolve( out.getFileName() + ".tmp" );
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

	/** 외부로 노출되는 변경 스트림 */
	public Flux<FileChange> events() {

		// 짧은 디바운스: 같은 디렉터리에서 잦은 이벤트를 약간 합침
		return changeSink
			.asFlux()
			.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( 50 ) ) );

	}

	/** 감시 시작 */
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
						if (kind == StandardWatchEventKinds.OVERFLOW)
							continue;
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

						changeSink.tryEmitNext( new FileChange( child, kind, getClass().getSimpleName() ) );

					}

					key.reset();

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
