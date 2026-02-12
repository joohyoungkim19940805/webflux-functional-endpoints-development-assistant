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
 * src/main/java 를 감시하면서 @Controller/@MessageMapping 기반 RSocket 엔드포인트를 파싱하여
 * rsoket.json(기본: src/main/resources/static/rsoket.json)로 저장.
 */
public class RsoketJsonFileWatcher extends AbstractWatcher {

	public static final class Config {

		private final String watchDirectory;
		private final String rsoketOutputFile;

		private Config(Builder b) {
			this.watchDirectory = b.watchDirectory.replace('\\', '/').replace('.', '/');

			int lastDotIndex = b.rsoketOutputFile.lastIndexOf('.');
			if (lastDotIndex == -1) {
				this.rsoketOutputFile = b.rsoketOutputFile.replace('\\', '/') + ".json";
			} else {
				this.rsoketOutputFile =
					b.rsoketOutputFile.substring(0, lastDotIndex).replace('\\', '/').replace('.', '/')
						+ b.rsoketOutputFile.substring(lastDotIndex);
			}
		}

		public String watchDirectory() { return watchDirectory; }
		public String rsoketOutputFile() { return rsoketOutputFile; }

		public static Builder builder() { return new Builder(); }

		public static final class Builder {
			private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;
			private String rsoketOutputFile = "src/main/resources/static/rsoket.json";

			public Builder watchDirectory(String p) { this.watchDirectory = p; return this; }
			public Builder rsoketOutputFile(String p) { this.rsoketOutputFile = p; return this; }

			public Config build() { return new Config(this); }
		}
	}

	private final Config config;

	public RsoketJsonFileWatcher(Config config) {
		this.config = config;
	}

	public Mono<Boolean> runGenerateTask() {
		return Mono.fromCallable(() -> {
			String json = generateRsoketJson();
			Path out = Paths.get(config.rsoketOutputFile());
			return writeIfChanged(out, json.getBytes(StandardCharsets.UTF_8));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	protected Path root() { return Paths.get(config.watchDirectory()); }

	public void startWatching() {
		try {
			super.start();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private String generateRsoketJson() {
		try {
			RsoketParser parser = new RsoketParser();
			List<RsoketRouteInfo> routes = parser.extractRsoketRoutes(config.watchDirectory());
			routes.sort(
				Comparator.comparing(RsoketRouteInfo::getDestination)
					.thenComparing(RsoketRouteInfo::getController)
					.thenComparing(RsoketRouteInfo::getMethod)
			);
			return RsoketGenerator.generateRsoketJson(routes);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "";
	}
}
