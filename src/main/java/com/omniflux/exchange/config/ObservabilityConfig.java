package com.omniflux.exchange.config;

import com.omniflux.exchange.observability.FsReader;
import com.omniflux.exchange.observability.FsWatcher;
import com.omniflux.exchange.observability.MountSource;
import com.omniflux.exchange.observability.ResourceProbe;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Wires Task 1's {@link FsWatcher} and {@link ResourceProbe} as beans from
 * {@code omniflux.observability.*}. The probes themselves (Task 1) take plain
 * arguments and know nothing about Boot configuration — this class is the only
 * place that translates {@code omniflux.observability.watch-paths: AUTO} (or an
 * explicit list) into the {@link MountSource} that {@link FsWatcher#writableMounts}
 * consumes.
 *
 * <p>{@code watch-paths: AUTO} means {@link MountSource#linux()}, which parses
 * {@code /proc/self/mounts}. That file does not exist on the Windows
 * development machine this was built on, so AUTO falls back to an empty
 * {@link MountSource} there rather than failing the whole application context.
 * {@link ResourceProbe} has the identical off-Linux fallback for cgroup reads,
 * for the same reason (see its Javadoc). The real container the guarantee is
 * about is Linux, where {@code /proc/self/mounts} is always present.
 */
@Configuration
public class ObservabilityConfig {

    /** Not config-bound: the sampling cadence is an implementation detail of
     *  the probe, not part of the authoritative schema in spec §12. */
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(5);

    @Bean
    public ResourceProbe resourceProbe() {
        var probe = new ResourceProbe(FsReader.real(), SAMPLE_INTERVAL);
        probe.startSampling();
        return probe;
    }

    @Bean
    public MountSource mountSource(OmnifluxProperties props) {
        List<String> watchPaths = props.observability().watchPaths();
        if (watchPaths != null && !isAuto(watchPaths)) {
            return MountSource.of(watchPaths.stream()
                    .map(path -> MountSource.mount(path, true))
                    .toArray(MountSource.Mount[]::new));
        }
        try {
            return MountSource.linux();
        } catch (IOException _) {
            // Off-Linux development fallback — see the class Javadoc.
            return MountSource.of();
        }
    }

    @Bean
    public FsWatcher fsWatcher(@Qualifier("mountSource") MountSource mountSource) throws IOException {
        return FsWatcher.watching(FsWatcher.writableMounts(mountSource));
    }

    private static boolean isAuto(List<String> watchPaths) {
        return watchPaths.size() == 1 && "AUTO".equalsIgnoreCase(watchPaths.getFirst().trim())
                || watchPaths.stream().anyMatch(s -> s.toUpperCase(Locale.ROOT).equals("AUTO"));
    }
}
