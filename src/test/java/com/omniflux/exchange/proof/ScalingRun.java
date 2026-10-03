package com.omniflux.exchange.proof;

import java.util.List;

/** Small immutable matrix definition shared by the command-line proof tools. */
public record ScalingRun(List<MatrixCell> cells, int repetitions) {
    public ScalingRun {
        cells = List.copyOf(cells == null ? List.of() : cells);
        if (repetitions < 1) throw new IllegalArgumentException("repetitions must be positive");
    }

    public static ScalingRun smoke() {
        return new ScalingRun(List.of(
                new MatrixCell("S1-10k", 10_000, "CSV", "r2dbc", "narrow", 1),
                new MatrixCell("S2-10k", 10_000, "CSV", "rest", "narrow", 1)), 1);
    }
}
