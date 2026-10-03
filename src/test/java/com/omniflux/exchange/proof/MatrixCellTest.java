package com.omniflux.exchange.proof;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatrixCellTest {
    @Test
    void matrixCellsAndRunsValidateTheirExperimentalInputs() {
        assertThrows(IllegalArgumentException.class, () -> new MatrixCell("", 1, "CSV", "rest", "narrow", 1));
        assertThrows(IllegalArgumentException.class, () -> new MatrixCell("x", -1, "CSV", "rest", "narrow", 1));
        assertThrows(IllegalArgumentException.class, () -> new MatrixCell("x", 1, "CSV", "rest", "narrow", 0));
        assertThrows(IllegalArgumentException.class, () -> new ScalingRun(List.of(), 0));
        assertEquals(2, ScalingRun.smoke().cells().size());
    }

    @Test
    void theXlsxLimitCellIsTheExpectedFailureBoundaryOnTheRestAdapter() {
        var restBoundary = MatrixCell.fullMatrix().stream()
                .filter(cell -> cell.id().equals("S9-xlsx-limit-rest"))
                .findFirst().orElseThrow();
        assertEquals("rest", restBoundary.adapter());
        assertTrue(restBoundary.expectedFailure());
    }
}
