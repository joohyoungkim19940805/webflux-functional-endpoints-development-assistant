package com.byeolnaerim.watch.document.prp;

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

public final class PrpJsonFileWatcher extends AbstractSpoonDocumentWatcher {
    public static final class Config {
        private final String watchDirectory;
        private final String prpOutputFile;
        private final List<String> decompileJarPaths;
        private final List<String> sourceClasspath;
        private final List<Class<?>> decompileJarClasses;
        private Config(Builder builder) {
            this.watchDirectory = builder.watchDirectory.replace('\\', '/');
            this.prpOutputFile = normalizeJsonPath(builder.prpOutputFile);
            this.decompileJarPaths = List.copyOf(builder.decompileJarPaths);
            this.sourceClasspath = List.copyOf(builder.sourceClasspath);
            this.decompileJarClasses = List.copyOf(builder.decompileJarClasses);
        }
        public String watchDirectory() { return watchDirectory; }
        public String prpOutputFile() { return prpOutputFile; }
        public List<String> decompileJarPaths() { return decompileJarPaths; }
        public List<String> sourceClasspath() { return sourceClasspath; }
        public List<Class<?>> decompileJarClasses() { return decompileJarClasses; }
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;
            private String prpOutputFile = ProjectDefaults.PRP_OUTPUT_FILE;
            private final List<String> decompileJarPaths = new ArrayList<>();
            private final List<String> sourceClasspath = new ArrayList<>();
            private final List<Class<?>> decompileJarClasses = new ArrayList<>();
            public Builder watchDirectory(String value) { watchDirectory = value; return this; }
            public Builder prpOutputFile(String value) { prpOutputFile = value; return this; }
            public Builder addDecompileJar(String value) { decompileJarPaths.add(value); return this; }
            public Builder decompileJars(List<String> values) { decompileJarPaths.clear(); decompileJarPaths.addAll(values); return this; }
            public Builder addSourceClasspath(String value) { sourceClasspath.add(value); return this; }
            public Builder sourceClasspath(List<String> values) { sourceClasspath.clear(); sourceClasspath.addAll(values); return this; }
            public Builder addDecompileJarClass(Class<?> value) { decompileJarClasses.add(value); return this; }
            public Builder decompileJarClasses(List<Class<?>> values) { decompileJarClasses.clear(); decompileJarClasses.addAll(values); return this; }
            public Config build() { return new Config(this); }
        }
    }

    private final Config config;
    public PrpJsonFileWatcher(Config config) { this.config = config; }

    @Override public Mono<Boolean> runGenerateTask() {
        return Mono.fromCallable(() -> {
            try {
                String json = generatePrpJson();
                return writeIfChanged(Paths.get(config.prpOutputFile()), json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                error.printStackTrace();
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @Override protected Path root() { return Paths.get(config.watchDirectory()); }
    @Override public void startWatching() {
        try { super.start(); } catch (IOException error) { throw new RuntimeException(error); }
    }

    private String generatePrpJson() throws Exception {
        SpoonAnalysisContext analysis = analyzeSpoon(SpoonAnalysisRequest.of(
            config.watchDirectory(), config.sourceClasspath(), config.decompileJarPaths(), config.decompileJarClasses()
        ));
        PrpParser parser = new PrpParser(analysis.externalTypes());
        List<PrpRouteInfo> routes = parser.extractPrpRoutes(analysis.projectModel().getAllTypes());
        routes.sort(Comparator.comparing(PrpRouteInfo::getRoute)
            .thenComparing(PrpRouteInfo::getInteraction)
            .thenComparing(PrpRouteInfo::getController)
            .thenComparing(PrpRouteInfo::getMethod));
        return PrpJsonGenerator.generatePrpJson(routes, new PrpJsonGenerator.Options());
    }

    private static String normalizeJsonPath(String value) {
        String path = value.replace('\\', '/');
        return path.toLowerCase().endsWith(".json") ? path : path + ".json";
    }
}
