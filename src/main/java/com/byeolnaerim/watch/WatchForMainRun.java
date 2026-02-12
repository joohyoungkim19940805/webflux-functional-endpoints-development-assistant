
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

		public static Builder builder() {

			return new Builder();

		}

		public static final class Builder {

			private Path trigger; // Paths.get( "src/main/resources/.reloadtrigger" );

			private long debounceMillis = 400;

			private WatcherFactory<EntityFileWatcher> entityFactory;

			private WatcherFactory<HandlerGenerator> handlerFactory;

			private WatcherFactory<SwaggerJsonFileWatcher> swaggerFactory;

			private WatcherFactory<RsoketAsyncApiJsonFileWatcher> asyncApiFactory;

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

			public Builder asyncApiFactory(
				WatcherFactory<RsoketAsyncApiJsonFileWatcher> f
			) {

				this.asyncApiFactory = f;
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

	public WatchForMainRun(
							Config config
	) {

		this.config = config;

	}

	public void start() throws Exception {

		// lazy 생성
		if (entity == null && config.entityFactory != null)
			entity = config.entityFactory.create();
		if (handler == null && config.handlerFactory != null)
			handler = config.handlerFactory.create();
		if (swagger == null && config.swaggerFactory != null)
			swagger = config.swaggerFactory.create();
		if (asyncApiFactory == null && config.asyncApiFactory != null)
			asyncApiFactory = config.asyncApiFactory.create();

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


