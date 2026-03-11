package com.byeolnaerim.watch.document.swagger;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.byeolnaerim.watch.AbstractWatcher;
import com.byeolnaerim.watch.ProjectDefaults;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.swagger.functional.HandlerParser;
import com.byeolnaerim.watch.document.swagger.functional.RouteInfo;
import com.byeolnaerim.watch.document.swagger.functional.RouteParser;
import com.byeolnaerim.watch.document.swagger.mvc.MvcParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
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
public class SwaggerJsonFileWatcher extends AbstractWatcher {

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

	/**
	 * Executes a single Swagger generation pass and writes the output file only when changed.
	 *
	 * @return a {@link Mono} emitting {@code true} if the Swagger file was updated
	 */
	@Override
	public Mono<Boolean> runGenerateTask() {

		return Mono.fromCallable( () -> {
			// 1) 기존 generate 로직으로 JSON 문자열 구성
			String json = generateSwaggerJson(); // <- 기존 함수 그대로
			// 2) 실제 파일에 "변경 시에만" 기록
			Path out = Paths.get( config.swaggerOutputFile );
			return writeIfChanged( out, json.getBytes( StandardCharsets.UTF_8 ) );

		} ).subscribeOn( Schedulers.boundedElastic() );

	}

	/** 감시 루트 제공 */
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

	/** Swagger JSON 생성 */
	private String generateSwaggerJson() {

		try {
			List<RouteInfo> routeInfos = extractRouteInfos(); // RouteInfo 리스트 추출
			routeInfos
				.sort(
					Comparator
						.comparing( RouteInfo::getUrl )
						.thenComparing( RouteInfo::getHttpMethod )
				);

			String swaggerJson = SwaggerGenerator.generateSwaggerJson( routeInfos ); // Swagger JSON 생성
			return swaggerJson;

			// try (FileWriter writer = new FileWriter( config.swaggerOutputFile )) {
			// writer.write( swaggerJson );
			// System.out.println( "Swagger JSON file updated: " + config.swaggerOutputFile );
			// return swaggerJson;
			//
			// }

		} catch (Exception e) {
			e.printStackTrace();

		}

		return "";

	}

	/** Spoon 기반으로 RouterFunction에서 라우트 정보 추출 */
	private List<RouteInfo> extractRouteInfos() {

		Launcher launcher = new Launcher();
		launcher.addInputResource( config.watchDirectory );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );
		launcher.buildModel();

		CtModel model = launcher.getModel();

		// MVC 모드면 annotated 기반 파서로
		if (config.projectMode() == ProjectMode.MVC) { return MvcParser.parseRoutes( model ); }

		List<CtMethod<?>> routerMethods = model
			.getElements(
				(CtMethod<?> m) -> m.getAnnotations().stream().anyMatch( a -> a.getAnnotationType().getSimpleName().equals( "Bean" ) ) && m.getType().getSimpleName().contains( "RouterFunction" )
			);

		List<RouteInfo> routeInfos = new ArrayList<>();

		for (CtMethod<?> routerMethod : routerMethods) {
			@SuppressWarnings("rawtypes")
			List<CtInvocation> httpCalls = routerMethod
				.getElements( new TypeFilter<>( CtInvocation.class ) )
				.stream()
				.filter( inv -> RouteParser.HTTP_METHODS.contains( inv.getExecutable().getSimpleName() ) )
				.toList();

			for (CtInvocation<?> httpCall : httpCalls) {
				RouteInfo routeInfo = RouteParser.extractRouteInfoFromHttpCall( httpCall, routerMethod.getSimpleName() );
				HandlerParser handlerParser = new HandlerParser();
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

	// public static void main(
	// String[] args
	// ) {
	//
	// SwaggerJsonFileWatcher watcher = new SwaggerJsonFileWatcher(
	// SwaggerJsonFileWatcher.Config
	// .builder()
	// .watchDirectory( "src/main/java" )
	// .swaggerOutputFile( "src/main/resources/static/swagger.json" )
	// .build()
	// );
	// watcher.generateSwaggerJson(); // 단발 실행
	//
	// }

}
