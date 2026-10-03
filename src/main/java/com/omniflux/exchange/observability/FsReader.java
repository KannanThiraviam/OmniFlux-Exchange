package com.omniflux.exchange.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every filesystem read {@link ResourceProbe} performs goes through this.
 * Production is {@link #real()} = {@code Files::readString}. Tests count
 * calls, throw {@code NoSuchFileException} for cgroup paths, or block
 * forever — none of that is reachable from production code.
 */
public interface FsReader {

    String read(Path path) throws IOException;

    static FsReader real() {
        return Files::readString;
    }
}
