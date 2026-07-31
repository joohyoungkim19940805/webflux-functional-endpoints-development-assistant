package com.byeolnaerim.watch.document.swagger;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import com.byeolnaerim.watch.ProjectDefaults;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.AbstractSpoonDocumentWatcher;
import com.byeolnaerim.watch.document.SpoonAnalysisContext;
import com.byeolnaerim.watch.document.SpoonAnalysisRequest;
import com.byeolnaerim.watch.document.swagger.functional.HandlerParser;
import com.byeolnaerim.watch.document.swagger.functional.RouteInfo;
import com.byeolnaerim.watch.document.swagger.functional.RouteParser;
import com.byeolnaerim.watch.document.swagger.mvc.MvcParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;


/**
 * Generates a Swagger/OpenAPI JSON document from the given route metadata.
 *
 * @param routeInfos
 *            the parsed route metadata
 * 
 * @return the generated Swagger JSON string
 * 
 * @throws Exception
 *             if JSON generation fails
 */
public class SwaggerJsonFileWatcher extends AbstractSpoonDocumentWatcher {

	/**
	 * Supported source parsing modes for Swagger generation.
	 */
	public static enum ProjectMode {
		/** Parses annotated MVC controller endpoints. */
		MVC, //
		/** Parses functional endpoints based on {@code RouterFunction}. */
		FUNCTIONAL_ENDPOINT
	}

	/**
	 * Immutable configuration for {@link SwaggerJsonFileWatcher}.
	 */
	public static final class Config {

		private final String watchDirectory;

		private final String swaggerOutputFile; // = "src/main/resources/static/swagger.json";

		private final ProjectMode projectMode;

		private final List<String> decompileJarPaths;

		private final List<String> sourceClasspath;

		private final List<Class<?>> decompileJarClasses;

		private final BiPredicate<Class<?>, Map<String, Object>> customTypeMapper;

		private Config(
						Builder b
		) {

			this.watchDirectory = b.watchDirectory.replace( '\\', '/' ).replace( '.', '/' );
			int lastDotIndex = b.swaggerOutputFile.lastIndexOf( '.' );

			if (lastDotIndex == -1) {
				this.swaggerOutputFile = b.swaggerOutputFile.replace( '\\', '/' ).replace( '.', '/' ) + ".json";

			} else {
				this.swaggerOutputFile = b.swaggerOutputFile.substring( 0, lastDotIndex ).replace( '\\', '/' ).replace( '.', '/' ) + b.swaggerOutputFile.substring( lastDotIndex );

			}

			this.projectMode = (b.projectMode != null) ? b.projectMode : ProjectMode.FUNCTIONAL_ENDPOINT;

			this.decompileJarPaths = List.copyOf( b.decompileJarPaths );
			this.sourceClasspath = List.copyOf( b.sourceClasspath );
			this.decompileJarClasses = List.copyOf( b.decompileJarClasses );
			this.customTypeMapper = b.customTypeMapper;

		}

		/** get watchDirectory */
		public String watchDirectory() {

			return watchDirectory;

		}

		/** swaggerOutputFile */
		public String swaggerOutputFile() {

			return swaggerOutputFile;

		}

		/** projectMode */
		public ProjectMode projectMode() {

			return projectMode;

		}

		/** decompileJarPaths */
		public List<String> decompileJarPaths() {

			return decompileJarPaths;

		}

		/** sourceClasspath */
		public List<String> sourceClasspath() {

			return sourceClasspath;

		}

		/** decompileJarClasses */
		public List<Class<?>> decompileJarClasses() {

			return decompileJarClasses;

		}

		/* customTypeMapper */
		public BiPredicate<Class<?>, Map<String, Object>> customTypeMapper() {

			return customTypeMapper;

		}

		/**
		 * Creates a new Swagger watcher configuration builder.
		 *
		 * @return a new builder
		 */
		public static Builder builder() {

			return new Builder();

		}

		/**
		 * Builder for {@link SwaggerJsonFileWatcher.Config}.
		 */
		public static final class Builder {

			private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;

			private String swaggerOutputFile = ProjectDefaults.SWAGGER_OUTPUT_FILE;

			private ProjectMode projectMode = ProjectMode.FUNCTIONAL_ENDPOINT;

			private final List<String> decompileJarPaths = new ArrayList<>();

			private final List<String> sourceClasspath = new ArrayList<>();

			private final List<Class<?>> decompileJarClasses = new ArrayList<>();

			private BiPredicate<Class<?>, Map<String, Object>> customTypeMapper;

			/**
			 * Sets the source directory to watch and analyze.
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
			 * Sets the target output path of the generated Swagger JSON file.
			 *
			 * @param p
			 *            the output file path
			 * 
			 * @return this builder
			 */
			public Builder swaggerOutputFile(
				String p
			) {

				this.swaggerOutputFile = p;
				return this;

			}

			/**
			 * Sets the parsing mode used for route extraction.
			 *
			 * @param mode
			 *            the project mode
			 * 
			 * @return this builder
			 */
			public Builder projectMode(
				ProjectMode mode
			) {

				this.projectMode = (mode != null) ? mode : ProjectMode.FUNCTIONAL_ENDPOINT;
				return this;

			}


			/**
			 * Adds a decompile jar.
			 *
			 * @param jarPath
			 *            jar path
			 * 
			 * @return this builder
			 */
			public Builder addDecompileJar(
				String jarPath
			) {

				this.decompileJarPaths.add( jarPath );
				return this;

			}

