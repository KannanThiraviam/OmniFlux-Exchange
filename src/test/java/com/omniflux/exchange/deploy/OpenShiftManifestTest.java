package com.omniflux.exchange.deploy;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 24. These manifests boot in HEADER mode behind the restricted gateway.
 *
 * <p>v1 has no JWT provider, so {@code StartupValidator} refuses
 * {@code auth-mode=JWT} at boot. The two implemented modes (DISABLED, HEADER)
 * requires the explicit non-JWT flag, which the ConfigMap sets alongside the
 * gateway-only NetworkPolicy. The deployment annotation records that the
 * manifest is runnable under that operational boundary.
 */
class OpenShiftManifestTest {

    private static final Path DEPLOY_DIR = Path.of("deploy", "openshift");
    private static final Path APPLICATION_YML =
            Path.of("src", "main", "resources", "application.yml");

    private static final DeploymentDoc D =
            DeploymentDoc.load(DEPLOY_DIR.resolve("deployment.yaml"));

    @Test
    void theContainerLimitMatchesTheValidatorsArithmetic() {
        // 416 MiB needed (max-heap-budget 320 + non-heap-reserve 96). A limit
        // below it means StartupValidator refuses to boot -- in production, on
        // every pod, after the image is already rolling.
        assertEquals("512Mi", D.limit("memory"));
        assertTrue(D.env().contains("-Xmx320m"));
        assertTrue(D.env().contains("-XX:MaxDirectMemorySize=64m"));
        assertTrue(D.env().contains("-XX:+ExitOnOutOfMemoryError"));
    }

    @Test
    void requestsEqualLimitsSoTheQosClassIsGuaranteed() {
        // Burstable QoS makes this pod an eviction candidate under node
        // pressure -- which is the exact failure the whole design exists to
        // avoid, arriving by a different door.
        assertEquals(D.limit("memory"), D.request());
    }

    @Test
    void theRootFilesystemIsReadOnlyWithATinyTmpfs() {
        // The zero-disk guarantee, enforced rather than measured. 16Mi on
        // purpose: a spooling library fails loudly instead of quietly
        // consuming ephemeral storage until the kubelet EVICTS the pod.
        assertTrue(D.securityContext().readOnlyRootFilesystem());
        assertEquals("16Mi", D.emptyDir().sizeLimit());
        assertEquals("Memory", D.emptyDir().medium()); // tmpfs, not node disk
    }

    @Test
    void ephemeralStorageIsLimitedToo() {
        // Without this a write outside the tmpfs consumes node disk silently.
        assertEquals("64Mi", D.limit("ephemeral-storage"));
    }

    @Test
    void theReplicaCountAndRollingStrategyMatchTheSpec() {
        assertEquals(3, D.replicas());
        assertEquals("RollingUpdate", D.strategy().type());
        assertEquals(1, D.strategy().maxUnavailable());
        assertEquals(1, D.strategy().maxSurge());
    }

    @Test
    void readinessFAILSFastAndLivenessDoesNOT() {
        // REGRESSION-shaped. A liveness probe that trips while a pod is
        // merely BUSY restarts a healthy worker mid-export, orphaning a
        // multipart upload every time it fires. Liveness is generous;
        // readiness is strict.
        assertEquals("/actuator/health/liveness", D.livenessProbe().path());
        assertEquals("/actuator/health/readiness", D.readinessProbe().path());
        long leaseDurationSeconds = leaseDurationSecondsFromApplicationYml();
        assertTrue(
                (long) D.livenessProbe().failureThreshold() * D.livenessProbe().periodSeconds()
                        > leaseDurationSeconds,
                "liveness must outlast a lease (" + leaseDurationSeconds
                        + "s), or a busy pod restarts itself");
    }

    @Test
    void terminationGracePeriodOutlastsAnInFlightPartUpload() {
        assertTrue(D.terminationGracePeriodSeconds() >= 60);
    }

