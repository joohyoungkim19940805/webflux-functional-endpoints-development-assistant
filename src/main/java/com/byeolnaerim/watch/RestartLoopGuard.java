package com.byeolnaerim.watch;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;


/**
 * Persistent circuit breaker for development-time automatic restart triggers.
 * <p>The history is stored outside the runtime classpath so the guard survives
 * Spring DevTools restarts without causing another restart event itself.</p>
 */
final class RestartLoopGuard {

	private final Path historyFile;

	private final int maxRestarts;

	private final long windowMillis;

	RestartLoopGuard(
					Path historyFile,
					int maxRestarts,
					long windowMillis
	) {

		this.historyFile = historyFile.toAbsolutePath().normalize();
		this.maxRestarts = Math.max( 1, maxRestarts );
		this.windowMillis = Math.max( 1_000L, windowMillis );

	}

	synchronized boolean tryAcquire() {

		long now = System.currentTimeMillis();
		long minimum = now - windowMillis;
		List<Long> recent = readHistory()
			.stream()
			.filter( value -> value >= minimum )
			.toList();

		if (recent.size() >= maxRestarts) {
			writeHistory( recent );
			return false;

		}

		List<Long> updated = new ArrayList<>( recent );
		updated.add( now );
		writeHistory( updated );
		return true;

	}

	private List<Long> readHistory() {

		if (! Files.isRegularFile( historyFile ))
			return List.of();

		try {
			List<Long> values = new ArrayList<>();

			for (String line : Files.readAllLines( historyFile, StandardCharsets.UTF_8 )) {
				try {
					values.add( Long.parseLong( line.trim() ) );

				} catch (NumberFormatException ignore) {}

			}

			return values;

		} catch (IOException ignore) {
			return List.of();

		}

	}

	private void writeHistory(
		List<Long> values
	) {

		try {
			if (historyFile.getParent() != null) {
				Files.createDirectories( historyFile.getParent() );

			}

			String content = values
				.stream()
				.map( String::valueOf )
				.collect( java.util.stream.Collectors.joining( System.lineSeparator() ) );

			Files.writeString(
				historyFile,
				content,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			);

		} catch (IOException ignore) {}

	}

}
