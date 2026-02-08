package com.starbearing.watch;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import reactor.core.publisher.Flux;

public final class ClasspathWatcher extends AbstractWatcher {

    private final Path root;
    private final Path triggerPath;

    public ClasspathWatcher(Path root, Path triggerPath) {
        this.root = root.toAbsolutePath().normalize();
        this.triggerPath = triggerPath == null ? null : triggerPath.toAbsolutePath().normalize();
    }

    @Override
    protected Path root() {
        return root;
    }

    @Override
    protected WatchEvent.Kind<?>[] kinds() {
        return new WatchEvent.Kind<?>[] {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        };
    }

    @Override
    public Flux<FileChange> events() {
        return super.events()
            .filter(fc -> isRelevant(fc.path()));
    }

    private boolean isRelevant(Path p) {
        if (p == null) return false;

        Path n = p.toAbsolutePath().normalize();
        if (triggerPath != null && triggerPath.equals(n)) return false; // self-trigger loop 방지

        String s = n.toString().replace('\\', '/');

        // 임시 파일류 제외
        if (s.endsWith(".tmp") || s.endsWith("~")) return false;

        if (s.endsWith(".class")) return true;

        if (s.endsWith(".properties") || s.endsWith(".yml") || s.endsWith(".yaml")) return true;

        if (s.endsWith(".jar")) return true;

        return false;
    }
}
