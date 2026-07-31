package com.byeolnaerim.watch.document;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.byeolnaerim.watch.FileChange;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;


/**
 * Builds and shares Spoon models between Swagger and AsyncAPI generators.
 * <p>Project models are reused until a relevant source or classpath event explicitly
 * invalidates them. Decompiled external-jar models and runtime classpath inspection
 * results are cached across cycles while their source jar fingerprint remains unchanged.</p>
 */
public final class SpoonAnalysisCache {

	private record ProjectModelKey(
		Path watchDirectory,
		String classpathSignature
	) {}

	private record ExternalModelKey(
		Path jarLocation,
		long size,
		long lastModifiedMillis,
		String classpathSignature
	) {}

	private record RuntimeClasspathSnapshot(
		String rawClasspath,
		List<Path> entries
	) {}

	private record ExternalSpoonModel(
		CtModel model,
		Map<String, CtType<?>> types
	) {}

	private static final Object RUNTIME_CLASSPATH_LOCK = new Object();

	private static volatile RuntimeClasspathSnapshot runtimeClasspathSnapshot;

	private static final ConcurrentMap<Path, Boolean> CLASSPATH_ENTRY_VALIDITY = new ConcurrentHashMap<>();

	private static final ConcurrentMap<ExternalModelKey, ExternalSpoonModel> EXTERNAL_MODEL_CACHE = new ConcurrentHashMap<>();

	private final ConcurrentMap<ProjectModelKey, CtModel> projectModelCache = new ConcurrentHashMap<>();

	/**
	 * Clears all project-source models. External jar models remain cached until
	 * their jar fingerprint changes or an explicit classpath invalidation occurs.
	 */
	public void invalidateProjectModels() {

		projectModelCache.clear();

	}

	/**
	 * Backward-compatible alias used by standalone document watchers.
	 */
	public void beginGeneration() {

		invalidateProjectModels();

	}

	/**
	 * Invalidates only project models affected by a source change batch. A Java
	 * modification always requires a new Spoon model. Create/delete events for a
	 * Java file or package directory are structural and also invalidate the model.
	 */
	public void invalidateProjectModels(
		Collection<FileChange> changes
	) {

		if (changes == null || changes.isEmpty() || projectModelCache.isEmpty()) {
			return;

		}

		if (changes.stream().anyMatch( FileChange::isOverflow )) {
			invalidateProjectModels();
			return;

		}

		for (FileChange change : changes) {
			if (change == null || change.path() == null) {
				continue;

			}

			if (! change.isJavaSource() && ! change.isStructuralSourceChange()) {
				continue;

			}

			Path changedPath = change.path().toAbsolutePath().normalize();

			projectModelCache
				.keySet()
				.removeIf( key -> changedPath.startsWith( key.watchDirectory() ) );

		}

	}

	/**
	 * Invalidates cache entries affected by a runtime classpath change. Project
	 * models are cleared because their symbol resolution can change. External
	 * models are removed only when the changed artifact is a jar, avoiding a full
	 * external-model rebuild for every project class compilation.
	 */
	public void invalidateClasspath(
		FileChange change
	) {

		if (change == null) {
			return;

		}

		invalidateProjectModels();

		if (change.isOverflow()) {
			runtimeClasspathSnapshot = null;
			CLASSPATH_ENTRY_VALIDITY.clear();
			EXTERNAL_MODEL_CACHE.clear();
			return;

		}

		Path changedPath = change.path();

		if (changedPath == null) {
			return;

		}

		changedPath = changedPath.toAbsolutePath().normalize();
		runtimeClasspathSnapshot = null;
		CLASSPATH_ENTRY_VALIDITY.remove( changedPath );

		if (changedPath.getFileName() != null
			&& changedPath.getFileName().toString().toLowerCase().endsWith( ".jar" )) {
			Path jarPath = changedPath;
			EXTERNAL_MODEL_CACHE.keySet().removeIf( key -> key.jarLocation().equals( jarPath ) );

		}

	}

	/**
	 * Returns one shared analysis context for the supplied request.
	 */
	public SpoonAnalysisContext analyze(
		SpoonAnalysisRequest request
	) {

		Set<String> effectiveSourceClasspath = buildEffectiveSourceClasspath( request );
		String classpathSignature = buildClasspathSignature( effectiveSourceClasspath );
		ProjectModelKey projectKey = new ProjectModelKey( request.watchDirectory(), classpathSignature );

		CtModel projectModel = projectModelCache.computeIfAbsent(
			projectKey,
			ignored -> buildProjectModel( request.watchDirectory(), effectiveSourceClasspath )
		);

		Map<String, CtType<?>> externalTypes = new LinkedHashMap<>();

		for (Path jarLocation : request.decompileJarLocations()) {
			externalTypes.putAll( loadExternalTypes( jarLocation, effectiveSourceClasspath, classpathSignature ) );

		}

		return new SpoonAnalysisContext( projectModel, externalTypes, effectiveSourceClasspath );

	}

	private CtModel buildProjectModel(
		Path watchDirectory,
		Set<String> effectiveSourceClasspath
	) {

		Launcher launcher = createLauncher( watchDirectory, effectiveSourceClasspath );
		launcher.buildModel();
		return launcher.getModel();

	}