    @Test
    @Disabled("image-level SIGTERM behavior needs a built app image; configuration and fenced requeue are tested separately")
    void theAPPLICATIONActuallyHandlesSIGTERMGracefully() {
        // REGRESSION. A 60-second grace period is a manifest field. If the
        // application ignores SIGTERM it is force-killed at second 60
        // regardless, and every rolling deploy leaves an orphaned multipart
        // upload -- the grace period would have bought nothing but a slower
        // deploy.
        //
        // This is a RUNTIME test, not a YAML one: it must start a container
        // mid-export, send SIGTERM, and assert that it drains (aborts the
        // multipart upload, requeues the job, exits 0) rather than being
        // force-killed. This process-level test is disabled until Maven builds
        // an app image before the test phase. JobWorker drain and fenced
        // requeue behavior are covered by focused tests, and Boot shutdown
        // settings are asserted in JobRepositoryCancellationRaceTest.
        fail("disabled: app image is not built before the test phase");
    }

    @Test
    void noSecretIsInlinedInTheManifest() throws IOException {
        String text = Files.readString(DEPLOY_DIR.resolve("deployment.yaml"));
        for (String s : List.of("minioadmin", "password", "secret-key", "client-secret")) {
            assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(s), "found literal: " + s);
        }
        assertTrue(D.envFrom().stream().anyMatch(EnvFromRef::hasSecretRef));
    }

    @Test
    void theManifestsEnableHeaderModeBehindTheGateway() throws IOException {
        String deploymentText = Files.readString(DEPLOY_DIR.resolve("deployment.yaml"));
        assertFalse(deploymentText.contains("WILL NOT BOOT"));
        assertEquals("true", D.annotation());
        String config = Files.readString(DEPLOY_DIR.resolve("configmap.yaml"));
        assertTrue(config.contains("OMNIFLUX_SECURITY_ALLOW_NON_JWT_AUTH: \"true\""));
        assertTrue(config.contains("OMNIFLUX_SECURITY_AUTH_MODE: \"HEADER\""));
    }

    @Test
    void productionProfileLoadsTheMatchingProductionConfiguration() throws IOException {
        String config = Files.readString(DEPLOY_DIR.resolve("configmap.yaml"));
        assertTrue(config.contains("SPRING_PROFILES_ACTIVE: \"production\""));
        String production = Files.readString(Path.of("src", "main", "resources",
                "application-production.yml"));
        assertTrue(production.contains("forward-headers-strategy: framework"));
        assertTrue(production.contains("console: ecs"));
    }

    @Test
    void thereIsNoRouteUntilTheGatewayDecisionIsMade() {
        // Reference: the Data API is the only exposed entry point. A Route
        // publishes this service directly -- and in HEADER mode, directly
        // means header-forgeable by anyone who can reach the hostname.
        // REGRESSION. "No route.yaml, and some networkpolicy.yaml exists"
        // passes a NodePort or LoadBalancer Service -- which publishes the
        // pod just as effectively -- and passes an empty policy that selects
        // nothing.
        assertFalse(Files.exists(DEPLOY_DIR.resolve("route.yaml")));

        Yml service = Yml.load(DEPLOY_DIR.resolve("service.yaml"));
        assertEquals("ClusterIP", service.path("spec.type").asString());

        Yml np = Yml.load(DEPLOY_DIR.resolve("networkpolicy.yaml"));
        assertEquals(List.of("Ingress"), np.path("spec.policyTypes").asStringList());
        assertEquals(
                Map.of("app", "omniflux-exchange"),
                np.path("spec.podSelector.matchLabels").asStringMap());

        Yml ingress0 = np.path("spec.ingress").at();
        Yml from0 = ingress0.get("from").at();
        assertEquals(
                Map.of("kubernetes.io/metadata.name", "api-gateway"),
                from0.path("namespaceSelector.matchLabels").asStringMap());

        List<Integer> ports = new ArrayList<>();
        for (Object o : ingress0.get("ports").asList()) {
            ports.add(new Yml(o).get("port").asInt());
        }
        assertEquals(List.of(8080), ports);
    }

    @Test
    void qosGuaranteedRequiresCPUEqualityToo() {
        // Guaranteed QoS needs equal, NON-ZERO requests and limits for BOTH
        // cpu and memory, on every container INCLUDING init containers.
        // Memory alone yields Burstable -- and a Burstable pod is an
        // eviction candidate, which is the failure this whole design exists
        // to avoid, by another door.
        assertFalse(D.allContainers().isEmpty(), "expected at least one container");
        for (ContainerDoc c : D.allContainers()) {
            assertEquals(c.limit("cpu"), c.request("cpu"), c.name() + " cpu");
            assertEquals(c.limit("memory"), c.request("memory"), c.name() + " memory");
            assertNotEquals("0", c.request("cpu"), c.name() + " cpu request must be non-zero");
        }
    }

    @Test
    void thePodIsHardenedToTheClusterBaseline() {
        assertTrue(D.securityContext().runAsNonRoot());
        assertFalse(D.securityContext().allowPrivilegeEscalation());
        assertEquals(List.of("ALL"), D.securityContext().capabilitiesDrop());
        assertEquals("RuntimeDefault", D.securityContext().seccompProfileType());
        assertFalse(D.automountServiceAccountToken(), "this service calls no Kubernetes API");
    }

    @Test
    void noSecretAppearsInANYManifestFile() throws IOException {
        // REGRESSION. Scanning deployment.yaml for four substrings misses a
        // literal in the ConfigMap or a filled-in value in
        // secret.example.yaml -- and the example file is the one most likely
        // to acquire a real value.
        for (Path f : listManifestFiles()) {
            List<String> findings = SecretScanner.findings(f);
            assertTrue(findings.isEmpty(), f + ": " + findings);
        }
    }

    @Test
    void aPodDisruptionBudgetKeepsTwoPodsDuringNodeDrain() {
        Yml pdb = Yml.load(DEPLOY_DIR.resolve("pdb.yaml"));
        assertEquals("PodDisruptionBudget", pdb.get("kind").asString());
        assertEquals(2, pdb.path("spec.minAvailable").asInt());
        assertEquals(
                Map.of("app", "omniflux-exchange"),
                pdb.path("spec.selector.matchLabels").asStringMap());
    }

    // ---------------------------------------------------------------- helpers

    private static List<Path> listManifestFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DEPLOY_DIR, "*.yaml")) {
            for (Path p : ds) {
                files.add(p);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    /**
     * {@code OmnifluxProperties} (Task 3) does not exist yet at the time this
     * task runs, so the lease duration this test discriminates against is
     * read directly out of the shipped {@code application.yml} rather than
     * through the properties object the plan's pseudocode assumes.
     */
    private static long leaseDurationSecondsFromApplicationYml() {
        Yml app = Yml.load(APPLICATION_YML);
        String raw = app.path("omniflux.queue.lease-duration").asString();
        if (raw == null) {
            throw new AssertionError("omniflux.queue.lease-duration missing from application.yml");
        }
        return parseSimpleDurationSeconds(raw);
    }

    private static long parseSimpleDurationSeconds(String raw) {
        String s = raw.trim();
        String digits = s.substring(0, s.length() - 1);
        if (s.endsWith("s")) {
            return Long.parseLong(digits);
        }
        if (s.endsWith("m")) {
            return Long.parseLong(digits) * 60;
        }
        throw new AssertionError("unsupported duration literal: " + raw);
    }

    // ------------------------------------------------------------ YAML model

    /** Thin fluent navigator over a parsed YAML document (SnakeYaml's Map/List/scalar tree). */
    private record Yml(Object value) {

        static Yml load(Path file) {
            try (InputStream in = Files.newInputStream(file)) {
                return new Yml(new Yaml().load(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @SuppressWarnings("unchecked")
        Yml get(String key) {
            if (value == null) {
                return new Yml(null);
            }
            return new Yml(((Map<String, Object>) value).get(key));
        }

        Yml path(String dotted) {
            Yml cur = this;
            for (String part : dotted.split("\\.")) {
                cur = cur.get(part);
            }
            return cur;
        }

        @SuppressWarnings("unchecked")
        Yml at() {
            if (value == null) {
                return new Yml(null);
            }
            return new Yml(((List<?>) value).getFirst());
        }

        boolean isNull() {
            return value == null;
        }

        String asString() {
            return value == null ? null : String.valueOf(value);
        }

        int asInt() {
            if (value instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(String.valueOf(value));
        }

        boolean asBoolean() {
            return value != null && (Boolean) value;
        }

        @SuppressWarnings("unchecked")
        List<Object> asList() {
            return value == null ? List.of() : (List<Object>) value;
        }

        List<String> asStringList() {
            List<String> out = new ArrayList<>();
            for (Object o : asList()) {
                out.add(String.valueOf(o));
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> asMap() {
            return value == null ? Map.of() : (Map<String, Object>) value;
        }

        Map<String, String> asStringMap() {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            asMap().forEach((k, v) -> out.put(k, String.valueOf(v)));
            return out;
        }
    }

    private record DeploymentDoc(Yml root) {

        static DeploymentDoc load(Path file) {
            return new DeploymentDoc(Yml.load(file));
        }

        private Yml podSpec() {
            return root.path("spec.template.spec");
        }

        private Yml mainContainerYml() {
            return podSpec().get("containers").at();
        }

        private ContainerDoc mainContainer() {
            return new ContainerDoc(mainContainerYml());
        }

        String limit(String resource) {
            return mainContainer().limit(resource);
        }

        String request() {
            return mainContainer().request("memory");
        }

        String env() {
            for (Object o : mainContainerYml().get("env").asList()) {
                Yml e = new Yml(o);
                if ("JAVA_TOOL_OPTIONS".equals(e.get("name").asString())) {
                    return e.get("value").asString();
                }
            }
            throw new AssertionError("no env var named " + "JAVA_TOOL_OPTIONS");
        }

        List<EnvFromRef> envFrom() {
            List<EnvFromRef> out = new ArrayList<>();
            for (Object o : mainContainerYml().get("envFrom").asList()) {
                out.add(new EnvFromRef(new Yml(o)));
            }
            return out;
        }

        SecurityContextDoc securityContext() {
            return new SecurityContextDoc(
                    podSpec().get("securityContext"), mainContainerYml().get("securityContext"));
        }

        EmptyDirDoc emptyDir() {
            for (Object o : podSpec().get("volumes").asList()) {
                Yml v = new Yml(o);
                if ("tmp".equals(v.get("name").asString())) {
                    return new EmptyDirDoc(v.get("emptyDir"));
                }
            }
            throw new AssertionError("no volume named " + "tmp");
        }

        int replicas() {
            return root.path("spec.replicas").asInt();
        }

        StrategyDoc strategy() {
            return new StrategyDoc(root.path("spec.strategy"));
        }

        ProbeDoc livenessProbe() {
            return new ProbeDoc(mainContainerYml().get("livenessProbe"));
        }

        ProbeDoc readinessProbe() {
            return new ProbeDoc(mainContainerYml().get("readinessProbe"));
        }

        int terminationGracePeriodSeconds() {
            return podSpec().get("terminationGracePeriodSeconds").asInt();
        }

        String annotation() {
            return root.path("metadata.annotations").get("omniflux.io/runnable").asString();
        }

        boolean automountServiceAccountToken() {
            return podSpec().get("automountServiceAccountToken").asBoolean();
        }

        List<ContainerDoc> allContainers() {
            List<ContainerDoc> out = new ArrayList<>();
            for (Object o : podSpec().get("initContainers").asList()) {
                out.add(new ContainerDoc(new Yml(o)));
            }
            for (Object o : podSpec().get("containers").asList()) {
                out.add(new ContainerDoc(new Yml(o)));
            }
            return out;
        }
    }

    private record ContainerDoc(Yml y) {
        String name() {
            return y.get("name").asString();
        }

        String limit(String resource) {
            return y.path("resources.limits").get(resource).asString();
        }

        String request(String resource) {
            return y.path("resources.requests").get(resource).asString();
        }
    }

    private record SecurityContextDoc(Yml pod, Yml container) {
        private Yml effective(String key) {
            Yml c = container.get(key);
            return c.isNull() ? pod.get(key) : c;
        }

        boolean runAsNonRoot() {
            return effective("runAsNonRoot").asBoolean();
        }

        boolean readOnlyRootFilesystem() {
            return effective("readOnlyRootFilesystem").asBoolean();
        }

        boolean allowPrivilegeEscalation() {
            return effective("allowPrivilegeEscalation").asBoolean();
        }

        List<String> capabilitiesDrop() {
            return effective("capabilities").get("drop").asStringList();
        }

        String seccompProfileType() {
            return effective("seccompProfile").get("type").asString();
        }
    }

    private record EmptyDirDoc(Yml y) {
        String sizeLimit() {
            return y.get("sizeLimit").asString();
        }

        String medium() {
            return y.get("medium").asString();
        }
    }

    private record StrategyDoc(Yml y) {
        String type() {
            return y.get("type").asString();
        }

        int maxUnavailable() {
            return y.path("rollingUpdate.maxUnavailable").asInt();
        }

        int maxSurge() {
            return y.path("rollingUpdate.maxSurge").asInt();
        }
    }

    private record ProbeDoc(Yml y) {
        String path() {
            return y.path("httpGet.path").asString();
        }

        int periodSeconds() {
            return y.get("periodSeconds").asInt();
        }

        int failureThreshold() {
            return y.get("failureThreshold").asInt();
        }
    }

    private record EnvFromRef(Yml y) {
        boolean hasSecretRef() {
            return !y.get("secretRef").isNull();
        }
    }

    // ------------------------------------------------------------- scanning

    /**
     * Flags a YAML {@code key: value} line only when BOTH the key name looks
     * credential-shaped (password, secret-key, client-secret, access-key,
     * token, credential -- hyphen/underscore-insensitive) AND the value is a
     * real, non-placeholder literal. A bare key (no value on the line, e.g.
     * inside a block whose value is nested) or an empty-string placeholder
     * (as {@code secret.example.yaml} uses throughout) is not a finding --
     * that is the "keys only, no values" contract the example file is meant
     * to satisfy.
     */
    private static final class SecretScanner {

        private static final Pattern SENSITIVE_KEY_LINE =
                Pattern.compile(
                        "(?i)^\\s*-?\\s*[\"']?[A-Za-z0-9_.-]*"
                                + "(password|secret[-_]?key|client[-_]?secret|access[-_]?key|token|credential)"
                                + "[\"']?\\s*:\\s*(.+)$");

        static List<String> findings(Path file) {
            List<String> findings = new ArrayList<>();
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.strip();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    continue;
                }
                Matcher m = SENSITIVE_KEY_LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                String value = m.group(2).strip();
                int hash = value.indexOf(" #");
                if (hash >= 0) {
                    value = value.substring(0, hash).strip();
                }
                if (isPlaceholder(value) || isNonCredentialLiteral(value)) {
                    continue;
                }
                findings.add(file.getFileName() + ":" + (i + 1) + ": " + trimmed);
            }
            return findings;
        }

        private static boolean isPlaceholder(String v) {
            return v.isEmpty()
                    || v.equals("\"\"")
                    || v.equals("''")
                    || v.equalsIgnoreCase("null")
                    || v.equals("~");
        }

        // A key that merely CONTAINS a sensitive word (e.g. the Kubernetes
        // field automountServiceAccountToken) is not itself a credential
        // unless its value could plausibly BE one. A boolean or bare number
        // never is -- this is what keeps automountServiceAccountToken: false
        // from being flagged.
        private static final Pattern BOOLEAN_OR_NUMBER = Pattern.compile("(?i)true|false|-?\\d+(\\.\\d+)?");

        private static boolean isNonCredentialLiteral(String v) {
            return BOOLEAN_OR_NUMBER.matcher(v).matches();
        }
    }
}
