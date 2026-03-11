
package com.byeolnaerim.watch;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.byeolnaerim.watch.db.EntityFileWatcher;
import com.byeolnaerim.watch.document.asyncapi.rsoket.RsoketAsyncApiJsonFileWatcher;
import com.byeolnaerim.watch.document.swagger.SwaggerJsonFileWatcher;
import com.byeolnaerim.watch.route.HandlerGenerator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/**
 * Main development-time orchestrator that wires multiple watchers together
 * and runs them as a coordinated generation pipeline.
 * <p>This class performs an initial generation pass, subscribes to watcher event streams,
 * debounces bursts of file changes, and re-runs generation when relevant source files change.</p>
 * <p>It can also monitor classpath-related resources and touch a reload-trigger file
 * to help devtools-like reload workflows.</p>
 */
public final class WatchForMainRun {

	/**
	 * Factory interface for lazily creating watcher instances.
	 *
	 * @param <T>
	 *            the watcher type
	 */
	@FunctionalInterface
	public interface WatcherFactory<T> {

		/**
		 * Creates a watcher instance.
		 *
		 * @return the created watcher
		 * 
		 * @throws Exception
		 *             if creation fails
		 */
		T create() throws Exception;

	}

	/**
	 * Immutable configuration for {@link WatchForMainRun}.
	 */
	public static final class Config {

		private final Path trigger;

		private final long debounceMillis;

		private final WatcherFactory<EntityFileWatcher> entityFactory;

		private final WatcherFactory<HandlerGenerator> handlerFactory;

		private final WatcherFactory<SwaggerJsonFileWatcher> swaggerFactory;

		private final WatcherFactory<RsoketAsyncApiJsonFileWatcher> asyncApiFactory;

		private final List<Path> classpathWatchRoots;

		private Config(
						Builder b
		) {

			this.trigger = b.trigger;
			this.debounceMillis = b.debounceMillis;
			this.entityFactory = b.entityFactory;
			this.handlerFactory = b.handlerFactory;
			this.swaggerFactory = b.swaggerFactory;
			this.asyncApiFactory = b.asyncApiFactory;
			this.classpathWatchRoots = List.copyOf( b.classpathWatchRoots );

			// 필수 보장
			if (entityFactory == null || handlerFactory == null || swaggerFactory == null) { throw new IllegalStateException( "entityFactory/handlerFactory/swaggerFactory must be provided." ); }

		}

		/**
		 * Creates a new configuration builder.
		 *
		 * @return a new builder
		 */
		public static Builder builder() {

			return new Builder();

		}

		/**
		 * Builder for {@link WatchForMainRun.Config}.
		 * <p>This builder configures debounce behavior, watcher factories,
		 * reload trigger handling, and optional classpath watch roots.</p>
		 */
		public static final class Builder {

			private Path trigger; // Paths.get( "src/main/resources/.reloadtrigger" );

			private long debounceMillis = 400;

			private WatcherFactory<EntityFileWatcher> entityFactory;

			private WatcherFactory<HandlerGenerator> handlerFactory;

			private WatcherFactory<SwaggerJsonFileWatcher> swaggerFactory;

			private WatcherFactory<RsoketAsyncApiJsonFileWatcher> asyncApiFactory;

			private final List<Path> classpathWatchRoots = new ArrayList<>();

			/**
			 * Sets the reload-trigger file path.
			 *
			 * @param p
			 *            the trigger file path
			 * 
			 * @return this builder
			 */
			public Builder trigger(
				Path p
			) {

				this.trigger = p;
				return this;

			}

			/**
			 * Sets the debounce window used when coalescing bursts of file-change events.
			 *
			 * @param ms
			 *            the debounce duration in milliseconds
			 * 
			 * @return this builder
			 */
			public Builder debounceMillis(
				long ms
			) {

				this.debounceMillis = ms;
				return this;

			}

			/**
			 * Configures the entity watcher using a concrete watcher configuration object.
			 *
			 * @param cfg
			 *            the entity watcher configuration
			 * 
			 * @return this builder
			 */
			public Builder entityConfig(
				EntityFileWatcher.Config cfg
			) {

				this.entityFactory = () -> new EntityFileWatcher( cfg );
				return this;

			}

			/**
			 * Configures the handler generator using a concrete watcher configuration object.
			 *
			 * @param cfg
			 *            the handler generator configuration
			 * 
			 * @return this builder
			 */
			public Builder handlerConfig(
				HandlerGenerator.Config cfg
			) {

				this.handlerFactory = () -> new HandlerGenerator( cfg );
				return this;

			}

			/**
			 * Configures the Swagger generator watcher using a concrete watcher configuration object.
			 *
			 * @param cfg
			 *            the Swagger watcher configuration
			 * 
			 * @return this builder
			 */
			public Builder swaggerConfig(
				SwaggerJsonFileWatcher.Config cfg
			) {

				this.swaggerFactory = () -> new SwaggerJsonFileWatcher( cfg );
				return this;

			}

			/* ====== 커스텀 팩토리 직접 주입 ====== */
			/**
			 * Sets a custom lazy factory for the entity watcher.
			 *
			 * @param f
			 *            the watcher factory
			 * 
			 * @return this builder
			 */
			public Builder entityFactory(
				WatcherFactory<EntityFileWatcher> f
			) {

				this.entityFactory = f;
				return this;

			}

