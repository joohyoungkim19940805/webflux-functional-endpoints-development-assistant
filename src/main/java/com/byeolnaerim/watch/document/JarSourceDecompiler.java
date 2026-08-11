package com.byeolnaerim.watch.document;


import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.benf.cfr.reader.api.CfrDriver;


/**
 * Shares decompiled source directories between document generators.
 */
public final class JarSourceDecompiler {

	private static final Map<Path, Object> DECOMPILE_LOCKS = new ConcurrentHashMap<>();

	private JarSourceDecompiler() {

	}

	public static Path decompile(
		String jarPath, Set<String> effectiveSourceClasspath
	) {

		Path jar = Paths.get( jarPath ).toAbsolutePath().normalize();

		if (! Files.isRegularFile( jar ) || ! jar.getFileName().toString().toLowerCase().endsWith( ".jar" )) {
			throw new IllegalArgumentException( "Not a jar file: " + jar );

		}

		String fileName = jar.getFileName().toString();
		String baseName = stripExtension( fileName );
		String hash = Integer.toHexString( jar.toString().hashCode() );
		String dirName = (baseName + "-" + hash).replaceAll( "[^a-zA-Z0-9._-]", "_" );
		Path outputDir = Paths
			.get( System.getProperty( "java.io.tmpdir" ), "webflux-fe-dev-assistant", "spoon-decompiled", dirName )
			.toAbsolutePath()
			.normalize();
		Path completeMarker = outputDir.resolve( ".decompile-complete" );
		Object lock = DECOMPILE_LOCKS.computeIfAbsent( outputDir, ignored -> new Object() );

		synchronized (lock) {

			try {
				String expectedMarker = Files.size( jar ) + "\n" + Files.getLastModifiedTime( jar ).toMillis();

				if (Files.isRegularFile( completeMarker ) && expectedMarker.equals( Files.readString( completeMarker, StandardCharsets.UTF_8 ) )) {
					return outputDir;

				}

				recreateDirectory( outputDir );

				Map<String, String> options = new HashMap<>();
				options.put( "outputdir", outputDir.toString() );

				if (effectiveSourceClasspath != null && ! effectiveSourceClasspath.isEmpty()) {
					options.put( "extraclasspath", String.join( File.pathSeparator, effectiveSourceClasspath ) );

				}

				new CfrDriver.Builder()
					.withOptions( options )
					.build()
					.analyse( List.of( jar.toString() ) );

				Files.writeString( completeMarker, expectedMarker, StandardCharsets.UTF_8 );
				return outputDir;

			} catch (IOException e) {
				throw new RuntimeException( "Failed to prepare decompile output directory for jar: " + jarPath, e );

			} catch (Exception e) {
				throw new RuntimeException( "Failed to decompile jar: " + jarPath, e );

			}

		}

	}

	private static void recreateDirectory(
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

	private static String stripExtension(
		String fileName
	) {

		int idx = fileName.lastIndexOf( '.' );
		return idx >= 0 ? fileName.substring( 0, idx ) : fileName;

	}

}
