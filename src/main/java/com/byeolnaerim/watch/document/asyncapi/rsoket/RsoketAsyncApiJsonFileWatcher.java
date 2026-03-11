package com.byeolnaerim.watch.document.asyncapi.rsoket;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import com.byeolnaerim.watch.AbstractWatcher;
import com.byeolnaerim.watch.ProjectDefaults;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * Watches source files and regenerates an AsyncAPI JSON document for RSocket endpoints.
 * <p>This watcher parses {@code @Controller}/{@code @MessageMapping}-based RSocket routes,
 * sorts them deterministically, generates AsyncAPI JSON, and writes the output file only
 * when its content has changed.</p>
 */
public class RsoketAsyncApiJsonFileWatcher extends AbstractWatcher {

	/**
	 * Immutable configuration for {@link RsoketAsyncApiJsonFileWatcher}.
	 */
	public static final class Config {

		private final String watchDirectory;

		private final String asyncApiOutputFile;

		private Config(
						Builder b
		) {

			this.watchDirectory = b.watchDirectory.replace( '\\', '/' ).replace( '.', '/' );

			int lastDotIndex = b.asyncApiOutputFile.lastIndexOf( '.' );

			if (lastDotIndex == -1) {
				this.asyncApiOutputFile = b.asyncApiOutputFile.replace( '\\', '/' ) + ".json";

			} else {
				this.asyncApiOutputFile = b.asyncApiOutputFile.substring( 0, lastDotIndex ).replace( '\\', '/' ).replace( '.', '/' ) + b.asyncApiOutputFile.substring( lastDotIndex );

			}

		}

		public String watchDirectory() {

			return watchDirectory;

		}

		public String asyncApiOutputFile() {

			return asyncApiOutputFile;

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
		 * Builder for {@link RsoketAsyncApiJsonFileWatcher.Config}.
		 */
		public static final class Builder {

			private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;

			private String asyncApiOutputFile = "src/main/resources/static/asyncapi-rsocket.json";

			/**
			 * Sets the source directory to watch and parse.
			 *
			 * @param p
			 *            the watch directory
			 * 
			 * @return this builder
			 */
			public Builder watchDirectory(
				String p
			) {

				this.watchDirectory = p;
				return this;

			}

			/**
			 * Sets the target path of the generated AsyncAPI JSON file.
			 *
			 * @param p
			 *            the output file path
			 * 
			 * @return this builder
			 */
			public Builder asyncApiOutputFile(
				String p
			) {

				this.asyncApiOutputFile = p;
				return this;

			}

			/**
			 * Builds an immutable watcher configuration.
			 *
			 * @return the built configuration
			 */
			public Config build() {

				return new Config( this );

			}

		}

	}

	private final Config config;

	/**
	 * Creates a new AsyncAPI JSON watcher.
	 *
	 * @param config
	 *            the watcher configuration
	 */
	public RsoketAsyncApiJsonFileWatcher(
											Config config
	) {

		this.config = config;

	}

	/**
	 * Executes a single AsyncAPI generation pass.
	 *
	 * @return a {@link reactor.core.publisher.Mono} emitting {@code true} if the output file was
	 *         changed
	 */
	@Override
	public Mono<Boolean> runGenerateTask() {

		return Mono.fromCallable( () -> {
			String json = generateAsyncApiJson();
			Path out = Paths.get( config.asyncApiOutputFile() );
			return writeIfChanged( out, json.getBytes( StandardCharsets.UTF_8 ) );

		} ).subscribeOn( Schedulers.boundedElastic() );

	}

	@Override
	protected Path root() {

		return Paths.get( config.watchDirectory() );

	}

	/**
	 * Starts watching the configured source directory.
	 */
	@Override
	public void startWatching() {

		try {
			super.start();

		} catch (IOException e) {
			throw new RuntimeException( e );

		}

	}

	private String generateAsyncApiJson() {

		try {
			RsoketParser parser = new RsoketParser();
			List<RsoketRouteInfo> routes = parser.extractRsoketRoutes( config.watchDirectory() );
			routes
				.sort(
					Comparator
						.comparing( RsoketRouteInfo::getDestination )
						.thenComparing( RsoketRouteInfo::getController )
						.thenComparing( RsoketRouteInfo::getMethod )
				);

			RsoketAsyncApiGenerator.Options opt = new RsoketAsyncApiGenerator.Options();
			return RsoketAsyncApiGenerator.generateAsyncApiJson( routes, opt );

		} catch (Exception e) {
			e.printStackTrace();

		}

		return "";

	}

}