			/**
			 * Replaces the decompile jar.
			 *
			 * @param jarPaths
			 *            jar paths
			 * 
			 * @return this builder
			 */
			public Builder decompileJars(
				List<String> jarPaths
			) {

				this.decompileJarPaths.clear();
				this.decompileJarPaths.addAll( jarPaths );
				return this;

			}

			/**
			 * Adds a source classpath.
			 *
			 * @param classpathEntry
			 *            classpathEntry
			 * 
			 * @return this builder
			 */
			public Builder addSourceClasspath(
				String classpathEntry
			) {

				this.sourceClasspath.add( classpathEntry );
				return this;

			}

			/**
			 * Replaces the source classpath.
			 *
			 * @param entries
			 *            classpath entries
			 * 
			 * @return this builder
			 */
			public Builder sourceClasspath(
				List<String> entries
			) {

				this.sourceClasspath.clear();
				this.sourceClasspath.addAll( entries );
				return this;

			}

			/**
			 * Adds a decompile jar marker class.
			 * Any class loaded from the target external jar is acceptable.
			 *
			 * @param markerClass
			 *            class loaded from the target jar
			 *
			 * @return this builder
			 */
			public Builder addDecompileJarClass(
				Class<?> markerClass
			) {

				this.decompileJarClasses.add( markerClass );
				return this;

			}

			/**
			 * Replaces the decompile jar marker classes.
			 *
			 * @param markerClasses
			 *            marker classes loaded from target jars
			 *
			 * @return this builder
			 */
			public Builder decompileJarClasses(
				List<Class<?>> markerClasses
			) {

				this.decompileJarClasses.clear();
				this.decompileJarClasses.addAll( markerClasses );
				return this;

			}

			/**
			 * Sets a custom type mapper.
			 * <p>
			 * The mapper receives the Java type and the OpenAPI schema map to mutate.
			 * If it returns {@code true}, the default type mapping stops and the mutated
			 * schema is used as-is.
			 * </p>
			 *
			 * @param customTypeMapper
			 *            custom type mapper
			 *
			 * @return this builder
			 */
			public Builder customTypeMapper(
				BiPredicate<Class<?>, Map<String, Object>> customTypeMapper
			) {

				this.customTypeMapper = customTypeMapper;
				return this;

			}

			/**
			 * Builds an immutable {@link Config} instance.
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
	 * Creates a new Swagger JSON watcher.
	 *
	 * @param config
	 *            the watcher configuration
	 */
	public SwaggerJsonFileWatcher(
									Config config
	) {

		this.config = config;

	}


	@Override
	public Mono<Boolean> runGenerateTask() {

		return Mono.fromCallable( () -> {

			try {


				String json = generateSwaggerJson();
				Path out = Paths.get( config.swaggerOutputFile() );
				return writeIfChanged( out, json.getBytes( StandardCharsets.UTF_8 ) );

			} catch (Exception e) {
				e.printStackTrace();
				return false;

			}

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

	private String generateSwaggerJson() throws Exception {

		SpoonAnalysisContext analysis = analyzeSpoon(
				SpoonAnalysisRequest
					.of(
						config.watchDirectory(),
						config.sourceClasspath(),
						config.decompileJarPaths(),
						config.decompileJarClasses()
					)
			);

		List<RouteInfo> routeInfos = extractRouteInfos( analysis );
		routeInfos
			.sort(
				Comparator
					.comparing( RouteInfo::getUrl )
					.thenComparing( RouteInfo::getHttpMethod )
			);

		return SwaggerGenerator.generateSwaggerJson( routeInfos, config.customTypeMapper() );

	}

	/** Spoon 기반으로 RouterFunction에서 라우트 정보 추출 */
	private List<RouteInfo> extractRouteInfos(
		SpoonAnalysisContext analysis
	) {

		CtModel model = analysis.projectModel();
		Map<String, CtType<?>> externalTypes = analysis.externalTypes();

		if (config.projectMode() == ProjectMode.MVC) {
			return MvcParser.parseRoutes( model, externalTypes );

		}

		List<CtMethod<?>> routerMethods = model
			.getElements(
				(CtMethod<?> m) -> m
					.getAnnotations()
					.stream()
					.anyMatch( a -> a.getAnnotationType().getSimpleName().equals( "Bean" ) )
					&& m.getType().getSimpleName().contains( "RouterFunction" )
			);

		List<RouteInfo> routeInfos = new ArrayList<>();
		HandlerParser handlerParser = new HandlerParser( externalTypes );

		for (CtMethod<?> routerMethod : routerMethods) {
			@SuppressWarnings("rawtypes")
			List<CtInvocation> httpCalls = routerMethod
				.getElements( new TypeFilter<>( CtInvocation.class ) )
				.stream()
				.filter( inv -> RouteParser.HTTP_METHODS.contains( inv.getExecutable().getSimpleName() ) )
				.toList();

			for (CtInvocation<?> httpCall : httpCalls) {
				RouteInfo routeInfo = RouteParser.extractRouteInfoFromHttpCall( httpCall, routerMethod.getSimpleName() );
				routeInfo
					.setHandlerInfo(
						handlerParser
							.parseHandler(
								routeInfo.getHandlerInfoCtExpression(),
								RouteUtil.convertPathToMethodName( routeInfo.getUrl() )
							)
					);
				routeInfos.add( routeInfo );

			}

		}

		return routeInfos;

	}

}
