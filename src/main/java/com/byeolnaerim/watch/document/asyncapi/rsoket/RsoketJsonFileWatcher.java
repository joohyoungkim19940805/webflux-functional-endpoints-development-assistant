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
 * Watches source files and regenerates a simplified {@code rsoket.json} document
 * for parsed RSocket endpoints.
 */
public class RsoketJsonFileWatcher extends AbstractWatcher {

	public static final class Config {

		private final String watchDirectory;

		private final String rsoketOutputFile;

		/**
		 * Immutable configuration for {@link RsoketJsonFileWatcher}.
		 */
		private Config(
						Builder b
		) {

			this.watchDirectory = b.watchDirectory.replace( '\\', '/' ).replace( '.', '/' );

			int lastDotIndex = b.rsoketOutputFile.lastIndexOf( '.' );

			if (lastDotIndex == -1) {
				this.rsoketOutputFile = b.rsoketOutputFile.replace( '\\', '/' ) + ".json";

			} else {
				this.rsoketOutputFile = b.rsoketOutputFile.substring( 0, lastDotIndex ).replace( '\\', '/' ).replace( '.', '/' ) + b.rsoketOutputFile.substring( lastDotIndex );

			}

		}

		/**
		 * Returns the normalized source directory to watch.
		 *
		 * @return the watch directory
		 */
		public String watchDirectory() {

			return watchDirectory;

		}

		/**
		 * Returns the normalized target path of the generated {@code rsoket.json} file.
		 *
		 * @return the output file path
		 */
		public String rsoketOutputFile() {

			return rsoketOutputFile;

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
		 * Builder for {@link RsoketJsonFileWatcher.Config}.
		 */
		public static final class Builder {

			private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;

			private String rsoketOutputFile = "src/main/resources/static/rsoket.json";

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
			 * Sets the target path of the generated {@code rsoket.json} file.
			 *
			 * @param p
			 *            the output file path
			 * 
			 * @return this builder
			 */
			public Builder rsoketOutputFile(
				String p
			) {

				this.rsoketOutputFile = p;
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
	 * Creates a new simplified RSocket JSON watcher.
	 *
	 * @param config
	 *            the watcher configuration
	 */
	public RsoketJsonFileWatcher(
									Config config
	) {

		this.config = config;

	}

	/**
	 * Executes a single RSocket JSON generation pass.
	 *
	 * @return a {@link reactor.core.publisher.Mono} emitting {@code true} if the output file was
	 *         changed
	 */
	public Mono<Boolean> runGenerateTask() {

		return Mono.fromCallable( () -> {
			String json = generateRsoketJson();
			Path out = Paths.get( config.rsoketOutputFile() );
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
	public void startWatching() {

		try {
			super.start();

		} catch (IOException e) {
			throw new RuntimeException( e );

		}

	}

	private String generateRsoketJson() {

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
			return RsoketGenerator.generateRsoketJson( routes );

		} catch (Exception e) {
			e.printStackTrace();

		}

		return "";

	}

}
