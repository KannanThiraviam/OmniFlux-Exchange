package com.omniflux.exchange.job;

import com.omniflux.exchange.security.PrincipalKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferJobTest {
    private static final PrincipalKey OWNER = new PrincipalKey("issuer", "subject", "tenant");

    @Test
    void builderKeepsOwnershipSeparateFromAuthorizationSnapshot() {
        TransferJob job = TransferJob.builder()
                .id(UUID.randomUUID())
                .owner(OWNER)
                .roles(List.of("ANALYST"))
                .authzContextVersion("v1")
                .relationName("mock_orders")
                .format("CSV")
                .createdAt(Instant.EPOCH)
                .build();

        assertEquals(OWNER, job.owner());
        assertEquals("subject", job.requestedBy());
        assertEquals(List.of("ANALYST"), job.roles());
        assertEquals("v1", job.authzContextVersion());
        assertEquals(JobStatus.QUEUED, job.status());
        assertFalse(job.terminal());
    }

    @Test
    void attemptKeyIsScopedByClaimEvenWhenShutdownReusesTheAttemptNumber() {
        UUID id = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        TransferJob job = TransferJob.builder().id(id).owner(OWNER)
                .relationName("mock_orders").format("CSV").attemptCount(2).claimToken(claim).build();

        assertEquals("exports/" + id + "/a2/c" + claim + "/data.csv",
                job.attemptObjectKey("exports/", ".csv"));
        assertNotEquals(job.attemptObjectKey("exports/", ".csv"),
                job.toBuilder().claimToken(UUID.randomUUID()).build().attemptObjectKey("exports/", ".csv"));
        assertThrows(NullPointerException.class,
                () -> job.toBuilder().claimToken(null).build().attemptObjectKey("exports/", ".csv"));
    }

    @Test
    void lifecycleHelpersRecognizeTerminalStates() {
        assertTrue(JobStatus.COMPLETED.terminal());
        assertTrue(JobStatus.FAILED.terminal());
        assertTrue(JobStatus.CANCELLED.terminal());
        assertFalse(JobStatus.QUEUED.terminal());
        assertTrue(JobStatus.IN_PROGRESS.workerOwned());
    }
}
