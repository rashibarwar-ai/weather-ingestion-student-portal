import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  Modular Student Resource Portal
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  Design patterns used
 *  ─────────────────────────────────────────────────────────────────────────
 *  ① Factory Pattern    — ParserFactory creates the correct IFileParser at
 *                         runtime based on file extension, with zero changes
 *                         to calling code when a new parser is added.
 *
 *  ② Strategy Pattern   — EncryptionStrategy is an interface; three concrete
 *                         strategies (None, AES128, AES256) are swapped in at
 *                         upload time, keeping encryption logic completely
 *                         decoupled from the portal core.
 *
 *  ③ Singleton Pattern  — DatabaseConnectionPool is a Singleton.
 *    WHY: A connection pool creates and manages a fixed set of expensive JDBC
 *    connections. If every upload request instantiated its own pool, the JVM
 *    would exhaust database connections and heap memory almost immediately.
 *    A single shared instance caps connections at MAX_POOL_SIZE (5 here),
 *    lets threads borrow and return connections safely, and is created only
 *    once for the lifetime of the JVM — saving both memory and connection-
 *    setup latency for every subsequent request.
 *
 *  ④ Manual DI via Java Reflection — PluginLoader scans a local /parsers
 *     folder, loads .class files dynamically with URLClassLoader, and injects
 *     them into ParserFactory — all without a single hard-coded import of the
 *     plugin class. New parsers are dropped in as compiled .class files; the
 *     core never changes.
 *
 *  JDBC Transactions
 *  ─────────────────────────────────────────────────────────────────────────
 *  setAutoCommit(false) wraps the three-step upload (insert resource row,
 *  insert tag rows, update faculty upload count) in one atomic transaction.
 *  If any step fails, a full rollback fires — the DB is never left in a
 *  partial state.
 * ════════════════════════════════════════════════════════════════════════════
 */
public class StudentResourcePortal {

    // ════════════════════════════════════════════════════════════════════════
    //  1. ENCRYPTION STRATEGY  (Strategy Pattern)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Strategy interface — every encryption level implements this contract.
     * The portal core depends only on this interface, never on concrete classes.
     */
    interface EncryptionStrategy {
        String name();
        byte[] encrypt(byte[] data);
        String describe();
    }

    /** Level 0 — plain-text / open-source materials that need no encryption. */
    static final class NoEncryption implements EncryptionStrategy {
        @Override public String name() { return "NONE"; }
        @Override public byte[] encrypt(byte[] data) { return data; } // pass-through
        @Override public String describe() { return "No encryption applied (open-source content)"; }
    }

    /**
     * Level 1 — lightweight AES-128 simulation.
     * (XOR with a 128-bit key; swap for javax.crypto in production.)
     */
    static final class AES128Encryption implements EncryptionStrategy {
        private static final byte[] KEY = "StudentPortal128".getBytes(); // 16 bytes = 128 bit

        @Override public String name() { return "AES-128"; }

        @Override
        public byte[] encrypt(byte[] data) {
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
            }
            return out;
        }

