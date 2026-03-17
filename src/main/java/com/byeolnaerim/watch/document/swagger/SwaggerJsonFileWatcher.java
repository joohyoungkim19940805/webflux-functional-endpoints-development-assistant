package com.byeolnaerim.watch.document.swagger;


import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.benf.cfr.reader.api.CfrDriver;
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

		private final List<String> decompileJarPaths;

		private final List<String> sourceClasspath;

		private final List<Class<?>> decompileJarClasses;

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

			try {

				// 1) 기존 generate 로직으로 JSON 문자열 구성
				String json = generateSwaggerJson(); // <- 기존 함수 그대로
				// 2) 실제 파일에 "변경 시에만" 기록
				Path out = Paths.get( config.swaggerOutputFile );
				return writeIfChanged( out, json.getBytes( StandardCharsets.UTF_8 ) );

			} catch (Exception e) {
				e.printStackTrace();
				return false;

			}

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

	/**
	 * Swagger JSON 생성
	 * 
	 * @throws Exception
	 */
	private String generateSwaggerJson() throws Exception {


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


	}

	/** Spoon 기반으로 RouterFunction에서 라우트 정보 추출 */
	private List<RouteInfo> extractRouteInfos() {

		Launcher launcher = new Launcher();
		launcher.addInputResource( config.watchDirectory() );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );

		Set<String> effectiveSourceClasspath = new LinkedHashSet<>();

		if (config.sourceClasspath() != null && ! config.sourceClasspath().isEmpty()) {
			effectiveSourceClasspath.addAll( config.sourceClasspath() );

		}

		// marker class가 로드된 실제 위치(jar 또는 classes dir)를 classpath에도 반영
		if (config.decompileJarClasses() != null && ! config.decompileJarClasses().isEmpty()) {

			for (Class<?> markerClass : config.decompileJarClasses()) {
				Path location = resolveClassLocation( markerClass );
				effectiveSourceClasspath.add( location.toString() );

			}

		}

		// path로 직접 추가한 jar도 classpath에 반영
		if (config.decompileJarPaths() != null && ! config.decompileJarPaths().isEmpty()) {
			effectiveSourceClasspath.addAll( config.decompileJarPaths() );

		}

		if (! effectiveSourceClasspath.isEmpty()) {
			launcher
				.getEnvironment()
				.setSourceClasspath(
					effectiveSourceClasspath.toArray( String[]::new )
				);

		}

		// 직접 경로로 받은 jar는 디컴파일해서 소스 입력으로 추가
		for (String jarPath : config.decompileJarPaths()) {
			Path decompiledSourceDir = decompileJarToSourceDir( jarPath );
			launcher.addInputResource( decompiledSourceDir.toString() );

		}

		// marker class로 받은 외부 jar/classes 위치도 입력으로 추가
		for (Class<?> markerClass : config.decompileJarClasses()) {
			addExternalInputResourceFromMarkerClass( launcher, markerClass );

		}

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
				HandlerParser handlerParser = new HandlerParser( model );
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

	private void addExternalInputResourceFromMarkerClass(
		Launcher launcher, Class<?> markerClass
	) {

		Path location = resolveClassLocation( markerClass );

		if (Files.isDirectory( location )) {
			launcher.addInputResource( location.toString() );
			return;

		}

		String fileName = location.getFileName() == null ? "" : location.getFileName().toString().toLowerCase();

		if (! fileName.endsWith( ".jar" )) {
			throw new IllegalArgumentException(
				"Marker class location is neither a directory nor a jar file: " + location + " (class=" + markerClass.getName() + ")"
			);

		}

		Path decompiledSourceDir = decompileJarToSourceDir( location.toString() );
		launcher.addInputResource( decompiledSourceDir.toString() );

	}

	private Path resolveClassLocation(
		Class<?> markerClass
	) {

		if (markerClass == null) {
			throw new IllegalArgumentException( "markerClass must not be null" );

		}

		ProtectionDomain protectionDomain = markerClass.getProtectionDomain();

		if (protectionDomain == null) {
			throw new IllegalStateException( "ProtectionDomain is null for class: " + markerClass.getName() );

		}

		CodeSource codeSource = protectionDomain.getCodeSource();

		if (codeSource == null || codeSource.getLocation() == null) {
			throw new IllegalStateException(
				"CodeSource location is null for class: " + markerClass
					.getName() + ". If this runs from a packaged Spring Boot fat jar, pass addDecompileJar(\"/real/path/to/dependency.jar\") instead."
			);

		}

		try {
			URI uri = codeSource.getLocation().toURI();

			if (uri.getScheme() != null && ! "file".equalsIgnoreCase( uri.getScheme() )) {
				throw new IllegalStateException(
					"Unsupported marker class location URI scheme: " + uri + " for class: " + markerClass
						.getName() + ". If this runs from a packaged Spring Boot fat jar, pass addDecompileJar(\"/real/path/to/dependency.jar\") instead."
				);

			}

			return Paths.get( uri ).toAbsolutePath().normalize();

		} catch (Exception e) {
			throw new RuntimeException( "Failed to resolve class location for: " + markerClass.getName(), e );

		}

	}

	private Path decompileJarToSourceDir(
		String jarPath
	) {

		try {
			Path jar = Paths.get( jarPath ).toAbsolutePath().normalize();

			if (! Files.exists( jar )) { throw new IllegalArgumentException( "Decompile jar not found: " + jar ); }

			String fileName = jar.getFileName().toString();
			String baseName = stripExtension( fileName );
			String hash = Integer.toHexString( jar.toString().hashCode() );
			String dirName = (baseName + "-" + hash).replaceAll( "[^a-zA-Z0-9._-]", "_" );

			Path outputDir = Paths.get( "build", "spoon-decompiled", dirName );

			recreateDirectory( outputDir );

			Map<String, String> options = new HashMap<>();
			options.put( "outputdir", outputDir.toString() );

			if (config.sourceClasspath() != null && ! config.sourceClasspath().isEmpty()) {
				options.put( "extraclasspath", String.join( File.pathSeparator, config.sourceClasspath() ) );

			}

			CfrDriver driver = new CfrDriver.Builder()
				.withOptions( options )
				.build();

			driver.analyse( List.of( jar.toString() ) );

			return outputDir;

		} catch (IOException e) {
			throw new RuntimeException( "Failed to prepare decompile output directory for jar: " + jarPath, e );

		} catch (Exception e) {
			throw new RuntimeException( "Failed to decompile jar: " + jarPath, e );

		}

	}

	private void recreateDirectory(
		Path dir
	)
		throws IOException {

		if (Files.exists( dir )) {
			List<Path> paths;

			try (Stream<Path> walk = Files.walk( dir )) {
				paths = walk.sorted( Comparator.reverseOrder() ).toList();

			}

			for (Path path : paths) {
				Files.deleteIfExists( path );

			}

		}

		Files.createDirectories( dir );

	}

	private String stripExtension(
		String fileName
	) {

		int idx = fileName.lastIndexOf( '.' );
		return idx >= 0 ? fileName.substring( 0, idx ) : fileName;

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
