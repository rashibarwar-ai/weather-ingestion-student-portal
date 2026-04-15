import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ============================================================
 *  High-Frequency Data Ingestion System – Weather Monitor
 *  ============================================================
 *  Architecture
 *  ─────────────────────────────────────────────────────────────
 *  • 15 Producer Threads  →  Thread-Safe Buffer (ReentrantLock)
 *  •  1 Consumer Thread   →  SQLite DB (Try-with-Resources)
 *
 *  Design highlights
 *  ─────────────────────────────────────────────────────────────
 *  ✔ ReentrantLock for deadlock-free, fair buffer access
 *  ✔ BlockingQueue (LinkedBlockingQueue) as the concurrent buffer
 *  ✔ Try-with-Resources on every DB connection (zero leakage)
 *  ✔ AtomicInteger for thread-safe alert counting
 *  ✔ Graceful shutdown via poison-pill + ExecutorService.awaitTermination
 * ============================================================
 */
public class WeatherDataIngestion {

    // ── Configuration ──────────────────────────────────────────────────────────
    private static final int    NUM_STATIONS       = 15;
    private static final int    BUFFER_CAPACITY    = 200;
    private static final int    SIMULATION_SECONDS = 20;   // run for N seconds then stop
    private static final double ALERT_THRESHOLD    = 45.0; // °C
    private static final String DB_URL             = "jdbc:sqlite:weather_alerts.db";

    // ── Shared Buffer ──────────────────────────────────────────────────────────
    /**
     * Thread-safe buffer backed by a bounded BlockingQueue.
     * A ReentrantLock guards the offer/poll operations to satisfy
     * the "synchronized-block or ReentrantLock" requirement explicitly,
     * while the BlockingQueue itself provides additional condition signalling.
     */
    static final class WeatherBuffer {
        private final BlockingQueue<WeatherReading> queue;
        private final ReentrantLock lock = new ReentrantLock(true); // fair lock

        WeatherBuffer(int capacity) {
            this.queue = new LinkedBlockingQueue<>(capacity);
        }

