package com.omniflux.exchange.proof;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs one reproducible proof cell through the normal HTTP export path.
 *
 * <p>The verifier independently streams the downloaded object, checks the
 * complete object checksum stored by the worker, checks consecutive fixture
 * keys, and rejects a server configured with a different source adapter than
 * the cell declares. The runner also submits {@link MatrixCell#concurrency()}
 * jobs concurrently, so concurrency cells exercise the admission gate rather
 * than merely recording a label.</p>
 */
public final class StreamingRunner {
    private static final long MEMORY_ACCOUNTING_TOLERANCE_BYTES = 1L << 20;
    private static final Pattern ADAPTER = Pattern.compile(
            "\\\"omniflux\\.source\\.default-adapter\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private StreamingRunner() { }

    // A command-line harness: stdout IS its output channel (run-matrix.sh
    // consumes the emitted JSON), so printing to System.out is deliberate.
    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    public static void main(String[] args) {
        try {
            Result result = new Runner(Options.parse(args)).run();
            System.out.println(result.json());
            if (result.exitCode() != 0) System.exit(1);
        } catch (Exception error) {
            System.err.println("proof runner failed: " + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static final class Runner {
        private final Options options;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        private Runner(Options options) { this.options = options; }

        private Result run() throws Exception {
            long totalStarted = System.nanoTime();
            String before = get(options.baseUrl() + "/api/system/resources");
            String adapter = adapter(before);
            if (!options.cell().adapter().equals(adapter)) {
                throw new IOException("proof cell requires adapter " + options.cell().adapter()
                        + " but server reports " + adapter);
            }

            long seedMillis = 0;
            if (options.seed()) {
                long seedStarted = System.nanoTime();
                post(options.baseUrl() + "/api/data/seed", "{\"relation\":\""
                        + json(options.relation()) + "\",\"rows\":" + options.maxId() + "}");
                seedMillis = elapsedMillis(seedStarted);
            }

            String request = "{\"relation\":\"" + json(options.relation())
                    + "\",\"columns\":[],\"filters\":[{\"column\":\"id\",\"operator\":\"LTE\",\"value\":"
                    + options.maxId() + "}],\"format\":\"" + options.cell().format()
                    + "\",\"csvMode\":\"RAW\"}";
            ResourceSampler sampler = new ResourceSampler(options.baseUrl(), client);
            sampler.observe(before);
            sampler.start();
            List<SingleJob> jobs;
            try (var pool = Executors.newFixedThreadPool(options.cell().concurrency())) {
                List<Callable<SingleJob>> calls = new ArrayList<>();
                for (int index = 1; index <= options.cell().concurrency(); index++) {
                    int jobNumber = index;
                    calls.add(() -> runJob(request, jobNumber, totalStarted));
                }
                jobs = pool.invokeAll(calls).stream().map(this::getResult).toList();
            } finally {
                sampler.stop();
            }

            String after = get(options.baseUrl() + "/api/system/resources");
            sampler.observe(after);
            long events = counter(after, "create") + counter(after, "modify") + counter(after, "delete")
                    - counter(before, "create") - counter(before, "modify") - counter(before, "delete");
            boolean allPassed = jobs.stream().allMatch(job -> job.exitCode() == 0);
            boolean overlapObserved = sampler.overlapObserved(options.cell().concurrency());
            boolean memoryWithinLimit = sampler.memoryWithinLimit();
            boolean telemetryValid = sampler.telemetryValid();
            boolean globalCeilingObserved = sampler.globalCeilingObserved();
            boolean filesystemOverflowed = overflowed(after);
            boolean resourcesPassed = telemetryValid && overlapObserved && globalCeilingObserved
                    && memoryWithinLimit
                    && sampler.oomKills() == 0 && !filesystemOverflowed;
            int exit = allPassed && events == 0 && resourcesPassed ? 0 : 1;
            return Result.from(options, adapter, jobs, sampler, events, filesystemOverflowed,
                    overlapObserved, memoryWithinLimit, exit,
                    seedMillis, elapsedMillis(totalStarted));
        }

        // Bounded remote poll: a 500-ms interval against a 30-min deadline. There
        // is no server-side push channel for job status.
        @SuppressWarnings("BusyWait")
        private SingleJob runJob(String request, int jobNumber, long totalStarted) throws Exception {
            Instant submittedAt = Instant.now();
            long submitStarted = System.nanoTime();
            String submitted = post(options.baseUrl() + "/api/exports", request,
                    "Idempotency-Key", "proof-" + options.cell().id() + "-" + jobNumber + "-" + System.nanoTime());
            long submitMillis = elapsedMillis(submitStarted);
            String id = string(submitted, "id");
            if (id == null || id.isBlank()) throw new IOException("submit response has no job id");
            String job;
            long queueStarted = System.nanoTime();
            long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
            do {
                Thread.sleep(500);
                job = get(options.baseUrl() + "/api/jobs/" + id);
                String status = string(job, "status");
                if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                    boolean expected = options.cell().expectedFailure()
                            && "XLSX_ROW_LIMIT".equals(string(job, "errorCode"));
                    return SingleJob.failed(id, status, string(job, "errorCode"), expected ? 0 : 1,
                            submitMillis, elapsedMillis(queueStarted), elapsedMillis(totalStarted));
                }
                if (System.nanoTime() > deadline) throw new IOException("job timed out: " + id);
            } while (!"COMPLETED".equals(string(job, "status")));

            String attempts = get(options.baseUrl() + "/api/jobs/" + id + "/attempts");
            String startedAt = string(attempts, "startedAt");
            long queueMillis = startedAt == null ? elapsedMillis(queueStarted)
                    : Math.max(0L, Duration.between(submittedAt, Instant.parse(startedAt)).toMillis());
            String timing = get(options.baseUrl() + "/api/jobs/" + id + "/timing");
            String storedSha256 = string(job, "contentSha256");
            long presignStarted = System.nanoTime();
            String download = get(options.baseUrl() + "/api/jobs/" + id + "/download");
            long presignMillis = elapsedMillis(presignStarted);
            long downloadVerifyStarted = System.nanoTime();
            Verification verification = verify(string(download, "url"));
            long downloadVerifyMillis = elapsedMillis(downloadVerifyStarted);
            if (storedSha256 == null || !storedSha256.equalsIgnoreCase(verification.objectSha256())) {
                throw new IOException("whole-object checksum mismatch for job " + id
                        + ": stored=" + storedSha256 + ", downloaded=" + verification.objectSha256());
            }
            int exit = verification.rows() == options.cell().rows() ? 0 : 1;
            return new SingleJob(id, "COMPLETED", null, verification.rows(), verification.bytes(),
                    verification.firstKey(), verification.lastKey(), verification.keyDigest(),
                    verification.objectSha256(), exit, submitMillis, queueMillis, presignMillis,
                    downloadVerifyMillis, elapsedMillis(totalStarted),
                    flatNumber(timing, "sourceReadMillis"), flatNumber(timing, "sourceCalls"),
                    flatNumber(timing, "csvProcessingMillis"), flatNumber(timing, "writerMillis"),
                    flatNumber(timing, "downstreamWriteMillis"), flatNumber(timing, "cosUploadMillis"),
                    flatNumber(timing, "cosUploadBytes"), flatNumber(timing, "exportMillis"));
        }

        private Verification verify(String url) throws Exception {
            if (url == null || url.isBlank()) throw new IOException("download response has no URL");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(30)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) throw new IOException("download returned " + response.statusCode());
            try (DigestingInputStream counted = new DigestingInputStream(
                    new BufferedInputStream(response.body()));
                 PushbackInputStream input = new PushbackInputStream(counted, 1)) {
                Verification parsed = "CSV".equals(options.cell().format())
                        ? verifyCsv(input) : verifyXlsx(input);
                // ZIP/StAX verification may stop at the end of the final
                // worksheet while the object still has central-directory or
                // trailing bytes. Consume the remainder before finalizing
                // the whole-object digest.
                input.transferTo(OutputStream.nullOutputStream());
                return parsed.withObject(counted.count(), counted.digestHex());
            }
        }

        private Verification verifyCsv(PushbackInputStream input) throws Exception {
            long records = 0, first = -1, last = -1, expected = 1;
            MessageDigest keyDigest = MessageDigest.getInstance("SHA-256");
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false, sawAny = false;
            int value;
            while ((value = input.read()) >= 0) {
                char c = (char) value;
                if (quoted) {
                    if (c == '"') {
                        int next = input.read();
                        if (next == '"') field.append('"');
                        else {
                            quoted = false;
                            if (next >= 0) input.unread(next);
                        }
                    } else field.append(c);
                    sawAny = true;
                } else if (c == '"' && field.isEmpty()) {
                    quoted = true;
                    sawAny = true;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    sawAny = true;
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        int next = input.read();
                        if (next != '\n' && next >= 0) input.unread(next);
                    }
                    fields.add(field.toString());
                    field.setLength(0);
                    RowKey row = rowKey(++records, fields, expected, keyDigest);
                    if (row != null) {
                        if (first < 0) first = row.key();
                        last = row.key();
                        expected = row.nextExpected();
                    }
                    fields.clear();
                    sawAny = false;
                } else {
                    field.append(c);
                    sawAny = true;
                }
            }
            if (sawAny || !field.isEmpty() || !fields.isEmpty()) {
                fields.add(field.toString());
                RowKey row = rowKey(++records, fields, expected, keyDigest);
                if (row != null) {
                    if (first < 0) first = row.key();
                    last = row.key();
                }
            }
            return new Verification(Math.max(0, records - 1), 0, first, last,
                    hex(keyDigest.digest()), null);
        }

        private static RowKey rowKey(long rowNumber, List<String> fields, long expected,
                                     MessageDigest digest) throws IOException {
            if (rowNumber == 1) {
                if (fields.isEmpty() || !"id".equalsIgnoreCase(fields.getFirst())) {
                    throw new IOException("CSV header does not start with id");
                }
                return null;
            }
            if (fields.size() < 2) throw new IOException("CSV data row has no value column: " + rowNumber);
            long key;
            try {
                key = Long.parseLong(fields.getFirst());
            } catch (NumberFormatException error) {
                throw new IOException("CSV key is not an integer at row " + rowNumber, error);
            }
            if (key != expected) throw new IOException("non-consecutive CSV key at row " + rowNumber
                    + ": expected " + expected + ", got " + key);
            digest.update(Long.toString(key).getBytes(StandardCharsets.UTF_8));
            return new RowKey(key, expected + 1);
        }

        private Verification verifyXlsx(InputStream input) throws Exception {
            long rows = 0, first = -1, last = -1;
            MessageDigest keyDigest = MessageDigest.getInstance("SHA-256");
            InputStream nonClosing = new FilterInputStream(input) {
                @Override public void close() { }
            };
            try (ZipInputStream zip = new ZipInputStream(nonClosing)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.getName().startsWith("xl/worksheets/sheet")) continue;
                    SheetVerification sheet = verifySheet(zip, keyDigest);
                    rows += sheet.rows();
                    if (first < 0) first = sheet.firstKey();
                    if (sheet.lastKey() >= 0) last = sheet.lastKey();
                }
            }
            return new Verification(Math.max(0, rows - 1), 0, first, last,
                    hex(keyDigest.digest()), null);
        }

        private static SheetVerification verifySheet(InputStream input, MessageDigest keyDigest)
                throws XMLStreamException, IOException {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            // StAX may close its input when the reader closes. The input here
            // is the current ZipInputStream entry, whose owner must remain
            // open so verifyXlsx can advance to the next ZIP entry.
            InputStream nonClosing = new FilterInputStream(input) {
                @Override public void close() { }
            };
            XMLStreamReader reader = factory.createXMLStreamReader(nonClosing,
                    StandardCharsets.UTF_8.name());
            long first = -1, last = -1, expected = 1;
            int rowNumber = 0, cellCount = 0;
            String cellRef = null;
            StringBuilder cellValue = new StringBuilder();
            boolean inCell = false, keySeen = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && "row".equals(reader.getLocalName())) {
                        rowNumber++;
                        cellCount = 0;
                        keySeen = false;
                    } else if (event == XMLStreamConstants.START_ELEMENT && "c".equals(reader.getLocalName())) {
                        cellCount++;
                        cellRef = reader.getAttributeValue(null, "r");
                        cellValue.setLength(0);
                        inCell = true;
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        if (inCell) cellValue.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT && "c".equals(reader.getLocalName())) {
                        if (cellRef != null && cellRef.matches("A\\d+")) {
                            if (rowNumber > 1) {
                                long key = parseSheetKey(cellValue.toString(), expected, rowNumber);
                                if (first < 0) first = key;
                                last = key;
                                expected++;
                                keyDigest.update(Long.toString(key).getBytes(StandardCharsets.UTF_8));
                                keySeen = true;
                            } else if (!"id".equalsIgnoreCase(cellValue.toString())) {
                                throw new IOException("XLSX header does not start with id");
                            }
                        }
                        inCell = false;
                    } else if (event == XMLStreamConstants.END_ELEMENT && "row".equals(reader.getLocalName())) {
                        if (cellCount < 2) throw new IOException("XLSX row has too few cells: " + rowNumber);
                        if (rowNumber > 1 && !keySeen) throw new IOException("XLSX row has no key: " + rowNumber);
                    }
                }
            } finally {
                reader.close();
            }
            return new SheetVerification(rowNumber, first, last);
        }

        private static long parseSheetKey(String cellValue, long expected, int rowNumber) throws IOException {
            long key;
            try {
                key = Long.parseLong(cellValue);
            } catch (NumberFormatException error) {
                throw new IOException("XLSX key is not an integer at row " + rowNumber, error);
            }
            if (key != expected) throw new IOException("non-consecutive XLSX key at row "
                    + rowNumber + ": expected " + expected + ", got " + key);
            return key;
        }

        private SingleJob getResult(Future<SingleJob> future) {
            try {
                return future.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();   // preserve the cancel signal for the pool
                throw new IllegalStateException(error);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IllegalStateException(cause);
            }
        }

        private String get(String url) throws Exception { return send("GET", url, null, null, null); }
        private void post(String url, String body) throws Exception { post(url, body, null, null); }
        private String post(String url, String body, String header, String value) throws Exception {
            return send("POST", url, body, header, value);
        }
        private String send(String method, String url, String body, String header, String value) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30));
            if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json");
            else builder.GET();
            if (header != null) builder.header(header, value);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IOException(method + " " + url + " returned "
                    + response.statusCode() + ": " + response.body());
            return response.body();
        }
    }

    private static final class ResourceSampler {
        private final String baseUrl;
        private final HttpClient client;
        private final AtomicLong peakHeap = new AtomicLong();
        private final AtomicLong peakRss = new AtomicLong();
        private final AtomicLong peakCgroup = new AtomicLong();
        private final AtomicLong maxActive = new AtomicLong();
        private final AtomicLong maxQueued = new AtomicLong();
        private final AtomicLong maxGlobalActive = new AtomicLong();
        private final AtomicLong globalLimit = new AtomicLong();
        private final AtomicLong oomKills = new AtomicLong();
        private final AtomicLong memoryLimit = new AtomicLong();
        private final AtomicLong telemetrySamples = new AtomicLong();
        private final AtomicLong invalidTelemetrySamples = new AtomicLong();
        private final ConcurrentHashMap<String, PodStats> podStats = new ConcurrentHashMap<>();
        private ScheduledExecutorService sampler;

        private ResourceSampler(String baseUrl, HttpClient client) {
            this.baseUrl = baseUrl;
            this.client = client;
        }

        private void start() {
            sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "proof-resource-sampler");
                thread.setDaemon(true);
                return thread;
            });
            // Short enough to observe a fast 10k concurrency cell;
            // the final sample remains authoritative for terminal
            // memory and filesystem values.
            sampler.scheduleWithFixedDelay(this::pollOnce, 0, 100, TimeUnit.MILLISECONDS);
        }

        private void pollOnce() {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(baseUrl + "/api/system/resources"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                observe(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Keep the proof running; the final endpoint sample remains authoritative.
            }
        }

        private void observe(String json) {
            String pod = string(json, "podName");
            if (pod == null || pod.isBlank()) pod = "unknown";
            Long cgroupPeak = optionalNumber(json, "cgroup", "peakBytes");
            Long cgroupLimit = optionalNumber(json, "cgroup", "limitBytes");
            Long cgroupOomKills = optionalNumber(json, "cgroup", "oomKills");
            Long configuredGlobalLimit = optionalNumber(json, "jobs", "globalLimit");
            Long activeGlobalJobs = optionalNumber(json, "jobs", "globalActive");
            podStats.computeIfAbsent(pod, ignored -> new PodStats()).observe(
                    cgroupPeak, cgroupLimit, cgroupOomKills,
                    activeGlobalJobs, configuredGlobalLimit);
            if (cgroupPeak != null && cgroupPeak > 0
                    && cgroupLimit != null && cgroupLimit > 0
                    && cgroupOomKills != null && cgroupOomKills >= 0
                    && configuredGlobalLimit != null && configuredGlobalLimit > 0) {
                telemetrySamples.incrementAndGet();
            } else {
                invalidTelemetrySamples.incrementAndGet();
            }
            peakHeap.accumulateAndGet(number(json, "heap", "peakBytes"), Math::max);
            peakRss.accumulateAndGet(number(json, "rss", "usedBytes"), Math::max);
            peakCgroup.accumulateAndGet(number(json, "cgroup", "peakBytes"), Math::max);
            maxActive.accumulateAndGet(number(json, "jobs", "active"), Math::max);
            maxQueued.accumulateAndGet(number(json, "jobs", "queued"), Math::max);
            maxGlobalActive.accumulateAndGet(number(json, "jobs", "globalActive"), Math::max);
            globalLimit.accumulateAndGet(number(json, "jobs", "globalLimit"), Math::max);
            oomKills.accumulateAndGet(number(json, "cgroup", "oomKills"), Math::max);
            memoryLimit.accumulateAndGet(number(json, "cgroup", "limitBytes"), Math::max);
        }

        private void stop() {
            if (sampler != null) sampler.shutdownNow();
        }

        private long peakHeap() { return peakHeap.get(); }
        private long peakRss() { return peakRss.get(); }
        private long peakCgroup() { return peakCgroup.get(); }
        private long maxActive() { return maxActive.get(); }
        private long maxQueued() { return maxQueued.get(); }
        private long maxGlobalActive() { return maxGlobalActive.get(); }
        private long globalLimit() { return globalLimit.get(); }
        private long oomKills() { return oomKills.get(); }
        private long memoryLimit() { return memoryLimit.get(); }
        private long telemetrySamples() { return telemetrySamples.get(); }
        private long invalidTelemetrySamples() { return invalidTelemetrySamples.get(); }
        private List<PodEvidence> podEvidence() {
            return new TreeSet<>(podStats.keySet()).stream()
                    .map(name -> podStats.get(name).evidence(name)).toList();
        }
        private boolean telemetryValid() {
            return telemetrySamples() > 0 && invalidTelemetrySamples() == 0;
        }
        private boolean globalCeilingObserved() {
            return telemetryValid() && globalLimit() > 0 && maxGlobalActive() <= globalLimit();
        }
        private boolean memoryWithinLimit() {
            return telemetryValid()
                    && peakCgroup() <= memoryLimit() + MEMORY_ACCOUNTING_TOLERANCE_BYTES;
        }
        private boolean overlapObserved(int requestedConcurrency) {
            if (requestedConcurrency <= 1) return true;
            long configuredLimit = globalLimit();
            long expected = configuredLimit > 0
                    ? Math.min(requestedConcurrency, configuredLimit) : requestedConcurrency;
            return maxActive() >= expected && maxGlobalActive() <= (configuredLimit > 0
                    ? configuredLimit : Long.MAX_VALUE);
        }

        private static final class PodStats {
            private final AtomicLong peakCgroup = new AtomicLong(-1);
            private final AtomicLong memoryLimit = new AtomicLong(-1);
            private final AtomicLong maxGlobalActive = new AtomicLong(-1);
            private final AtomicLong globalLimit = new AtomicLong(-1);
            private final AtomicLong oomKills = new AtomicLong(-1);
            private final AtomicLong validSamples = new AtomicLong();
            private final AtomicLong invalidSamples = new AtomicLong();

            private void observe(Long cgroupPeak, Long cgroupLimit, Long cgroupOomKills,
                                 Long activeGlobalJobs, Long configuredGlobalLimit) {
                max(peakCgroup, cgroupPeak);
                max(memoryLimit, cgroupLimit);
                max(maxGlobalActive, activeGlobalJobs);
                max(globalLimit, configuredGlobalLimit);
                max(oomKills, cgroupOomKills);
                boolean valid = cgroupPeak != null && cgroupPeak > 0
                        && cgroupLimit != null && cgroupLimit > 0
                        && cgroupOomKills != null && cgroupOomKills >= 0
                        && configuredGlobalLimit != null && configuredGlobalLimit > 0;
                (valid ? validSamples : invalidSamples).incrementAndGet();
            }

            private PodEvidence evidence(String name) {
                return new PodEvidence(name, peakCgroup.get(), memoryLimit.get(),
                        maxGlobalActive.get(), globalLimit.get(), oomKills.get(),
                        validSamples.get(), invalidSamples.get());
            }

            private static void max(AtomicLong target, Long value) {
                if (value != null) target.accumulateAndGet(value, Math::max);
            }
        }
    }

    private static final class DigestingInputStream extends InputStream {
        private final InputStream delegate;
        private final MessageDigest digest;
        private long bytes;

        private DigestingInputStream(InputStream delegate) {
            this.delegate = delegate;
            try { this.digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
        }

        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) { digest.update((byte) value); bytes++; }
            return value;
        }

        // InputStream.read(byte[],int,int) carries JDK nullability annotations we
        // cannot mirror without the org.jetbrains:annotations dependency.
        @SuppressWarnings("NullableProblems")
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) { digest.update(buffer, offset, read); bytes += read; }
            return read;
        }

        private long count() { return bytes; }
        private String digestHex() { return hex(digest.digest()); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private record Verification(long rows, long bytes, long firstKey, long lastKey,
                                String keyDigest, String objectSha256) {
        private Verification withObject(long bytes, String sha256) {
            return new Verification(rows, bytes, firstKey, lastKey, keyDigest, sha256);
        }
    }

    private record RowKey(long key, long nextExpected) { }
    private record SheetVerification(long rows, long firstKey, long lastKey) { }

    private record SingleJob(String id, String status, String errorCode, long rowCount,
                             long objectBytes, long firstKey, long lastKey, String keyDigest,
                             String objectSha256, int exitCode, long submitMillis, long queueMillis,
                             long presignMillis, long downloadVerifyMillis, long totalMillis,
                             long sourceReadMillis, long sourceCalls, long csvProcessingMillis,
                             long writerMillis, long downstreamWriteMillis, long cosUploadMillis,
                             long cosUploadBytes, long exportMillis) {
        private static SingleJob failed(String id, String status, String error, int exit,
                                        long submitMillis, long queueMillis, long totalMillis) {
            return new SingleJob(id, status, error, 0, 0, -1, -1, "", "", exit,
                    submitMillis, queueMillis, 0, 0, totalMillis, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record Result(String cellId, String adapter, String relation, String format,
                          long expectedRows, int concurrency, int verifiedJobs, String status,
                          String errorCode, long rowCount, long objectBytes, long peakHeapBytes,
                          long peakRssBytes, long peakCgroupBytes, long maxActiveJobs,
                          long maxQueuedJobs, long maxGlobalActiveJobs, long globalLimit,
                          long oomKills, long memoryLimitBytes,
                          List<PodEvidence> podEvidence, long telemetrySamples,
                          long invalidTelemetrySamples, boolean telemetryValid,
                          boolean overlapObserved, boolean globalCeilingObserved,
                          boolean memoryWithinLimit, boolean filesystemOverflowed,
                          int exitCode, long filesystemEvents,
                          long firstKey, long lastKey, String keyDigest, String objectSha256,
                          long seedMillis, long submitMillis, long queueMillis, long presignMillis,
                          long downloadVerifyMillis, long totalMillis, long sourceReadMillis,
                          long sourceCalls, long csvProcessingMillis, long writerMillis,
                          long downstreamWriteMillis, long cosUploadMillis, long cosUploadBytes,
                          long exportMillis) {
        private static Result from(Options options, String adapter, List<SingleJob> jobs,
                                   ResourceSampler sampler, long filesystemEvents,
                                   boolean filesystemOverflowed, boolean overlapObserved,
                                   boolean memoryWithinLimit, int exit,
                                   long seedMillis, long totalMillis) {
            SingleJob first = jobs.getFirst();
            boolean completed = jobs.stream().allMatch(job -> "COMPLETED".equals(job.status()));
            return new Result(options.cell().id(), adapter, options.relation(), options.cell().format(),
                    options.cell().rows(), options.cell().concurrency(),
                    (int) jobs.stream().filter(job -> job.exitCode() == 0).count(),
                    completed ? "COMPLETED" : first.status(), first.errorCode(), first.rowCount(),
                    first.objectBytes(), sampler.peakHeap(), sampler.peakRss(), sampler.peakCgroup(),
                    sampler.maxActive(), sampler.maxQueued(), sampler.maxGlobalActive(),
                    sampler.globalLimit(), sampler.oomKills(), sampler.memoryLimit(),
                    sampler.podEvidence(), sampler.telemetrySamples(), sampler.invalidTelemetrySamples(),
                    sampler.telemetryValid(), overlapObserved, sampler.globalCeilingObserved(),
                    memoryWithinLimit, filesystemOverflowed,
                    exit, filesystemEvents, first.firstKey(), first.lastKey(), first.keyDigest(),
                    first.objectSha256(), seedMillis, max(jobs, SingleJob::submitMillis),
                    max(jobs, SingleJob::queueMillis), max(jobs, SingleJob::presignMillis),
                    max(jobs, SingleJob::downloadVerifyMillis), totalMillis,
                    sum(jobs, SingleJob::sourceReadMillis), sum(jobs, SingleJob::sourceCalls),
                    sum(jobs, SingleJob::csvProcessingMillis), sum(jobs, SingleJob::writerMillis),
                    sum(jobs, SingleJob::downstreamWriteMillis), sum(jobs, SingleJob::cosUploadMillis),
                    sum(jobs, SingleJob::cosUploadBytes), max(jobs, SingleJob::exportMillis));
        }

        private String json() {
            double seconds = totalMillis / 1000.0;
            long rowsPerSecond = seconds <= 0 ? 0 : Math.round(rowCount / seconds);
            long bytesPerSecond = seconds <= 0 ? 0 : Math.round(objectBytes / seconds);
            return "{\"cellId\":\"" + StreamingRunner.json(cellId) + "\",\"adapter\":\"" + StreamingRunner.json(adapter)
                    + "\",\"relation\":\"" + StreamingRunner.json(relation) + "\",\"format\":\"" + format
                    + "\",\"expectedRows\":" + expectedRows + ",\"concurrency\":" + concurrency
                    + ",\"verifiedJobs\":" + verifiedJobs + ",\"status\":\"" + status
                    + "\",\"errorCode\":" + (errorCode == null ? "null" : "\"" + StreamingRunner.json(errorCode) + "\"")
                    + ",\"rowCount\":" + rowCount + ",\"objectBytes\":" + objectBytes
                    + ",\"peakHeapBytes\":" + peakHeapBytes + ",\"peakRssBytes\":" + peakRssBytes
                    + ",\"peakCgroupBytes\":" + peakCgroupBytes
                    + ",\"maxActiveJobs\":" + maxActiveJobs
                    + ",\"maxQueuedJobs\":" + maxQueuedJobs
                    + ",\"maxGlobalActiveJobs\":" + maxGlobalActiveJobs
                    + ",\"globalLimit\":" + globalLimit
                    + ",\"oomKills\":" + oomKills
                    + ",\"memoryLimitBytes\":" + memoryLimitBytes
                    + ",\"memoryToleranceBytes\":" + MEMORY_ACCOUNTING_TOLERANCE_BYTES
                    + ",\"podEvidence\":[" + podEvidence.stream()
                            .map(PodEvidence::json)
                            .collect(java.util.stream.Collectors.joining(",")) + "]"
                    + ",\"telemetrySamples\":" + telemetrySamples
                    + ",\"invalidTelemetrySamples\":" + invalidTelemetrySamples
                    + ",\"telemetryValid\":" + telemetryValid
                    + ",\"overlapObserved\":" + overlapObserved
                    + ",\"globalCeilingObserved\":" + globalCeilingObserved
                    + ",\"memoryWithinLimit\":" + memoryWithinLimit
                    + ",\"filesystemOverflowed\":" + filesystemOverflowed
                    + ",\"exitCode\":" + exitCode
                    + ",\"filesystemEvents\":" + filesystemEvents + ",\"firstKey\":" + firstKey
                    + ",\"lastKey\":" + lastKey + ",\"keyDigest\":\"" + keyDigest
                    + "\",\"objectSha256\":\"" + objectSha256 + "\",\"seedMillis\":" + seedMillis
                    + ",\"submitMillis\":" + submitMillis + ",\"queueMillis\":" + queueMillis
                    + ",\"presignMillis\":" + presignMillis + ",\"downloadVerifyMillis\":"
                    + downloadVerifyMillis + ",\"totalMillis\":" + totalMillis
                    + ",\"rowsPerSecond\":" + rowsPerSecond + ",\"bytesPerSecond\":" + bytesPerSecond
                    + ",\"sourceReadMillis\":" + sourceReadMillis + ",\"sourceCalls\":" + sourceCalls
                    + ",\"csvProcessingMillis\":" + csvProcessingMillis + ",\"writerMillis\":" + writerMillis
                    + ",\"downstreamWriteMillis\":" + downstreamWriteMillis
                    + ",\"cosUploadMillis\":" + cosUploadMillis + ",\"cosUploadBytes\":" + cosUploadBytes
                    + ",\"exportMillis\":" + exportMillis + "}";
        }

        private static long sum(List<SingleJob> jobs, java.util.function.ToLongFunction<SingleJob> getter) {
            return jobs.stream().mapToLong(getter).sum();
        }
        private static long max(List<SingleJob> jobs, java.util.function.ToLongFunction<SingleJob> getter) {
            return jobs.stream().mapToLong(getter).max().orElse(0);
        }
    }

    private record PodEvidence(String podName, long peakCgroupBytes, long memoryLimitBytes,
                               long maxGlobalActiveJobs, long globalLimit, long oomKills,
                               long telemetrySamples, long invalidTelemetrySamples) {
        private String json() {
            return "{\"podName\":\"" + StreamingRunner.json(podName)
                    + "\",\"peakCgroupBytes\":" + peakCgroupBytes
                    + ",\"memoryLimitBytes\":" + memoryLimitBytes
                    + ",\"maxGlobalActiveJobs\":" + maxGlobalActiveJobs
                    + ",\"globalLimit\":" + globalLimit
                    + ",\"oomKills\":" + oomKills
                    + ",\"telemetrySamples\":" + telemetrySamples
                    + ",\"invalidTelemetrySamples\":" + invalidTelemetrySamples + "}";
        }
    }

    private record Options(String baseUrl, String relation, MatrixCell cell,
                           boolean seed, long maxId) {
        static Options parse(String[] args) {
            String base = value(args, "--base-url", System.getenv().getOrDefault("OMNIFLUX_PROOF_URL", "http://localhost:8080"));
            String cellId = value(args, "--cell", "S1-10k");
            MatrixCell cell = MatrixCell.fullMatrix().stream().filter(c -> c.id().equals(cellId)).findFirst()
                    .orElse(new MatrixCell(cellId, Long.parseLong(value(args, "--rows", "10000")),
                            value(args, "--format", "CSV"), value(args, "--adapter", "rest"), "narrow", 1));
            String relation = value(args, "--relation", "wide".equals(cell.shape()) ? "mock_wide" : "mock_customers");
            String seedValue = System.getenv().getOrDefault("PROOF_SEED", "1");
            boolean seed = !skipSeed(args) && !"0".equals(seedValue)
                    && !"false".equalsIgnoreCase(seedValue);
            String maxIdValue = value(args, "--max-id", System.getenv().getOrDefault("PROOF_MAX_ID", ""));
            long maxId = maxIdValue.isBlank() ? cell.rows() : Long.parseLong(maxIdValue);
            if (maxId < cell.rows() || maxId < 0) {
                throw new IllegalArgumentException("--max-id must be at least the expected cell row count");
            }
            return new Options(base.replaceAll("/$", ""), relation, cell, seed, maxId);
        }
        private static boolean skipSeed(String[] args) {
            for (String arg : args) if ("--skip-seed".equals(arg)) return true;
            return false;
        }
        private static String value(String[] args, String name, String fallback) {
            for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
            return fallback;
        }
    }

    private static String adapter(String json) throws IOException {
        Matcher matcher = ADAPTER.matcher(json);
        if (!matcher.find()) throw new IOException("system response has no effective source adapter");
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }
    private static String string(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }
    private static long number(String json, String parent, String key) {
        Long value = optionalNumber(json, parent, key);
        return value == null ? 0 : value;
    }
    private static Long optionalNumber(String json, String parent, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(parent) + "\\\"\\s*:\\s*\\{[^}]*?\\\""
                + Pattern.quote(key) + "\\\"\\s*:\\s*(null|-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) return null;
        return Long.parseLong(matcher.group(1));
    }
    private static boolean overflowed(String json) {
        Pattern pattern = Pattern.compile(
                "\\\"filesystem\\\"\\s*:\\s*\\{[^}]*?\\\"overflowed\\\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }
    private static long flatNumber(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }
    private static long counter(String json, String key) { return number(json, "filesystem", key); }
    private static long elapsedMillis(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
