package com.omniflux.exchange.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NaiveExportRunnerTest {
    @Test
    void naiveEstimatorRejectsInvalidSizesAndDetectsOverflow() {
        assertEquals(2_200L, NaiveExportRunner.estimatedRetainedBytes(2, 1_100));
        assertThrows(IllegalArgumentException.class, () -> NaiveExportRunner.estimatedRetainedBytes(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> NaiveExportRunner.estimatedRetainedBytes(1, -1));
        assertThrows(ArithmeticException.class,
                () -> NaiveExportRunner.estimatedRetainedBytes(Long.MAX_VALUE, 2));
    }
}
