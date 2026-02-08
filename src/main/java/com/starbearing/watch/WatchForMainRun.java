
package com.starbearing.watch;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.starbearing.watch.db.EntityFileWatcher;
import com.starbearing.watch.document.swagger.SwaggerJsonFileWatcher;
import com.starbearing.watch.route.HandlerGenerator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


public final class WatchForMainRun {

	/* =========================
	 * 생성 팩토리 정의
	 * ========================= */
	@FunctionalInterface
	public interface WatcherFactory<T> {

		T create() throws Exception;

	}

	public static final class Config {

		private final Path trigger;

		private final long debounceMillis;

		private final WatcherFactory<EntityFileWatcher> entityFactory;

		private final WatcherFactory<HandlerGenerator> handlerFactory;

		private final WatcherFactory<SwaggerJsonFileWatcher> swaggerFactory;

		private final List<Path> classpathWatchRoots;

		private Config(
						Builder b
		) {

			this.trigger = b.trigger;
			this.debounceMillis = b.debounceMillis;
			this.entityFactory = b.entityFactory;
			this.handlerFactory = b.handlerFactory;
			this.swaggerFactory = b.swaggerFactory;
			this.classpathWatchRoots = List.copyOf( b.classpathWatchRoots );

			// 필수 보장
			if (entityFactory == null || handlerFactory == null || swaggerFactory == null) { throw new IllegalStateException( "entityFactory/handlerFactory/swaggerFactory must be provided." ); }

		}

		public static Builder builder() {

			return new Builder();

		}

		public static final class Builder {

			private Path trigger = Paths.get( ProjectDefaults.TRIGGER_FILE ); // Paths.get( "src/main/resources/.reloadtrigger" );

			private long debounceMillis = 400;

			private WatcherFactory<EntityFileWatcher> entityFactory;

			private WatcherFactory<HandlerGenerator> handlerFactory;

			private WatcherFactory<SwaggerJsonFileWatcher> swaggerFactory;

			private final List<Path> classpathWatchRoots = new ArrayList<>();

			public Builder trigger(
				Path p
			) {

				this.trigger = p;
				return this;

			}

			public Builder debounceMillis(
				long ms
			) {

				this.debounceMillis = ms;
				return this;

			}

			/* ====== 권장: Config로 주입 ====== */
			public Builder entityConfig(
				EntityFileWatcher.Config cfg
			) {

				this.entityFactory = () -> new EntityFileWatcher( cfg );
				return this;

			}

			public Builder handlerConfig(
				HandlerGenerator.Config cfg
			) {

				this.handlerFactory = () -> new HandlerGenerator( cfg );
				return this;

			}

			public Builder swaggerConfig(
				SwaggerJsonFileWatcher.Config cfg
			) {

				this.swaggerFactory = () -> new SwaggerJsonFileWatcher( cfg );
				return this;

			}

			/* ====== 필요시: 커스텀 팩토리 직접 주입 ====== */
			public Builder entityFactory(
				WatcherFactory<EntityFileWatcher> f
			) {

				this.entityFactory = f;
				return this;

			}

			public Builder handlerFactory(
				WatcherFactory<HandlerGenerator> f
			) {

				this.handlerFactory = f;
				return this;

			}

			public Builder swaggerFactory(
				WatcherFactory<SwaggerJsonFileWatcher> f
			) {

				this.swaggerFactory = f;
				return this;

			}

			public Builder classpathWatchPaths(
				String pathsCsvOrPathSep
			) {

				if (pathsCsvOrPathSep == null || pathsCsvOrPathSep.isBlank())
					return this;

				String sep = File.pathSeparator; // Windows=";" / Unix=":"
				String regex = "\\s*,\\s*|\\s*" + Pattern.quote( sep ) + "\\s*";

				for (String part : pathsCsvOrPathSep.split( regex )) {
					if (part == null || part.isBlank())
						continue;
					classpathWatchRoots.add( Paths.get( part.trim() ).toAbsolutePath().normalize() );

				}

				return this;

			}