			/**
			 * Sets a custom lazy factory for the handler generator.
			 *
			 * @param f
			 *            the watcher factory
			 * 
			 * @return this builder
			 */
			public Builder handlerFactory(
				WatcherFactory<HandlerGenerator> f
			) {

				this.handlerFactory = f;
				return this;

			}

			/**
			 * Sets a custom lazy factory for the Swagger watcher.
			 *
			 * @param f
			 *            the watcher factory
			 * 
			 * @return this builder
			 */
			public Builder swaggerFactory(
				WatcherFactory<SwaggerJsonFileWatcher> f
			) {

				this.swaggerFactory = f;
				return this;

			}

			/**
			 * Sets a custom lazy factory for the AsyncAPI watcher.
			 *
			 * @param f
			 *            the watcher factory
			 * 
			 * @return this builder
			 */
			public Builder asyncApiFactory(
				WatcherFactory<RsoketAsyncApiJsonFileWatcher> f
			) {

				this.asyncApiFactory = f;
				return this;

			}

			/**
			 * Adds classpath watch roots from a CSV or OS path-separator-delimited string.
			 *
			 * @param pathsCsvOrPathSep
			 *            the classpath root list
			 * 
			 * @return this builder
			 */
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

			/**
			 * Builds an immutable {@link Config} instance.
			 * <p>If no explicit trigger or classpath watch roots are provided,
			 * sensible defaults are inferred from the current project and classpath.</p>
			 *
			 * @return the built configuration
			 */
			public Config build() {

				if (trigger != null) {
					trigger = trigger.toAbsolutePath().normalize();

				} else {
					trigger = ProjectDefaults.detectClasspathRoot( ProjectDefaults.detectProjectRoot() ).normalize().resolve( ".reloadtrigger" ).normalize();

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

	private RsoketAsyncApiJsonFileWatcher asyncApiFactory;

	private volatile boolean running = false;

	private Disposable subscription;

	private Disposable classpathSubscription;

	private final List<ClasspathWatcher> classpathWatchers = new ArrayList<>();

	private final List<AbstractWatcher> watchers = new ArrayList<>();

	/**
	 * Creates a new orchestrator with the given configuration.
	 *
	 * @param config
	 *            the orchestrator configuration
	 */
	public WatchForMainRun(
							Config config
	) {

		this.config = config;

	}

	/**
	 * Starts the orchestrator and all configured watchers.
	 * <p>This method performs an initial generation pass, subscribes to watcher events,
	 * optionally starts classpath watchers, and then starts file watching for all generators.</p>
	 *
	 * @throws Exception
	 *             if startup fails
	 */
	public void start() throws Exception {

		watchers.clear();

		// lazy 생성
		if (entity == null && config.entityFactory != null) {
			entity = config.entityFactory.create();
			watchers.add( entity );

		}

		if (handler == null && config.handlerFactory != null) {
			handler = config.handlerFactory.create();
			watchers.add( handler );

		}

		if (swagger == null && config.swaggerFactory != null) {
			swagger = config.swaggerFactory.create();
			watchers.add( swagger );

		}

		if (asyncApiFactory == null && config.asyncApiFactory != null) {
			asyncApiFactory = config.asyncApiFactory.create();
			watchers.add( asyncApiFactory );

		}

		running = true;

		// 초기 실행
		runPipeline( watchers ).blockOptional();

		this.subscription = Flux
			.merge( watchers.stream().map( e -> e.events() ).toList() )
			.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.debounceMillis ) ) )
			.onBackpressureLatest()
			.concatMap( ignored -> runPipeline( watchers ) )
			.subscribe();

		if (! config.classpathWatchRoots.isEmpty()) {

			for (Path root : config.classpathWatchRoots) {
				classpathWatchers.add( new ClasspathWatcher( root, config.trigger ) );

			}

			this.classpathSubscription = Flux
				.merge( classpathWatchers.stream().map( ClasspathWatcher::events ).toList() )
				.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.debounceMillis ) ) )
				.onBackpressureLatest()
				.doOnNext( fc -> {
					if (running)
						touchTrigger();

				} ) // ✅ Mono 안 만들고 실제 touch 실행
				.subscribe();

			for (ClasspathWatcher w : classpathWatchers) {

				try {
					w.startWatching(); // subscribe 이후 start

				} catch (Exception ignore) {

				}

			}

		}

		// watch 시작
		watchers.forEach( e -> e.startWatching() );

		// Thread.currentThread().join();

	}

	/**
	 * Stops all subscriptions and closes all managed watchers.
	 */
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

		Stream
			.of( classpathWatchers, watchers )
			.flatMap( Collection::stream )
			.filter( Objects::nonNull )
			.forEach( w -> {

				try {
					w.close();

				} catch (Throwable ignore) {}

			} );

		classpathWatchers.clear();
		watchers.clear();


	}

	private Mono<Boolean> runPipeline(
		Collection<AbstractWatcher> watchers
	) {

		return Flux
			.fromIterable( watchers )
			.filter( Objects::nonNull )
			.flatMap( AbstractWatcher::runGenerateTask )
			.reduce( false, (acc, v) -> acc || Boolean.TRUE.equals( v ) );

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