	private Map<String, CtType<?>> loadExternalTypes(
		Path jarLocation,
		Set<String> effectiveSourceClasspath,
		String classpathSignature
	) {

		Path normalizedJar = jarLocation.toAbsolutePath().normalize();

		try {
			ExternalModelKey key = new ExternalModelKey(
				normalizedJar,
				Files.size( normalizedJar ),
				Files.getLastModifiedTime( normalizedJar ).toMillis(),
				classpathSignature
			);

			EXTERNAL_MODEL_CACHE
				.keySet()
				.removeIf(
					existing -> existing.jarLocation().equals( normalizedJar )
						&& (existing.size() != key.size() || existing.lastModifiedMillis() != key.lastModifiedMillis())
				);

			return EXTERNAL_MODEL_CACHE
				.computeIfAbsent(
					key,
					ignored -> buildExternalModel( normalizedJar, effectiveSourceClasspath )
				)
				.types();

		} catch (IOException e) {
			throw new RuntimeException( "Failed to inspect external jar: " + normalizedJar, e );

		}

	}

	private ExternalSpoonModel buildExternalModel(
		Path jarLocation,
		Set<String> effectiveSourceClasspath
	) {

		Path sourceDir = JarSourceDecompiler.decompile( jarLocation.toString(), effectiveSourceClasspath );
		Launcher launcher = createLauncher( sourceDir, effectiveSourceClasspath );
		launcher.buildModel();

		Map<String, CtType<?>> result = new LinkedHashMap<>();

		for (CtType<?> type : launcher.getModel().getAllTypes()) {
			result.put( type.getQualifiedName(), type );

		}

		return new ExternalSpoonModel( launcher.getModel(), Map.copyOf( result ) );

	}

	private Launcher createLauncher(
		Path input,
		Set<String> effectiveSourceClasspath
	) {

		Launcher launcher = new Launcher();
		launcher.addInputResource( input.toString() );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );

		if (! effectiveSourceClasspath.isEmpty()) {
			launcher.getEnvironment().setSourceClasspath( effectiveSourceClasspath.toArray( String[]::new ) );

		}

		return launcher;

	}

	private Set<String> buildEffectiveSourceClasspath(
		SpoonAnalysisRequest request
	) {

		Set<Path> candidates = new LinkedHashSet<>( collectRuntimeClasspathEntries() );
		candidates.addAll( request.sourceClasspath() );
		candidates.addAll( request.decompileJarLocations() );

		Set<String> result = new LinkedHashSet<>();

		for (Path candidate : candidates) {
			Path normalized = candidate.toAbsolutePath().normalize();

			if (isValidClasspathEntry( normalized )) {
				result.add( normalized.toString() );

			}

		}

		return result;

	}

	private List<Path> collectRuntimeClasspathEntries() {

		String rawClasspath = System.getProperty( "java.class.path", "" );
		RuntimeClasspathSnapshot snapshot = runtimeClasspathSnapshot;

		if (snapshot != null && snapshot.rawClasspath().equals( rawClasspath )) {
			return snapshot.entries();

		}

		synchronized (RUNTIME_CLASSPATH_LOCK) {
			snapshot = runtimeClasspathSnapshot;

			if (snapshot != null && snapshot.rawClasspath().equals( rawClasspath )) {
				return snapshot.entries();

			}

			List<Path> entries = new ArrayList<>();

			if (rawClasspath != null && ! rawClasspath.isBlank()) {

				for (String entry : rawClasspath.split( java.util.regex.Pattern.quote( File.pathSeparator ) )) {
					if (entry == null || entry.isBlank())
						continue;

					Path path = Paths.get( entry ).toAbsolutePath().normalize();

					if (isValidClasspathEntry( path )) {
						entries.add( path );

					}

				}

			}

			runtimeClasspathSnapshot = new RuntimeClasspathSnapshot( rawClasspath, List.copyOf( entries ) );
			return runtimeClasspathSnapshot.entries();

		}

	}

	private boolean isValidClasspathEntry(
		Path path
	) {

		if (Boolean.TRUE.equals( CLASSPATH_ENTRY_VALIDITY.get( path ) )) {
			return true;

		}

		boolean valid = inspectClasspathEntry( path );

		if (valid) {
			CLASSPATH_ENTRY_VALIDITY.put( path, true );

		}

		return valid;

	}

	private boolean inspectClasspathEntry(
		Path path
	) {

		try {

			if (! Files.exists( path )) {
				return false;

			}

			if (Files.isRegularFile( path )) {
				return path.getFileName().toString().toLowerCase().endsWith( ".jar" );

			}

			return Files.isDirectory( path );

		} catch (Exception e) {
			return false;

		}

	}

	private String buildClasspathSignature(
		Set<String> effectiveSourceClasspath
	) {

		StringBuilder signature = new StringBuilder();

		for (String entry : effectiveSourceClasspath) {
			Path path = Paths.get( entry ).toAbsolutePath().normalize();
			signature.append( path );

			try {

				if (Files.isRegularFile( path )) {
					signature
						.append( ':' )
						.append( Files.getLastModifiedTime( path ).toMillis() )
						.append( ':' )
						.append( Files.size( path ) );

				}

			} catch (IOException ignore) {
				signature.append( ":missing" );

			}

			signature.append( File.pathSeparatorChar );

		}

		return signature.toString();

	}

}