			public Config build() {

				if (trigger != null) {
					trigger = trigger.toAbsolutePath().normalize();


				}

				if (this.classpathWatchRoots.isEmpty()) {
					String cp = System.getProperty( "java.class.path", "" );
					String sep = java.io.File.pathSeparator;

					for (String part : cp.split( java.util.regex.Pattern.quote( sep ) )) {
						if (part == null || part.isBlank())
							continue;
						Path p = Paths.get( part.trim() ).toAbsolutePath().normalize();
						if (Files.isDirectory( p ))
							this.classpathWatchRoots.add( p );

					}

				}

				classpathWatchRoots.add( trigger.getParent() );


				return new Config( this );

			}

		}

	}

	/* =========================
	 * 필드 (실 인스턴스는 start()에서 생성)
	 * ========================= */
	private final Config config;

	private EntityFileWatcher entity;

	private HandlerGenerator handler;

	private SwaggerJsonFileWatcher swagger;

	private volatile boolean running = false;

	private Disposable subscription;

	private Disposable classpathSubscription;

	private final List<ClasspathWatcher> classpathWatchers = new ArrayList<>();

	public WatchForMainRun(
							Config config
	) {

		this.config = config;

	}

	public void start() throws Exception {

		// lazy 생성
		if (entity == null)
			entity = config.entityFactory.create();
		if (handler == null)
			handler = config.handlerFactory.create();
		if (swagger == null)
			swagger = config.swaggerFactory.create();

		running = true;

		// 초기 실행
		runPipeline( entity, handler, swagger ).blockOptional();

		this.subscription = Flux
			.merge( entity.events(), handler.events(), swagger.events() )
			.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.debounceMillis ) ) )
			.onBackpressureLatest()
			.concatMap( ignored -> runPipeline( entity, handler, swagger ) )
			.subscribe();

		if (! config.classpathWatchRoots.isEmpty()) {

			for (Path root : config.classpathWatchRoots) {
				classpathWatchers.add( new ClasspathWatcher( root, config.trigger ) );

			}

			this.classpathSubscription = Flux
				.merge( classpathWatchers.stream().map( ClasspathWatcher::events ).toList() )
				.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.debounceMillis ) ) )
				.onBackpressureLatest()
				.doOnNext( fc -> touchTrigger() ) // ✅ Mono 안 만들고 실제 touch 실행
				.subscribe();

			for (ClasspathWatcher w : classpathWatchers) {
				w.start(); // subscribe 이후 start

			}

		}

		// watch 시작
		entity.startWatching();
		handler.startWatching();
		swagger.startWatching();

		// Thread.currentThread().join();

	}

	public void stop() {

		running = false;

		if (subscription != null) {
			subscription.dispose();
			subscription = null;

		}

		if (classpathSubscription != null) {
			classpathSubscription.dispose();
			classpathSubscription = null;

		}

		for (ClasspathWatcher w : classpathWatchers) {

			try {
				w.close();

			} catch (Throwable ignore) {}

		}

		classpathWatchers.clear();

		try {
			entity.close();

		} catch (Throwable ignore) {}

		try {
			handler.close();

		} catch (Throwable ignore) {}

		try {
			swagger.close();

		} catch (Throwable ignore) {}

	}

	private Mono<Boolean> runPipeline(
		EntityFileWatcher e, HandlerGenerator h, SwaggerJsonFileWatcher s
	) {

		return Mono
			.zip( e.runGenerateTask(), h.runGenerateTask(), s.runGenerateTask() )
			.map( t3 -> t3.getT1() || t3.getT2() || t3.getT3() );

	}

	private void touchTrigger() {

		try {
			Path trigger = config.trigger;

			if (trigger.getParent() != null) {
				Files.createDirectories( trigger.getParent() );

			}

			if (! Files.exists( trigger )) {
				Files.createFile( trigger );

			}

			Files
				.setLastModifiedTime(
					trigger,
					java.nio.file.attribute.FileTime.fromMillis( System.currentTimeMillis() )
				);
			System.out.println( "  - touched " + trigger );

		} catch (IOException ex) {
			throw new RuntimeException( ex );

		}

	}

}


