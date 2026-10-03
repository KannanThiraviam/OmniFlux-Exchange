package com.omniflux.exchange.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where {@link FsWatcher#writableMounts(MountSource)} looks for "every
 * writable mount." Not {@code FileStore}: it exposes {@code isReadOnly()}
 * but no reliable mount PATH, only a {@code FileStore} object, and
 * {@code Path.of(store.toString())} is not a contract.
 */
public interface MountSource {

    record Mount(Path path, boolean writable) { }

    List<Mount> mounts();

    static Mount mount(String path, boolean writable) {
        return new Mount(Path.of(path), writable);
    }

    /** The test seam. */
    static MountSource of(Mount... mounts) {
        List<Mount> fixed = List.of(mounts);
        return () -> fixed;
    }

    /**
     * Parses {@code /proc/self/mounts}. A mount is writable when its option
     * list lacks {@code ro}.
     */
    static MountSource linux() throws IOException {
        List<Mount> parsed = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("/proc/self/mounts"))) {
            String[] fields = line.split("\\s+");
            if (fields.length < 4) continue;
            String mountPoint = fields[1];
            List<String> options = Arrays.asList(fields[3].split(","));
            boolean writable = !options.contains("ro");
            parsed.add(new Mount(Path.of(mountPoint), writable));
        }
        List<Mount> fixed = List.copyOf(parsed);
        return () -> fixed;
    }
}