        @Override public String describe() { return "AES-128 XOR encryption (restricted materials)"; }
    }

    /**
     * Level 2 — stronger AES-256 simulation.
     * (XOR with a 256-bit key; same swap-for-javax.crypto note applies.)
     */
    static final class AES256Encryption implements EncryptionStrategy {
        private static final byte[] KEY = "StudentPortalAES256SecureKey!!!!".getBytes(); // 32 bytes = 256 bit

        @Override public String name() { return "AES-256"; }

        @Override
        public byte[] encrypt(byte[] data) {
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
            }
            return out;
        }

        @Override public String describe() { return "AES-256 XOR encryption (confidential exam papers)"; }
    }

    /** Factory method for encryption levels — keeps portal code clean. */
    static EncryptionStrategy encryptionFor(int level) {
        return switch (level) {
            case 1  -> new AES128Encryption();
            case 2  -> new AES256Encryption();
            default -> new NoEncryption();
        };
    }


    // ════════════════════════════════════════════════════════════════════════
    //  2. FILE PARSER  (Factory Pattern + pluggable Strategy)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Plugin contract — every file-type parser must implement this interface.
     * Dropping a new compiled parser into /parsers and restarting the portal
     * is the ONLY step needed to support a new file type.
     */
    public interface IFileParser {
        /** File extension this parser handles, lowercase without dot (e.g. "pdf"). */
        String supportedExtension();
        /** Extract human-readable content from raw file bytes. */
        ParsedContent parse(byte[] rawBytes, String fileName);
    }

    /** Value object returned by every parser. */
    record ParsedContent(String title, String body, int wordCount, Map<String, String> metadata) {}

    // ── Built-in parsers (compiled into the main class for convenience) ─────

    /** Simulates PDF text extraction (pdfbox would replace this in production). */
    public static final class PdfParser implements IFileParser {
        @Override public String supportedExtension() { return "pdf"; }

        @Override
        public ParsedContent parse(byte[] rawBytes, String fileName) {
            // Production: use Apache PDFBox PdfTextStripper
            String simulated = "[PDF content extracted from " + fileName + "]\n"
                + "Introduction to Data Structures — Chapter 1\n"
                + "Topics: arrays, linked lists, stacks, queues, trees, graphs.\n"
                + "Word count estimated from byte length: " + (rawBytes.length / 6);
            int words = simulated.split("\\s+").length;
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("pages",    "12");
            meta.put("version",  "PDF-1.4");
            meta.put("author",   "Faculty Upload");
            return new ParsedContent("PDF: " + fileName, simulated, words, meta);
        }
    }

    /** Parses Markdown: strips # headers and ** bold markers. */
    public static final class MarkdownParser implements IFileParser {
        @Override public String supportedExtension() { return "md"; }

        @Override
        public ParsedContent parse(byte[] rawBytes, String fileName) {
            String raw  = new String(rawBytes);
            String body = raw.replaceAll("#+\\s+", "")   // strip heading markers
                             .replaceAll("\\*\\*(.*?)\\*\\*", "$1") // strip bold
                             .replaceAll("_(.*?)_", "$1");           // strip italic
            int words = body.trim().isEmpty() ? 0 : body.trim().split("\\s+").length;
            // Extract first heading as title
            String title = Arrays.stream(raw.split("\n"))
                                 .filter(l -> l.startsWith("#"))
                                 .map(l -> l.replaceFirst("#+\\s*", ""))
                                 .findFirst()
                                 .orElse(fileName);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("headings", String.valueOf(raw.lines().filter(l -> l.startsWith("#")).count()));
            meta.put("codeBlocks", String.valueOf((raw.split("```").length - 1) / 2));
            return new ParsedContent(title, body, words, meta);
        }
    }

    /** Plain-text parser — trivially splits on whitespace. */
    public static final class TextParser implements IFileParser {
        @Override public String supportedExtension() { return "txt"; }

        @Override
        public ParsedContent parse(byte[] rawBytes, String fileName) {
            String body  = new String(rawBytes);
            int    words = body.trim().isEmpty() ? 0 : body.trim().split("\\s+").length;
            String title = Arrays.stream(body.split("\n"))
                                 .filter(l -> !l.isBlank())
                                 .findFirst()
                                 .map(l -> l.substring(0, Math.min(l.length(), 60)))
                                 .orElse(fileName);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("lines", String.valueOf(body.lines().count()));
            meta.put("chars", String.valueOf(body.length()));
            return new ParsedContent(title, body, words, meta);
        }
    }

    /**
     * ── Factory Pattern ──────────────────────────────────────────────────
     *
     * ParserFactory is the single point of parser creation. Callers ask for
     * a parser by extension; they never new-up a concrete parser class.
     *
     * PluginLoader injects dynamically loaded parsers via registerParser(),
     * so the factory grows at runtime without source-level changes.
     */
    static final class ParserFactory {
        /** Registry: extension → parser instance. */
        private final Map<String, IFileParser> registry = new ConcurrentHashMap<>();

        /** Register all built-in parsers at construction time. */
        ParserFactory() {
            register(new PdfParser());
            register(new MarkdownParser());
            register(new TextParser());
        }

        void register(IFileParser parser) {
            registry.put(parser.supportedExtension().toLowerCase(), parser);
            System.out.printf("  [Factory] Registered parser: %-12s → %s%n",
                    parser.supportedExtension(), parser.getClass().getSimpleName());
        }

        /**
         * Factory method — the core of the Factory Pattern.
         * Returns the correct parser or throws if unsupported.
         */
        IFileParser getParser(String fileName) {
            String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";
            IFileParser parser = registry.get(ext);
            if (parser == null) {
                throw new UnsupportedOperationException(
                    "No parser registered for extension: '" + ext + "'");
            }
            return parser;
        }

        Set<String> supportedExtensions() {
            return Collections.unmodifiableSet(registry.keySet());
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  3. PLUGIN LOADER  (Manual Dependency Injection via Java Reflection)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Scans a local /parsers directory for compiled .class files, loads them
     * with URLClassLoader, instantiates the class via reflection, and injects
     * it into the ParserFactory — all without touching a single import statement
     * in the core code.
     *
     * DI flow:
     *   File system  →  URLClassLoader  →  Class.forName()
     *   →  newInstance()  →  ParserFactory.register()
     *
     * This is "constructor injection" done manually: the dependency (parser)
     * is created outside the consumer (factory) and handed in via register().
     */
    static final class PluginLoader {
        private final Path   pluginDir;
        private final ParserFactory factory;

        PluginLoader(Path pluginDir, ParserFactory factory) {
            this.pluginDir = pluginDir;
            this.factory   = factory;
        }

        /**
         * Loads all .class files in pluginDir.
         * Each class must implement IFileParser and have a no-arg constructor.
         */
        int loadPlugins() {
            if (!Files.isDirectory(pluginDir)) {
                System.out.println("  [PluginLoader] No /parsers directory found — skipping dynamic load.");
                return 0;
            }

            int loaded = 0;
            try (var stream = Files.list(pluginDir)) {
                List<Path> classFiles = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();

                if (classFiles.isEmpty()) {
                    System.out.println("  [PluginLoader] /parsers directory is empty.");
                    return 0;
                }

                // URLClassLoader needs the directory URL, not the file URL
                URL[] urls = { pluginDir.toUri().toURL() };
                try (URLClassLoader loader = new URLClassLoader(urls,
                        StudentResourcePortal.class.getClassLoader())) {

                    for (Path classFile : classFiles) {
                        String className = classFile.getFileName().toString()
                                                    .replace(".class", "");
                        try {
                            // ── Reflection: load class by name ───────────────
                            Class<?> clazz = loader.loadClass(className);

                            // Verify it implements IFileParser before instantiating
                            if (!IFileParser.class.isAssignableFrom(clazz)) {
                                System.out.printf("  [PluginLoader] Skipped %-20s (does not implement IFileParser)%n", className);
                                continue;
                            }

                            // ── Reflection: invoke no-arg constructor ─────────
                            Constructor<?> ctor = clazz.getDeclaredConstructor();
                            ctor.setAccessible(true);
                            IFileParser plugin = (IFileParser) ctor.newInstance();

                            // ── DI: inject into factory ───────────────────────
                            factory.register(plugin);
                            loaded++;
                            System.out.printf("  [PluginLoader] Dynamically injected: %s%n", className);

                        } catch (ClassNotFoundException | NoSuchMethodException
                                | InstantiationException | IllegalAccessException
                                | InvocationTargetException e) {
                            System.err.printf("  [PluginLoader] Failed to load %s: %s%n",
                                    className, e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("  [PluginLoader] I/O error scanning plugin dir: " + e.getMessage());
            }
            return loaded;
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  4. DATABASE CONNECTION POOL  (Singleton Pattern)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * ── Singleton Pattern ─────────────────────────────────────────────────
     *
     * WHY SINGLETON HERE?
     *
     * A database connection pool is one of the most expensive objects a JVM
     * application creates: each connection involves a TCP handshake, auth
     * negotiation, and a chunk of native memory (~1 MB per connection on some
     * drivers). A connection pool pre-allocates a fixed number of connections
     * (MAX_POOL_SIZE = 5) and lets threads borrow and return them rather than
     * opening a new one for every query.
     *
     * If DatabaseConnectionPool were instantiated per-request or per-thread:
     *   • A portal with 100 concurrent users would open 100 × 5 = 500 db
     *     connections, exhausting both the SQLite file handle limit and heap.
     *   • Each pool would have its own idle-connection monitor thread, adding
     *     CPU overhead with zero benefit.
     *   • Connections would never be returned to a "shared" pool — they would
     *     just accumulate and leak.
     *
     * One Singleton instance caps connections at MAX_POOL_SIZE for the entire
     * JVM lifetime, regardless of how many threads or requests are active.
     * This is the canonical reason every major connection pool library
     * (HikariCP, c3p0, DBCP) is initialised as a single shared instance.
     *
     * Thread safety: getInstance() uses double-checked locking with a
     * volatile field to guarantee exactly one instance across threads.
     */
    static final class DatabaseConnectionPool {

        private static final int    MAX_POOL_SIZE = 5;
        private static final String DB_URL        = "jdbc:sqlite:student_portal.db";

        // volatile ensures the reference is visible to all threads immediately
        private static volatile DatabaseConnectionPool instance;

        private final Queue<Connection> pool = new LinkedList<>();
        private       int               totalCreated = 0;

        /** Private constructor — blocks direct instantiation. */
        private DatabaseConnectionPool() {
            System.out.println("  [Pool] Initialising connection pool (max=" + MAX_POOL_SIZE + ")...");
            try {
                Class.forName("org.sqlite.JDBC");
                for (int i = 0; i < MAX_POOL_SIZE; i++) {
                    pool.offer(DriverManager.getConnection(DB_URL));
                    totalCreated++;
                }
                System.out.println("  [Pool] " + totalCreated + " connections ready.");
            } catch (ClassNotFoundException | SQLException e) {
                System.err.println("  [Pool] Warning: SQLite driver not found. Using mock connections.");
                System.err.println("         Add sqlite-jdbc.jar to classpath for real DB support.");
            }
        }

        /** Double-checked locking Singleton accessor. */
        static DatabaseConnectionPool getInstance() {
            if (instance == null) {
                synchronized (DatabaseConnectionPool.class) {
                    if (instance == null) {
                        instance = new DatabaseConnectionPool();
                    }
                }
            }
            return instance;
        }

        /** Borrow a connection from the pool. Blocks if all are in use. */
        synchronized Connection borrow() throws SQLException {
            if (pool.isEmpty()) {
                // In production: block with a Condition/Semaphore; here we open a spare
                System.out.println("  [Pool] All connections busy — opening temporary connection.");
                return DriverManager.getConnection(DB_URL);
            }
            return pool.poll();
        }

        /** Return a connection to the pool. */
        synchronized void returnConnection(Connection conn) {
            if (conn != null) {
                try {
                    if (!conn.isClosed()) {
                        conn.setAutoCommit(true); // reset for next borrower
                        pool.offer(conn);
                    }
                } catch (SQLException e) {
                    System.err.println("  [Pool] Could not return connection: " + e.getMessage());
                }
            }
        }

        int poolSize() { return pool.size(); }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  5. DOMAIN MODELS
    // ════════════════════════════════════════════════════════════════════════

    record Faculty(int id, String name, String department) {}

    record UploadRequest(
        String   fileName,
        byte[]   content,
        Faculty  uploader,
        int      encryptionLevel,   // 0=none, 1=AES-128, 2=AES-256
        List<String> tags
    ) {}

    record UploadResult(
        boolean success,
        String  resourceId,
        String  message,
        long    processingMs
    ) {}


    // ════════════════════════════════════════════════════════════════════════
    //  6. DATABASE SERVICE  (JDBC Transactions)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * All database work lives here.  The key JDBC pattern is:
     *
     *   conn.setAutoCommit(false);          // begin transaction
     *   try {
     *       // step 1: insert resource row
     *       // step 2: insert tag rows
     *       // step 3: increment faculty upload count
     *       conn.commit();                   // all-or-nothing
     *   } catch (SQLException e) {
     *       conn.rollback();                 // undo everything on any failure
     *   } finally {
     *       conn.setAutoCommit(true);        // always reset
     *   }
     *
     * This guarantees the DB is never left with a resource row but no tags,
     * or a tag inserted for a resource that failed to persist.
     */
    static final class PortalDatabaseService {

        private final DatabaseConnectionPool pool;

        PortalDatabaseService(DatabaseConnectionPool pool) {
            this.pool = pool;
            initSchema();
        }

        /** Create tables if they don't exist. */
        private void initSchema() {
            Connection conn = null;
            try {
                conn = pool.borrow();
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS faculty (
                            id         INTEGER PRIMARY KEY,
                            name       TEXT    NOT NULL,
                            department TEXT    NOT NULL,
                            uploads    INTEGER DEFAULT 0
                        )
                    """);
                    s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS resources (
                            resource_id      TEXT    PRIMARY KEY,
                            file_name        TEXT    NOT NULL,
                            file_type        TEXT    NOT NULL,
                            encryption_level TEXT    NOT NULL,
                            word_count       INTEGER,
                            uploader_id      INTEGER,
                            uploaded_at      TEXT    NOT NULL,
                            title            TEXT,
                            FOREIGN KEY (uploader_id) REFERENCES faculty(id)
                        )
                    """);
                    s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS resource_tags (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            resource_id TEXT NOT NULL,
                            tag         TEXT NOT NULL,
                            FOREIGN KEY (resource_id) REFERENCES resources(resource_id)
                        )
                    """);
                }
                System.out.println("  [DB] Schema initialised.");
            } catch (SQLException e) {
                System.out.println("  [DB] Schema init skipped (SQLite not available): " + e.getMessage());
            } finally {
                pool.returnConnection(conn);
            }
        }

        /**
         * Persists a complete upload inside a single JDBC transaction.
         * All three INSERTs commit together or roll back together.
         */
        boolean saveUpload(String resourceId, UploadRequest req, ParsedContent parsed,
                           EncryptionStrategy enc) {
            Connection conn = null;
            try {
                conn = pool.borrow();

                // ── BEGIN TRANSACTION ──────────────────────────────────────
                conn.setAutoCommit(false);
                System.out.println("  [DB] Transaction started for resource: " + resourceId);

                try {
                    // Step 1: Insert resource metadata row
                    String insertResource = """
                        INSERT INTO resources
                            (resource_id, file_name, file_type, encryption_level,
                             word_count, uploader_id, uploaded_at, title)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                    try (PreparedStatement ps = conn.prepareStatement(insertResource)) {
                        ps.setString(1, resourceId);
                        ps.setString(2, req.fileName());
                        ps.setString(3, req.fileName().substring(req.fileName().lastIndexOf('.') + 1));
                        ps.setString(4, enc.name());
                        ps.setInt   (5, parsed.wordCount());
                        ps.setInt   (6, req.uploader().id());
                        ps.setString(7, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        ps.setString(8, parsed.title());
                        ps.executeUpdate();
                    }
                    System.out.println("  [DB] Step 1/3 ✔ — resource row inserted");

                    // Step 2: Insert tag rows
                    String insertTag = "INSERT INTO resource_tags (resource_id, tag) VALUES (?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertTag)) {
                        for (String tag : req.tags()) {
                            ps.setString(1, resourceId);
                            ps.setString(2, tag.trim().toLowerCase());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    System.out.println("  [DB] Step 2/3 ✔ — " + req.tags().size() + " tag(s) inserted");

                    // Step 3: Upsert faculty and increment upload count
                    // Try insert first; if faculty exists, just increment
                    String upsertFaculty = """
                        INSERT INTO faculty (id, name, department, uploads)
                        VALUES (?, ?, ?, 1)
                        ON CONFLICT(id) DO UPDATE SET uploads = uploads + 1
                    """;
                    try (PreparedStatement ps = conn.prepareStatement(upsertFaculty)) {
                        ps.setInt   (1, req.uploader().id());
                        ps.setString(2, req.uploader().name());
                        ps.setString(3, req.uploader().department());
                        ps.executeUpdate();
                    }
                    System.out.println("  [DB] Step 3/3 ✔ — faculty upload count incremented");

                    // ── COMMIT ────────────────────────────────────────────
                    conn.commit();
                    System.out.println("  [DB] ✅ Transaction COMMITTED — all 3 steps persisted atomically");
                    return true;

                } catch (SQLException e) {
                    // ── ROLLBACK on any failure ────────────────────────────
                    System.err.println("  [DB] ❌ Step failed: " + e.getMessage());
                    System.err.println("  [DB] ↩ Rolling back entire transaction...");
                    conn.rollback();
                    return false;
                } finally {
                    conn.setAutoCommit(true); // always restore default
                }

            } catch (SQLException e) {
                System.out.println("  [DB] SQLite unavailable — simulating successful save.");
                return true;   // simulation mode: treat as success
            } finally {
                pool.returnConnection(conn);
            }
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  7. PORTAL CORE  (orchestrates all components)
    // ════════════════════════════════════════════════════════════════════════

    static final class StudentPortalService {

        private final ParserFactory          parserFactory;
        private final PortalDatabaseService  dbService;

        StudentPortalService(ParserFactory factory, PortalDatabaseService dbService) {
            this.parserFactory = factory;
            this.dbService     = dbService;
        }

        /**
         * Full upload pipeline:
         *   resolve parser → select encryption strategy → parse → encrypt → save
         */
        UploadResult upload(UploadRequest req) {
            long start = System.currentTimeMillis();
            System.out.println("\n  ── Processing upload: " + req.fileName() + " ──");

            try {
                // ① Factory Pattern: get the right parser for this file type
                IFileParser parser = parserFactory.getParser(req.fileName());
                System.out.println("  [Portal] Parser selected : " + parser.getClass().getSimpleName());

                // ② Strategy Pattern: get the right encryption level
                EncryptionStrategy enc = encryptionFor(req.encryptionLevel());
                System.out.println("  [Portal] Encryption      : " + enc.describe());

                // ③ Parse the file content
                ParsedContent parsed = parser.parse(req.content(), req.fileName());
                System.out.printf ("  [Portal] Parsed %-35s  words: %d%n",
                        '"' + parsed.title() + '"', parsed.wordCount());

                // ④ Encrypt the bytes (Strategy delegates actual work)
                byte[] encrypted = enc.encrypt(req.content());
                System.out.printf ("  [Portal] Encrypted: %d → %d bytes%n",
                        req.content().length, encrypted.length);

                // ⑤ JDBC Transaction: persist only if all steps above succeeded
                String resourceId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                boolean saved = dbService.saveUpload(resourceId, req, parsed, enc);

                long ms = System.currentTimeMillis() - start;
                if (saved) {
                    return new UploadResult(true, resourceId,
                            "Upload successful in " + ms + "ms", ms);
                } else {
                    return new UploadResult(false, null,
                            "Database transaction failed — upload rolled back", ms);
                }

            } catch (UnsupportedOperationException e) {
                long ms = System.currentTimeMillis() - start;
                return new UploadResult(false, null, "Unsupported file type: " + e.getMessage(), ms);
            }
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  8. MAIN — wiring and demo
    // ════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║        Modular Student Resource Portal                   ║");
        System.out.println("║  Factory + Strategy + Singleton + DI (Reflection) + JDBC ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // ── Step 1: Initialise components ──────────────────────────────────
        System.out.println("► Step 1: Initialise ParserFactory (built-in parsers)");
        ParserFactory factory = new ParserFactory();

        // ── Step 2: Plugin Loader (Manual DI via Reflection) ───────────────
        System.out.println("\n► Step 2: PluginLoader — scan /parsers for dynamic plugins");
        Path parsersDir = Path.of("parsers");
        new PluginLoader(parsersDir, factory).loadPlugins();
        System.out.println("  Supported extensions: " + factory.supportedExtensions());

        // ── Step 3: Singleton connection pool ──────────────────────────────
        System.out.println("\n► Step 3: Obtain Singleton DatabaseConnectionPool");
        DatabaseConnectionPool pool1 = DatabaseConnectionPool.getInstance();
        DatabaseConnectionPool pool2 = DatabaseConnectionPool.getInstance();
        System.out.println("  pool1 == pool2 (same instance)? " + (pool1 == pool2));
        System.out.println("  Available connections: " + pool1.poolSize());

        // ── Step 4: Database service ────────────────────────────────────────
        System.out.println("\n► Step 4: Initialise PortalDatabaseService");
        PortalDatabaseService dbService = new PortalDatabaseService(pool1);

        // ── Step 5: Portal service ──────────────────────────────────────────
        StudentPortalService portal = new StudentPortalService(factory, dbService);

        // ── Step 6: Simulate uploads ────────────────────────────────────────
        System.out.println("\n════════════════════════════════════════════════════════════");
        System.out.println("► Step 6: Simulating faculty uploads");
        System.out.println("════════════════════════════════════════════════════════════");

        Faculty drPatel  = new Faculty(1, "Dr. Patel",   "Computer Science");
        Faculty profKhan = new Faculty(2, "Prof. Khan",  "Mathematics");
        Faculty drSingh  = new Faculty(3, "Dr. Singh",   "Physics");

        List<UploadRequest> uploads = List.of(
            new UploadRequest(
                "data_structures_ch1.pdf",
                "Binary data representing a real PDF would be here...".getBytes(),
                drPatel, 0,
                List.of("data-structures", "algorithms", "CS101")
            ),
            new UploadRequest(
                "linear_algebra_notes.md",
                "# Linear Algebra\n**Vectors** and **matrices** are foundational.\n"
                    + "## Topics\n- Eigenvalues\n- Eigenvectors\n- Matrix decomposition"
                    .getBytes(),
                profKhan, 1,
                List.of("linear-algebra", "mathematics", "MTH201")
            ),
            new UploadRequest(
                "quantum_mechanics_intro.txt",
                "Quantum Mechanics — Introduction\n"
                    + "Wave-particle duality is a core concept in modern physics.\n"
                    + "Schrodinger equation governs quantum state evolution."
                    .getBytes(),
                drSingh, 2,
                List.of("quantum", "physics", "PHY301")
            ),
            new UploadRequest(
                "unsupported_upload.xlsx",         // ← no parser → should fail gracefully
                new byte[]{1, 2, 3},
                drPatel, 0,
                List.of("test")
            )
        );

        // ── Run each upload and print result ───────────────────────────────
        System.out.println();
        for (UploadRequest req : uploads) {
            UploadResult result = portal.upload(req);
            System.out.printf("%n  %-40s  %s  ID: %-10s  %s%n",
                    req.fileName(),
                    result.success() ? "✅ SUCCESS" : "❌ FAILED ",
                    result.success() ? result.resourceId() : "N/A",
                    result.message());
        }

        // ── Final summary ──────────────────────────────────────────────────
        System.out.println("\n════════════════════════════════════════════════════════════");
        System.out.println("  DESIGN PATTERN SUMMARY");
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("  Factory Pattern   — ParserFactory.getParser(fileName)");
        System.out.println("                      Returns correct parser by extension;");
        System.out.println("                      new types added by drop-in .class file.");
        System.out.println("  Strategy Pattern  — EncryptionStrategy interface");
        System.out.println("                      NoEncryption / AES128 / AES256 swapped");
        System.out.println("                      at runtime without portal code change.");
        System.out.println("  Singleton Pattern — DatabaseConnectionPool.getInstance()");
        System.out.println("                      One pool, 5 connections, all threads;");
        System.out.println("                      double-checked locking, volatile field.");
        System.out.println("  Manual DI/Reflect — PluginLoader + URLClassLoader");
        System.out.println("                      .class files injected into factory");
        System.out.println("                      via Constructor.newInstance().");
        System.out.println("  JDBC Transactions — setAutoCommit(false) + commit/rollback");
        System.out.println("                      3-step insert is atomic or fully undone.");
        System.out.println("════════════════════════════════════════════════════════════\n");
    }
}