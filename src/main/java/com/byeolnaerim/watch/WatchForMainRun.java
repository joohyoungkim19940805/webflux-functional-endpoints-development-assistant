
package com.byeolnaerim.watch;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.byeolnaerim.watch.db.EntityFileWatcher;
import com.byeolnaerim.watch.document.AbstractSpoonDocumentWatcher;
import com.byeolnaerim.watch.document.SpoonAnalysisCache;
import com.byeolnaerim.watch.document.asyncapi.rsocket.RsoketAsyncApiJsonFileWatcher;
import com.byeolnaerim.watch.document.swagger.SwaggerJsonFileWatcher;
import com.byeolnaerim.watch.route.HandlerGenerator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


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

		private final long restartDebounceMillis;

		private final int restartLoopLimit;

		private final long restartLoopWindowMillis;

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
			this.restartDebounceMillis = b.restartDebounceMillis;
			this.restartLoopLimit = b.restartLoopLimit;
			this.restartLoopWindowMillis = b.restartLoopWindowMillis;
			this.entityFactory = b.entityFactory;
			this.handlerFactory = b.handlerFactory;
			this.swaggerFactory = b.swaggerFactory;
			this.asyncApiFactory = b.asyncApiFactory;
			this.classpathWatchRoots = List.copyOf( b.classpathWatchRoots );

			if (entityFactory == null && handlerFactory == null && swaggerFactory == null && asyncApiFactory == null) {
				throw new IllegalStateException(
					"At least one watcher factory must be provided."
				);

			}

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

			private long restartDebounceMillis = 1_500;

			private int restartLoopLimit = 4;

			private long restartLoopWindowMillis = 30_000;

			private WatcherFactory<EntityFileWatcher> entityFactory;

			private WatcherFactory<HandlerGenerator> handlerFactory;

			private WatcherFactory<SwaggerJsonFileWatcher> swaggerFactory;

			private WatcherFactory<RsoketAsyncApiJsonFileWatcher> asyncApiFactory;

			private final List<Path> classpathWatchRoots = new ArrayList<>();

			/**
			 * Sets the reload-trigger file path and explicitly enables library-managed restart signaling.
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
			 * Sets the quiet period used before touching an explicitly configured restart trigger.
			 *
			 * @param ms
			 *            quiet period in milliseconds
			 *
			 * @return this builder
			 */
			public Builder restartDebounceMillis(
				long ms
			) {

				this.restartDebounceMillis = Math.max( 100L, ms );
				return this;

			}

			/**
			 * Configures the persistent restart-loop circuit breaker.
			 *
			 * @param maxRestarts
			 *            maximum trigger touches allowed inside the window
			 * @param windowMillis
			 *            rolling time window in milliseconds
			 *
			 * @return this builder
			 */
			public Builder restartLoopGuard(
				int maxRestarts, long windowMillis
			) {

				this.restartLoopLimit = Math.max( 1, maxRestarts );
				this.restartLoopWindowMillis = Math.max( 1_000L, windowMillis );
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
			 * <p>Classpath roots are inferred when omitted. Automatic restart remains disabled
			 * unless an explicit trigger path is configured.</p>
			 *
			 * @return the built configuration
			 */
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

				if (trigger != null && trigger.getParent() != null) {
					classpathWatchRoots.add( trigger.getParent() );

				}

				List<Path> distinctRoots = new ArrayList<>( new LinkedHashSet<>( classpathWatchRoots ) );
				classpathWatchRoots.clear();
				classpathWatchRoots.addAll( distinctRoots );

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

	private final SpoonAnalysisCache spoonAnalysisCache = new SpoonAnalysisCache();

	private final AtomicBoolean sourceRestartPending = new AtomicBoolean( false );

	private RestartLoopGuard restartLoopGuard;

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

		if (entity == null && config.entityFactory != null) {
			entity = config.entityFactory.create();

		}

		if (entity != null) {
			watchers.add( entity );

		}

		if (handler == null && config.handlerFactory != null) {
			handler = config.handlerFactory.create();

		}

		if (handler != null) {
			watchers.add( handler );

		}

		if (swagger == null && config.swaggerFactory != null) {
			swagger = config.swaggerFactory.create();

		}

		if (swagger != null) {
			watchers.add( swagger );

		}

		if (asyncApiFactory == null && config.asyncApiFactory != null) {
			asyncApiFactory = config.asyncApiFactory.create();

		}

		if (asyncApiFactory != null) {
			watchers.add( asyncApiFactory );

		}

		watchers
			.stream()
			.filter( AbstractSpoonDocumentWatcher.class::isInstance )
			.map( AbstractSpoonDocumentWatcher.class::cast )
			.forEach( watcher -> watcher.useSpoonAnalysisCache( spoonAnalysisCache ) );

		running = true;

		if (config.trigger != null) {
			restartLoopGuard = new RestartLoopGuard(
				ProjectDefaults.detectProjectRoot().resolve( "build/webflux-fe-dev/restart-history" ),
				config.restartLoopLimit,
				config.restartLoopWindowMillis
			);

		}

		Queue<FileChange> pendingSourceChanges = new ConcurrentLinkedQueue<>();

		Flux<List<FileChange>> sourceChanges = Flux
			.merge( watchers.stream().map( AbstractWatcher::events ).toList() )
			.doOnNext( pendingSourceChanges::add )
			.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.debounceMillis ) ) )
			.map( ignored -> drainChanges( pendingSourceChanges ) )
			.filter( changes -> ! changes.isEmpty() )
			.doOnNext( changes -> {
				if (config.trigger != null && changes.stream().anyMatch( this::requiresSourceRestart )) {
					sourceRestartPending.set( true );

				}

			} );

		this.subscription = Flux
			.merge( Mono.just( List.<FileChange>of() ), sourceChanges )
			.concatMap(
				changes -> runPipeline( watchers, changes )
					.subscribeOn( Schedulers.boundedElastic() )
					.onErrorResume( error -> {
						error.printStackTrace();
						return Mono.just( false );

					} )
			)
			.subscribe();

		if (! config.classpathWatchRoots.isEmpty()) {

			for (Path root : config.classpathWatchRoots) {
				classpathWatchers.add( new ClasspathWatcher( root, config.trigger ) );

			}

			Queue<FileChange> pendingClasspathChanges = new ConcurrentLinkedQueue<>();

			this.classpathSubscription = Flux
				.merge( classpathWatchers.stream().map( ClasspathWatcher::events ).toList() )
				.doOnNext( pendingClasspathChanges::add )
				.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.restartDebounceMillis ) ) )
				.map( ignored -> drainChanges( pendingClasspathChanges ) )
				.filter( changes -> ! changes.isEmpty() )
				.onBackpressureLatest()
				.doOnNext( changes -> {
					changes.forEach( spoonAnalysisCache::invalidateClasspath );

					if (running && config.trigger != null && shouldTouchTrigger( changes )) {
						touchTrigger();

					}

				} )
				.subscribe();

			for (ClasspathWatcher w : classpathWatchers) {

				try {
					w.startWatching();

				} catch (Exception ignore) {

				}

			}

		}

		watchers.forEach( AbstractWatcher::startWatching );

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
		Collection<AbstractWatcher> watchers,
		Collection<FileChange> changes
	) {

		List<AbstractWatcher> sourceGenerators = watchers
			.stream()
			.filter( Objects::nonNull )
			.filter( watcher -> ! (watcher instanceof AbstractSpoonDocumentWatcher) )
			.toList();

		List<AbstractWatcher> documentGenerators = watchers
			.stream()
			.filter( Objects::nonNull )
			.filter( AbstractSpoonDocumentWatcher.class::isInstance )
			.toList();

		Mono<Boolean> sourceResult = Flux
			.fromIterable( sourceGenerators )
			.concatMap( AbstractWatcher::runGenerateTask )
			.reduce( false, (acc, changed) -> acc || Boolean.TRUE.equals( changed ) );

		return sourceResult
			.flatMap( sourceChanged -> Mono.defer( () -> {
				if (Boolean.TRUE.equals( sourceChanged )) {
					spoonAnalysisCache.invalidateProjectModels();

					if (config.trigger != null) {
						sourceRestartPending.set( true );

					}

				} else {
					spoonAnalysisCache.invalidateProjectModels( changes );

				}

				return Flux
					.fromIterable( documentGenerators )
					.flatMap( AbstractWatcher::runGenerateTask )
					.reduce( false, (acc, changed) -> acc || Boolean.TRUE.equals( changed ) )
					.map( documentChanged -> sourceChanged || documentChanged );

			} ) );

	}

	private List<FileChange> drainChanges(
		Queue<FileChange> pendingChanges
	) {

		List<FileChange> changes = new ArrayList<>();
		FileChange change;

		while ((change = pendingChanges.poll()) != null) {
			changes.add( change );

		}

		return List.copyOf( changes );

	}

	private boolean requiresSourceRestart(
		FileChange change
	) {

		return change != null && (change.isJavaSource() || change.isStructuralSourceChange());

	}

	private boolean shouldTouchTrigger(
		Collection<FileChange> changes
	) {

		Path currentClasspathRoot = config.trigger.getParent() == null
			? null
			: config.trigger.getParent().toAbsolutePath().normalize();

		boolean currentProjectClassChanged = false;
		boolean externalRuntimeChanged = false;

		for (FileChange change : changes) {
			if (change == null || change.path() == null)
				continue;

			Path changedPath = change.path().toAbsolutePath().normalize();
			String value = changedPath.toString().replace( '\\', '/' ).toLowerCase();

			if (value.endsWith( ".class" )) {
				if (currentClasspathRoot != null && changedPath.startsWith( currentClasspathRoot )) {
					currentProjectClassChanged = true;

				} else {
					externalRuntimeChanged = true;

				}

			} else if (
				value.endsWith( ".jar" )
					|| value.endsWith( ".properties" )
					|| value.endsWith( ".yml" )
					|| value.endsWith( ".yaml" )
					|| change.isOverflow()
			) {
				externalRuntimeChanged = true;

			}

		}

		boolean sourceRequested = currentProjectClassChanged && sourceRestartPending.get();
		boolean shouldTouch = sourceRequested || externalRuntimeChanged;

		if (shouldTouch) {
			sourceRestartPending.set( false );

		}

		return shouldTouch;

	}

	private void touchTrigger() {

		if (restartLoopGuard != null && ! restartLoopGuard.tryAcquire()) {
			System.err.println(
				"[webflux-fe-dev] Automatic restart was blocked because repeated trigger touches were detected."
			);
			return;

		}

		try {
			Path trigger = config.trigger;

			if (trigger.getParent() != null) {
				Files.createDirectories( trigger.getParent() );

			}

			Files.writeString(
				trigger,
				Long.toString( System.currentTimeMillis() ),
				java.nio.charset.StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
				java.nio.file.StandardOpenOption.WRITE
			);
			System.out.println( "  - touched " + trigger );

		} catch (IOException ex) {
			throw new RuntimeException( ex );

		}

	}


}


