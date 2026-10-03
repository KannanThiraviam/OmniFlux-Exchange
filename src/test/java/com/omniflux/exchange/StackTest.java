package com.omniflux.exchange;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StackTest extends PostgresTestBase {

    @Autowired
    ApplicationContext ctx;

    @Test
    void runsOnTheReactiveStack() {
        assertTrue(ctx.getClass().getName().contains("Reactive"),
                "expected a reactive context, got " + ctx.getClass().getName());
    }

    @Test
    void servletStarterIsAbsent() {
        // With both starters present Boot configures MVC and this service silently
        // stops being reactive. Usually arrives transitively.
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.web.servlet.DispatcherServlet"));
    }

    @Test
    void springFrameworkIsSevenAndReactorMatchesTheBom() {
        // The bridge spike was verified on exactly these. A silent downgrade would
        // invalidate the evidence without any test noticing.
        assertTrue(java.util.Objects.requireNonNull(org.springframework.core.SpringVersion
                .getVersion(), "SpringVersion").startsWith("7."));
        assertTrue(java.util.Objects.requireNonNull(reactor.core.Scannable.class.getPackage()
                .getImplementationVersion(), "reactor version").startsWith("3.8."));
    }
}
