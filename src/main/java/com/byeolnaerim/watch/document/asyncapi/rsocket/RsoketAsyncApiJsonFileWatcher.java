package com.byeolnaerim.watch.document.asyncapi.rsocket;


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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.benf.cfr.reader.api.CfrDriver;
import com.byeolnaerim.watch.AbstractWatcher;
import com.byeolnaerim.watch.ProjectDefaults;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;


/**
 * Watches source files and regenerates an AsyncAPI JSON document for RSocket endpoints.
 */
public class RsoketAsyncApiJsonFileWatcher extends AbstractWatcher {

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
			Set<String> effectiveSourceClasspath = buildEffectiveSourceClasspath();

			Launcher launcher = new Launcher();
			launcher.addInputResource( config.watchDirectory() );
			launcher.getEnvironment().setAutoImports( true );
			launcher.getEnvironment().setNoClasspath( true );

			if (! effectiveSourceClasspath.isEmpty()) {
				launcher.getEnvironment().setSourceClasspath( effectiveSourceClasspath.toArray( String[]::new ) );

			}

			launcher.buildModel();
			CtModel model = launcher.getModel();
			Map<String, CtType<?>> externalTypes = buildExternalTypeRegistry( effectiveSourceClasspath );

			RsoketParser parser = new RsoketParser( externalTypes );
			List<RsoketRouteInfo> routes = parser.extractRsoketRoutes( model.getAllTypes() );
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

	private Set<String> buildEffectiveSourceClasspath() {

		Set<String> effectiveSourceClasspath = new LinkedHashSet<>();
		effectiveSourceClasspath.addAll( collectRuntimeClasspathEntries() );

		if (! config.sourceClasspath().isEmpty()) {
			effectiveSourceClasspath
				.addAll(
					config
						.sourceClasspath()
						.stream()
						.map( p -> Paths.get( p ).toAbsolutePath().normalize().toString() )
						.toList()
				);

		}

		for (Class<?> markerClass : config.decompileJarClasses()) {
			effectiveSourceClasspath.add( resolveClassLocation( markerClass ).toString() );

		}

		effectiveSourceClasspath
			.addAll(
				config
					.decompileJarPaths()
					.stream()
					.map( p -> Paths.get( p ).toAbsolutePath().normalize().toString() )
					.toList()
			);

		return effectiveSourceClasspath
			.stream()
			.filter( this::isValidClasspathEntry )
			.collect( Collectors.toCollection( LinkedHashSet::new ) );

	}

	private List<String> collectRuntimeClasspathEntries() {

		String rawClasspath = System.getProperty( "java.class.path", "" );

		if (rawClasspath == null || rawClasspath.isBlank()) {
			return List.of();

		}

		List<String> result = new ArrayList<>();

		for (String entry : rawClasspath.split( java.util.regex.Pattern.quote( File.pathSeparator ) )) {
			if (entry == null || entry.isBlank())
				continue;

			Path path = Paths.get( entry ).toAbsolutePath().normalize();

			if (Files.exists( path )) {
				result.add( path.toString() );

			}

		}

		return result;

	}

	private boolean isValidClasspathEntry(
		String entry
	) {

		try {
			Path path = Paths.get( entry ).toAbsolutePath().normalize();

			if (! Files.exists( path ))
				return false;

			if (Files.isRegularFile( path )) {
				return path.getFileName().toString().toLowerCase().endsWith( ".jar" );

			}

			return Files.isDirectory( path ) && containsClassFile( path );

		} catch (Exception e) {
			return false;

		}

	}

	private boolean containsClassFile(
		Path dir
	) {

		try (Stream<Path> walk = Files.walk( dir )) {
			return walk.anyMatch( p -> Files.isRegularFile( p ) && p.getFileName().toString().endsWith( ".class" ) );

		} catch (IOException e) {
			return false;

		}

	}

	private Map<String, CtType<?>> buildExternalTypeRegistry(
		Set<String> effectiveSourceClasspath
	) {

		Map<String, CtType<?>> result = new HashMap<>();
		Set<Path> jarLocations = new LinkedHashSet<>();

		for (String jarPath : config.decompileJarPaths()) {
			jarLocations.add( Paths.get( jarPath ).toAbsolutePath().normalize() );

		}

		for (Class<?> markerClass : config.decompileJarClasses()) {
			Path location = resolveClassLocation( markerClass );

			if (Files.isRegularFile( location ) && location.getFileName().toString().toLowerCase().endsWith( ".jar" )) {
				jarLocations.add( location );

			}

		}

		for (Path jarLocation : jarLocations) {
			Path decompiledSourceDir = decompileJarToSourceDir( jarLocation.toString(), effectiveSourceClasspath );
			loadExternalTypesFromSourceDir( result, decompiledSourceDir, effectiveSourceClasspath );

		}

		return result;

	}

	private void loadExternalTypesFromSourceDir(
		Map<String, CtType<?>> result, Path sourceDir, Set<String> effectiveSourceClasspath
	) {

		Launcher externalLauncher = new Launcher();
		externalLauncher.addInputResource( sourceDir.toString() );
		externalLauncher.getEnvironment().setAutoImports( true );
		externalLauncher.getEnvironment().setNoClasspath( true );

		if (! effectiveSourceClasspath.isEmpty()) {
			externalLauncher.getEnvironment().setSourceClasspath( effectiveSourceClasspath.toArray( String[]::new ) );

		}

		externalLauncher.buildModel();

		for (CtType<?> type : externalLauncher.getModel().getAllTypes()) {
			result.put( type.getQualifiedName(), type );

		}

	}

	private Path resolveClassLocation(
		Class<?> markerClass
	) {

		if (markerClass == null) {
			throw new IllegalArgumentException( "markerClass must not be null" );

		}

		ProtectionDomain protectionDomain = markerClass.getProtectionDomain();
		CodeSource codeSource = protectionDomain != null ? protectionDomain.getCodeSource() : null;

		if (codeSource == null || codeSource.getLocation() == null) {
			throw new IllegalStateException( "CodeSource location is null for class: " + markerClass.getName() );

		}

		try {
			URI uri = codeSource.getLocation().toURI();
			return Paths.get( uri ).toAbsolutePath().normalize();

		} catch (Exception e) {
			throw new IllegalStateException( "Failed to resolve class location: " + markerClass.getName(), e );

		}

	}

	private Path decompileJarToSourceDir(
		String jarPath, Set<String> effectiveSourceClasspath
	) {

		try {
			Path jar = Paths.get( jarPath ).toAbsolutePath().normalize();

			if (! Files.isRegularFile( jar ) || ! jar.getFileName().toString().toLowerCase().endsWith( ".jar" )) {
				throw new IllegalArgumentException( "Not a jar file: " + jar );

			}

			String fileName = jar.getFileName().toString();
			String baseName = stripExtension( fileName );
			String hash = Integer.toHexString( jar.toString().hashCode() );
			String dirName = (baseName + "-" + hash).replaceAll( "[^a-zA-Z0-9._-]", "_" );
			Path outputDir = Paths.get( "build", "spoon-decompiled", dirName );

			recreateDirectory( outputDir );

			Map<String, String> options = new HashMap<>();
			options.put( "outputdir", outputDir.toString() );

			if (! effectiveSourceClasspath.isEmpty()) {
				options.put( "extraclasspath", String.join( File.pathSeparator, effectiveSourceClasspath ) );

			}

			new CfrDriver.Builder()
				.withOptions( options )
				.build()
				.analyse( List.of( jar.toString() ) );

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

}
