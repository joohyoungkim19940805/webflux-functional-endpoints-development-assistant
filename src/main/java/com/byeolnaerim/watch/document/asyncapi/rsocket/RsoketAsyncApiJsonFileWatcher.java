package com.byeolnaerim.watch.document.asyncapi.rsocket;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.byeolnaerim.watch.ProjectDefaults;
import com.byeolnaerim.watch.document.AbstractSpoonDocumentWatcher;
import com.byeolnaerim.watch.document.SpoonAnalysisContext;
import com.byeolnaerim.watch.document.SpoonAnalysisRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * Watches source files and regenerates an AsyncAPI JSON document for RSocket endpoints.
 */
public class RsoketAsyncApiJsonFileWatcher extends AbstractSpoonDocumentWatcher {

	/** Immutable configuration for {@link RsoketAsyncApiJsonFileWatcher}. */
	public static final class Config {

		private final String watchDirectory;

		private final String asyncApiOutputFile;

		private final List<String> decompileJarPaths;

		private final List<String> sourceClasspath;

		private final List<Class<?>> decompileJarClasses;

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

			this.decompileJarPaths = List.copyOf( b.decompileJarPaths );
			this.sourceClasspath = List.copyOf( b.sourceClasspath );
			this.decompileJarClasses = List.copyOf( b.decompileJarClasses );

		}

		public String watchDirectory() {

			return watchDirectory;

		}

		public String asyncApiOutputFile() {

			return asyncApiOutputFile;

		}

		public List<String> decompileJarPaths() {

			return decompileJarPaths;

		}

		public List<String> sourceClasspath() {

			return sourceClasspath;

		}

		public List<Class<?>> decompileJarClasses() {

			return decompileJarClasses;

		}

		public static Builder builder() {

			return new Builder();

		}

		/** Builder for {@link RsoketAsyncApiJsonFileWatcher.Config}. */
		public static final class Builder {

			private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;

			private String asyncApiOutputFile = "src/main/resources/static/asyncapi-rsocket.json";

			private final List<String> decompileJarPaths = new ArrayList<>();

			private final List<String> sourceClasspath = new ArrayList<>();

			private final List<Class<?>> decompileJarClasses = new ArrayList<>();

			public Builder watchDirectory(
				String p
			) {

				this.watchDirectory = p;
				return this;

			}

			public Builder asyncApiOutputFile(
				String p
			) {

				this.asyncApiOutputFile = p;
				return this;

			}

			public Builder addDecompileJar(
				String jarPath
			) {

				this.decompileJarPaths.add( jarPath );
				return this;

			}

			public Builder decompileJars(
				List<String> jarPaths
			) {

				this.decompileJarPaths.clear();
				this.decompileJarPaths.addAll( jarPaths );
				return this;

			}

			public Builder addSourceClasspath(
				String classpathEntry
			) {

				this.sourceClasspath.add( classpathEntry );
				return this;

			}

			public Builder sourceClasspath(
				List<String> entries
			) {

				this.sourceClasspath.clear();
				this.sourceClasspath.addAll( entries );
				return this;

			}

			/**
			 * Adds a marker class loaded from the external jar whose source types must be
			 * available while parsing RSocket request and response schemas.
			 */
			public Builder addDecompileJarClass(
				Class<?> markerClass
			) {

				this.decompileJarClasses.add( markerClass );
				return this;

			}

			public Builder decompileJarClasses(
				List<Class<?>> markerClasses
			) {

				this.decompileJarClasses.clear();
				this.decompileJarClasses.addAll( markerClasses );
				return this;

			}

			public Config build() {

				return new Config( this );

			}

		}

	}

	private final Config config;

	public RsoketAsyncApiJsonFileWatcher(
											Config config
	) {

		this.config = config;

	}


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
			SpoonAnalysisContext analysis = analyzeSpoon(
					SpoonAnalysisRequest
						.of(
							config.watchDirectory(),
							config.sourceClasspath(),
							config.decompileJarPaths(),
							config.decompileJarClasses()
						)
				);

			RsoketParser parser = new RsoketParser( analysis.externalTypes() );
			List<RsoketRouteInfo> routes = parser.extractRsoketRoutes( analysis.projectModel().getAllTypes() );
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