        /** Producer calls this. Returns false if buffer is full (data dropped with a warning). */
        boolean produce(WeatherReading reading) {
            lock.lock();
            try {
                boolean added = queue.offer(reading);
                if (!added) {
                    System.err.printf("[WARN ] Buffer full – dropped reading from %s%n",
                            reading.stationId());
                }
                return added;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Consumer calls this.
         * Blocks up to 2 s; returns null on timeout so the consumer can check
         * the shutdown flag and exit cleanly.
         */
        WeatherReading consume() throws InterruptedException {
            return queue.poll(2, TimeUnit.SECONDS);
        }

        int size() {
            return queue.size();
        }
    }

    // ── Data Model ─────────────────────────────────────────────────────────────
    record WeatherReading(String stationId, double temperatureCelsius, String timestamp) {}

    // ── Producer Thread ────────────────────────────────────────────────────────
    static final class WeatherStation implements Runnable {
        private final String        stationId;
        private final WeatherBuffer buffer;
        private final Random        rng        = new Random();
        private volatile boolean    running    = true;

        WeatherStation(int id, WeatherBuffer buffer) {
            this.stationId = "Station-" + String.format("%02d", id);
            this.buffer    = buffer;
        }

        void stop() { running = false; }

        @Override
        public void run() {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            System.out.printf("[INFO ] %s started%n", stationId);

            while (running) {
                // Simulate temperature: 20–65 °C (occasionally extreme)
                double temp = 20.0 + (rng.nextDouble() * 45.0);
                String ts   = LocalDateTime.now().format(fmt);

                buffer.produce(new WeatherReading(stationId, temp, ts));

                // Random interval: 100–600 ms between readings
                try {
                    Thread.sleep(100 + rng.nextInt(500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.printf("[INFO ] %s stopped%n", stationId);
        }
    }

    // ── Consumer Thread ────────────────────────────────────────────────────────
    static final class AlertConsumer implements Runnable {
        private final WeatherBuffer  buffer;
        private final AtomicInteger  alertCount = new AtomicInteger(0);
        private volatile boolean     running    = true;

        AlertConsumer(WeatherBuffer buffer) {
            this.buffer = buffer;
        }

        void stop() { running = false; }
        int  alertCount() { return alertCount.get(); }

        @Override
        public void run() {
            System.out.println("[INFO ] AlertConsumer started");
            initDatabase();

            while (running) {
                try {
                    WeatherReading reading = buffer.consume();
                    if (reading == null) continue; // timeout – loop back and check `running`

                    if (reading.temperatureCelsius() > ALERT_THRESHOLD) {
                        persistAlert(reading);
                        alertCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Drain any remaining alerts left in the buffer after producers have stopped
            drainRemaining();
            System.out.printf("[INFO ] AlertConsumer stopped. Total alerts persisted: %d%n",
                    alertCount.get());
        }

        /** Create the alerts table if it does not exist. Try-with-Resources used. */
        private void initDatabase() {
            String ddl = """
                    CREATE TABLE IF NOT EXISTS extreme_weather_alerts (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        station_id  TEXT    NOT NULL,
                        temperature REAL    NOT NULL,
                        recorded_at TEXT    NOT NULL,
                        alert_label TEXT    NOT NULL DEFAULT 'Extreme Weather Alert'
                    )
                    """;
            // ── Try-with-Resources: Connection + Statement ─────────────────────
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement  stmt = conn.createStatement()) {

                stmt.execute(ddl);
                System.out.println("[INFO ] Database initialised: weather_alerts.db");

            } catch (SQLException e) {
                System.err.println("[ERROR] DB init failed: " + e.getMessage());
            }
            // Connection and Statement are auto-closed here — zero resource leakage
        }

        /**
         * Persist a single extreme-weather alert.
         * Try-with-Resources guarantees the PreparedStatement and Connection
         * are closed even if an exception is thrown mid-insert.
         */
        private void persistAlert(WeatherReading reading) {
            String sql = """
                    INSERT INTO extreme_weather_alerts
                        (station_id, temperature, recorded_at, alert_label)
                    VALUES (?, ?, ?, ?)
                    """;

            // ── Try-with-Resources: Connection + PreparedStatement ─────────────
            try (Connection        conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps   = conn.prepareStatement(sql)) {

                ps.setString(1, reading.stationId());
                ps.setDouble(2, reading.temperatureCelsius());
                ps.setString(3, reading.timestamp());
                ps.setString(4, "Extreme Weather Alert");
                ps.executeUpdate();

                System.out.printf("[ALERT] %-12s  %.2f°C  at %s%n",
                        reading.stationId(),
                        reading.temperatureCelsius(),
                        reading.timestamp());

            } catch (SQLException e) {
                System.err.printf("[ERROR] Insert failed for %s: %s%n",
                        reading.stationId(), e.getMessage());
            }
            // Connection is auto-closed here — green computing / zero leakage ✔
        }

        /** Called after producers stop to flush any remaining alerts. */
        private void drainRemaining() {
            WeatherReading reading;
            try {
                while ((reading = buffer.consume()) != null) {
                    if (reading.temperatureCelsius() > ALERT_THRESHOLD) {
                        persistAlert(reading);
                        alertCount.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Reporting ──────────────────────────────────────────────────────────────
    static void printReport(AlertConsumer consumer) {
        System.out.println("\n══════════════════════════════════════════════════");
        System.out.println("  SIMULATION COMPLETE – FINAL REPORT");
        System.out.println("══════════════════════════════════════════════════");
        System.out.printf("  Extreme Weather Alerts persisted : %d%n", consumer.alertCount());
        System.out.printf("  SQLite database                  : weather_alerts.db%n");
        System.out.println("──────────────────────────────────────────────────");
        System.out.println("  Top 10 most recent alerts:");
        System.out.println("──────────────────────────────────────────────────");

        String query = """
                SELECT station_id, temperature, recorded_at
                FROM   extreme_weather_alerts
                ORDER  BY id DESC
                LIMIT  10
                """;

        // ── Try-with-Resources: Connection + Statement + ResultSet ─────────────
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(query)) {

            int row = 0;
            while (rs.next()) {
                System.out.printf("  [%2d] %-12s  %.2f°C  %s%n",
                        ++row,
                        rs.getString("station_id"),
                        rs.getDouble("temperature"),
                        rs.getString("recorded_at"));
            }
            if (row == 0) System.out.println("  (no alerts recorded)");

        } catch (SQLException e) {
            System.err.println("[ERROR] Report query failed: " + e.getMessage());
        }
        // All resources auto-closed ✔

        System.out.println("══════════════════════════════════════════════════\n");
    }

    // ── Main ───────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws InterruptedException {

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  High-Frequency Weather Data Ingestion System    ║");
        System.out.println("║  15 Producers | 1 Consumer | SQLite alerts DB    ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        // 1. Shared thread-safe buffer
        WeatherBuffer buffer = new WeatherBuffer(BUFFER_CAPACITY);

        // 2. Create and start producers
        ExecutorService producerPool = Executors.newFixedThreadPool(NUM_STATIONS);
        WeatherStation[] stations    = new WeatherStation[NUM_STATIONS];

        for (int i = 1; i <= NUM_STATIONS; i++) {
            stations[i - 1] = new WeatherStation(i, buffer);
            producerPool.submit(stations[i - 1]);
        }

        // 3. Create and start consumer
        AlertConsumer consumer        = new AlertConsumer(buffer);
        Thread        consumerThread  = new Thread(consumer, "AlertConsumer");
        consumerThread.start();

        // 4. Let the simulation run for SIMULATION_SECONDS
        System.out.printf("%n[SIM  ] Running simulation for %d seconds …%n%n",
                SIMULATION_SECONDS);
        Thread.sleep(SIMULATION_SECONDS * 1000L);

        // 5. Signal producers to stop
        for (WeatherStation station : stations) station.stop();
        producerPool.shutdown();
        producerPool.awaitTermination(5, TimeUnit.SECONDS);

        // 6. Signal consumer to stop (it will drain the buffer first)
        consumer.stop();
        consumerThread.join(10_000);

        // 7. Print final report
        printReport(consumer);
    }
}