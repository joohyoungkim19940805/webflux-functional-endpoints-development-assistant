package com.byeolnaerim.watch.document;


import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Immutable input for a shared Spoon analysis pass.
 */
public record SpoonAnalysisRequest(
	Path watchDirectory,
	List<Path> sourceClasspath,
	List<Path> decompileJarLocations
) {

	public SpoonAnalysisRequest {

		watchDirectory = watchDirectory.toAbsolutePath().normalize();
		sourceClasspath = normalizePaths( sourceClasspath );
		decompileJarLocations = normalizePaths( decompileJarLocations );

	}

	public static SpoonAnalysisRequest of(
		String watchDirectory,
		List<String> sourceClasspath,
		List<String> decompileJarPaths,
		List<Class<?>> decompileJarClasses
	) {

		List<Path> explicitClasspath = new ArrayList<>();
		Set<Path> jarLocations = new LinkedHashSet<>();

		if (sourceClasspath != null) {

			for (String entry : sourceClasspath) {
				if (entry != null && ! entry.isBlank()) {
					explicitClasspath.add( Paths.get( entry ) );

				}

			}

		}

		if (decompileJarPaths != null) {

			for (String jarPath : decompileJarPaths) {
				if (jarPath != null && ! jarPath.isBlank()) {
					Path location = Paths.get( jarPath ).toAbsolutePath().normalize();
					explicitClasspath.add( location );
					jarLocations.add( location );

				}

			}

		}

		if (decompileJarClasses != null) {

			for (Class<?> markerClass : decompileJarClasses) {
				Path location = resolveClassLocation( markerClass );
				explicitClasspath.add( location );

				if (location.getFileName() != null && location.getFileName().toString().toLowerCase().endsWith( ".jar" )) {
					jarLocations.add( location );

				}

			}

		}

		return new SpoonAnalysisRequest(
			Paths.get( watchDirectory ),
			explicitClasspath,
			List.copyOf( jarLocations )
		);

	}

	private static List<Path> normalizePaths(
		List<Path> paths
	) {

		if (paths == null || paths.isEmpty()) {
			return List.of();

		}

		return paths
			.stream()
			.filter( java.util.Objects::nonNull )
			.map( path -> path.toAbsolutePath().normalize() )
			.distinct()
			.toList();

	}

	private static Path resolveClassLocation(
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

}
