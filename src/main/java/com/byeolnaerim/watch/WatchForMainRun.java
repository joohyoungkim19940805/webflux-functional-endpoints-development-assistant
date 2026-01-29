
package com.byeolnaerim.watch;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import com.byeolnaerim.watch.db.EntityFileWatcher;
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

		private Config(
						Builder b
		) {

			this.trigger = b.trigger;
			this.debounceMillis = b.debounceMillis;
			this.entityFactory = b.entityFactory;
			this.handlerFactory = b.handlerFactory;
			this.swaggerFactory = b.swaggerFactory;

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

			public Config build() {

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

		// watch 시작
		entity.startWatching();
		handler.startWatching();
		swagger.startWatching();

		this.subscription = Flux
			.merge( entity.events(), handler.events(), swagger.events() )
			.sampleTimeout( fc -> Mono.delay( Duration.ofMillis( config.debounceMillis ) ) )
			.onBackpressureLatest()
			.concatMap( ignored -> runPipeline( entity, handler, swagger ) ) // Mono<Boolean> 이어도 OK
			.subscribe();

		// Thread.currentThread().join();

	}

	public void stop() {

		running = false;

		if (subscription != null) {
			subscription.dispose();
			subscription = null;

		}

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
			.map( t3 -> t3.getT1() || t3.getT2() || t3.getT3() )
			.doOnNext( changed -> {

				touchTrigger();

			} );

	}

	private void touchTrigger() {

		try {
			Path trigger = config.trigger;
			if (! Files.exists( trigger ))
				Files.createFile( trigger );
			Files.setLastModifiedTime( trigger, java.nio.file.attribute.FileTime.fromMillis( System.currentTimeMillis() ) );
			System.out.println( "  - touched " + trigger );

		} catch (IOException ex) {
			throw new RuntimeException( ex );

		}

	}

}


