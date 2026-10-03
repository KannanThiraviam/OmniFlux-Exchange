package com.omniflux.exchange.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class NaiveExportControllerTest {
    private final NaiveExportController controller = new NaiveExportController();

    @Test
    void beanIsAbsentWithoutTheDemoProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(NaiveExportController.class)
                .run(ctx -> assertFalse(ctx.containsBean("naiveExportController"),
                        "the naive endpoint must not register without the demo profile"));
    }

    @Test
    void beanIsPresentUnderTheDemoProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(NaiveExportController.class)
                .withPropertyValues("spring.profiles.active=demo")
                .run(ctx -> assertTrue(ctx.containsBean("naiveExportController"),
                        "the demo profile must register the naive endpoint"));
    }

    @Test
    void countsRowsAndReportsTheReferenceEstimate() {
        var response = controller.run(new NaiveExportController.NaiveRequest(100)).block();
        assertEquals(100, response.rows());
        assertEquals(110_000L, response.estimatedRetainedBytes());
    }

    @Test
    void negativeRequestsClampToZeroRows() {
        var response = controller.run(new NaiveExportController.NaiveRequest(-5)).block();
        assertEquals(0, response.rows());
        assertEquals(0L, response.estimatedRetainedBytes());
    }
}
