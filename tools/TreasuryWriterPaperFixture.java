package smoke;

import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.module.treasury.NationTransferService;
import dev.starcore.starcore.module.treasury.TreasuryService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;

/** Creates only a fixture-owned lock table. All treasury changes use real public services. */
public final class TreasuryWriterPaperFixture extends ProductionStackPaperFixture {
    private static final String TABLE = "isolated_treasury_writer_fixture";
    private static final String TRANSFER = "isolated-treasury-writer-transfer";
    private static final long HOLD_MILLIS = 750;

    private DatabaseService database() throws Exception {
        Object core = Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
        Object context = core.getClass().getMethod("context").invoke(core);
        return (DatabaseService) context.getClass().getMethod("databaseService").invoke(context);
    }

    private Map<String,String> snapshot(DatabaseService database) throws Exception {
        var values = new TreeMap<String,String>();
        try (var connection = database.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT property_key,property_value FROM starcore_treasury_state ORDER BY property_key")) {
            while (rows.next()) values.put(rows.getString(1), rows.getString(2));
        }
        return values;
    }

    private NationTransferService.TransferRequest request() {
        return new NationTransferService.TransferRequest(TRANSFER, country("A"), country("B"),
                new BigDecimal("7.25"), NationTransferService.TransferCategory.OTHER,
                "Explicit isolated writer contention acceptance");
    }

    private void contend(String operation, Callable<?> mutation) throws Exception {
        require(Bukkit.isPrimaryThread(), "Treasury mutation must use the Paper owner thread");
        var database = database();
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            require(connection.getMetaData().getDatabaseProductName().equals("SQLite"), "SQLite fixture required");
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (operation TEXT PRIMARY KEY)");
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
                require(rows.next() && rows.getString(1).equalsIgnoreCase("wal"), "Actual WAL mode required");
            }
        }
        var before = snapshot(database);
        var held = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var releasedAt = new AtomicLong();
        long started = 0, returned = 0;
        Object result = null;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> {
                try (Connection connection = database.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var insert = connection.prepareStatement("INSERT INTO " + TABLE + " VALUES (?)")) {
                        insert.setString(1, operation); insert.executeUpdate();
                    }
                    held.countDown();
                    require(entered.await(10, TimeUnit.SECONDS), "Owner did not enter treasury operation");
                    Thread.sleep(HOLD_MILLIS);
                    connection.commit(); releasedAt.set(System.nanoTime());
                    return null;
                }
            });
            try {
                require(held.await(10, TimeUnit.SECONDS), "Independent writer did not acquire lock");
                started = System.nanoTime(); entered.countDown();
                result = mutation.call(); returned = System.nanoTime();
            } finally {
                entered.countDown(); holder.get(15, TimeUnit.SECONDS);
            }
        }
        var after = snapshot(database);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(returned - started);
        long heldMillis = TimeUnit.NANOSECONDS.toMillis(releasedAt.get() - started);
        log("TREASURY_WAIT", Map.of("operation", operation, "ownerThread", Bukkit.isPrimaryThread(),
                "holdMillis", HOLD_MILLIS, "writerReleasedAfterMillis", heldMillis,
                "elapsedMillis", elapsed, "returnedAfterRelease", returned >= releasedAt.get(),
                "result", result, "before", before, "after", after));
        require(heldMillis >= HOLD_MILLIS && elapsed >= HOLD_MILLIS && returned >= releasedAt.get(),
                "Treasury did not wait for the unrelated writer");
        require(Long.parseLong(after.get("__starcore_revision__")) == Long.parseLong(before.get("__starcore_revision__")) + 1,
                "Treasury write must commit exactly one revision");
    }

    private CompletionStage<?> mutate(String phase) { return main(() -> {
        TreasuryService treasury = service(TreasuryService.class);
        if (phase.equals("first")) {
            require(!getConfig().getBoolean("treasuryWriter.first", false), "Refusing to repeat explicit initial deposit");
            contend("deposit-first", () -> {
                treasury.deposit(country("A"), new BigDecimal("12.50")); return "DEPOSITED";
            });
            contend("transfer", () -> {
                var result = service(NationTransferService.class).transfer(request());
                require(result.status() == NationTransferService.TransferStatus.APPLIED, "Real transfer failed");
                return result.status().name();
            });
            getConfig().set("treasuryWriter.first", true); saveConfig();
        } else if (phase.equals("second")) {
            require(getConfig().getBoolean("treasuryWriter.first", false)
                    && !getConfig().getBoolean("treasuryWriter.second", false), "Unexpected restored deposit state");
            contend("deposit-restored", () -> {
                treasury.deposit(country("C"), new BigDecimal("2.75")); return "DEPOSITED";
            });
            getConfig().set("treasuryWriter.second", true); saveConfig();
        } else throw new IllegalArgumentException(phase);
        return null;
    }); }

    private CompletionStage<?> check(String phase) { return main(() -> {
        var treasury = service(TreasuryService.class);
        var balances = new TreeMap<String,String>();
        for (String who : List.of("A", "B", "C")) {
            var expected = new BigDecimal(switch(who) {
                case "A" -> "1000005.25";
                case "B" -> "1000007.25";
                default -> phase.equals("first") ? "1000000.00" : "1000002.75";
            });
            var actual = treasury.balance(country(who));
            require(actual.compareTo(expected) == 0, "Unexpected treasury balance for " + who);
            balances.put(who, actual.toPlainString());
        }
        var receipt = service(NationTransferService.class).transferReceipt(TRANSFER).orElseThrow();
        require(receipt.payerId().equals(country("A")) && receipt.receiverId().equals(country("B"))
                && receipt.amount().compareTo(new BigDecimal("7.25")) == 0, "Unexpected original transfer receipt");
        log("TREASURY_CHECK", Map.of("phase", phase, "balances", balances,
                "authority", treasury.authorityDomainId(), "receipt", receipt, "snapshot", snapshot(database())));
        return null;
    }); }

    private CompletionStage<?> replay() { return main(() -> {
        var database = database(); var before = snapshot(database);
        var result = service(NationTransferService.class).transfer(request());
        require(result.status() == NationTransferService.TransferStatus.ALREADY_APPLIED,
                "Transfer replay must recognize original receipt");
        var after = snapshot(database);
        require(after.equals(before), "Transfer replay changed SQL state or revision");
        log("TREASURY_REPLAY", Map.of("status", result.status().name(), "snapshot", after));
        return null;
    }); }

    @Override protected CompletionStage<?> extraAction(String action, String[] args) {
        return switch(action) {
            case "treasury-mutate" -> mutate(args[1]);
            case "treasury-check" -> check(args[1]);
            case "treasury-replay" -> replay();
            default -> super.extraAction(action, args);
        };
    }
}
