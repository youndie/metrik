package ru.workinprogress.metrik.server.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

// Миграции руками: ни Flyway, ни аналога на Kotlin/Native нет.
// Версия схемы живёт в PRAGMA user_version; новая миграция дописывается в конец списка и никогда
// не правится задним числом.

private val migrationV1 =
    listOf(
        """CREATE TABLE services (
id INTEGER PRIMARY KEY AUTOINCREMENT,
name TEXT NOT NULL,
created_at INTEGER NOT NULL
);""",
        """CREATE UNIQUE INDEX services_name ON services(name);""",
        """CREATE TABLE instances (
id INTEGER PRIMARY KEY AUTOINCREMENT,
service_id INTEGER NOT NULL,
instance_key TEXT NOT NULL,
release TEXT,
last_seen INTEGER NOT NULL,
last_window_seq INTEGER,
clock_skew INTEGER NOT NULL DEFAULT 0,
FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);""",
        """CREATE UNIQUE INDEX instances_service_key ON instances(service_id, instance_key);""",
        """CREATE TABLE deploys (
id INTEGER PRIMARY KEY AUTOINCREMENT,
service_id INTEGER NOT NULL,
instance_id INTEGER NOT NULL,
release TEXT NOT NULL,
first_seen INTEGER NOT NULL,
FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);""",
        """CREATE INDEX deploys_service_seen ON deploys(service_id, first_seen);""",
        // Инстансы слиты: instance_id здесь намеренно нет (см. docs/services/metrik-server.md).
        """CREATE TABLE route_windows (
service_id INTEGER NOT NULL,
window_start INTEGER NOT NULL,
method TEXT NOT NULL,
route TEXT NOT NULL,
status INTEGER NOT NULL,
count INTEGER NOT NULL,
sum_ms INTEGER NOT NULL,
max_ms INTEGER NOT NULL,
buckets TEXT NOT NULL,
PRIMARY KEY (service_id, window_start, method, route, status),
FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);""",
        """CREATE INDEX route_windows_service_time ON route_windows(service_id, window_start);""",
        """CREATE TABLE route_rollups (
service_id INTEGER NOT NULL,
granularity TEXT NOT NULL,
bucket_start INTEGER NOT NULL,
method TEXT NOT NULL,
route TEXT NOT NULL,
status INTEGER NOT NULL,
count INTEGER NOT NULL,
sum_ms INTEGER NOT NULL,
max_ms INTEGER NOT NULL,
buckets TEXT NOT NULL,
PRIMARY KEY (service_id, granularity, bucket_start, method, route, status),
FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);""",
        """CREATE INDEX route_rollups_service_time ON route_rollups(service_id, granularity, bucket_start);""",
        """CREATE TABLE system_windows (
instance_id INTEGER NOT NULL,
window_start INTEGER NOT NULL,
heap_used INTEGER NOT NULL,
heap_max INTEGER,
cpu_permille INTEGER NOT NULL,
threads INTEGER NOT NULL,
uptime INTEGER NOT NULL,
gc_count INTEGER,
gc_ms INTEGER,
PRIMARY KEY (instance_id, window_start),
FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
);""",
        """CREATE TABLE slow_samples (
id INTEGER PRIMARY KEY AUTOINCREMENT,
service_id INTEGER NOT NULL,
method TEXT NOT NULL,
route TEXT NOT NULL,
status INTEGER NOT NULL,
duration_ms INTEGER NOT NULL,
ts INTEGER NOT NULL,
FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);""",
        """CREATE INDEX slow_samples_service_ts ON slow_samples(service_id, ts);""",
        // Расписка о пакете: она же защита от повторной доставки, она же источник флага partial.
        """CREATE TABLE window_receipts (
service_id INTEGER NOT NULL,
instance_id INTEGER NOT NULL,
window_start INTEGER NOT NULL,
packet_index INTEGER NOT NULL,
packet_count INTEGER NOT NULL,
received_at INTEGER NOT NULL,
PRIMARY KEY (service_id, instance_id, window_start, packet_index)
);""",
        """CREATE INDEX window_receipts_service_window ON window_receipts(service_id, window_start);""",
        """CREATE TABLE alert_rules (
id INTEGER PRIMARY KEY AUTOINCREMENT,
service_id INTEGER,
rule_id TEXT NOT NULL,
threshold REAL NOT NULL,
min_count INTEGER NOT NULL,
windows INTEGER NOT NULL,
enabled INTEGER NOT NULL DEFAULT 1,
telegram_chat_id TEXT
);""",
        """CREATE UNIQUE INDEX alert_rules_scope ON alert_rules(IFNULL(service_id, -1), rule_id);""",
        """CREATE TABLE alert_states (
service_id INTEGER NOT NULL,
rule_id TEXT NOT NULL,
state TEXT NOT NULL,
since INTEGER NOT NULL,
last_notified_at INTEGER,
breaches INTEGER NOT NULL DEFAULT 0,
recoveries INTEGER NOT NULL DEFAULT 0,
PRIMARY KEY (service_id, rule_id),
FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);""",
        """CREATE TABLE alert_history (
id INTEGER PRIMARY KEY AUTOINCREMENT,
service_id INTEGER NOT NULL,
rule_id TEXT NOT NULL,
state TEXT NOT NULL,
at INTEGER NOT NULL,
detail TEXT
);""",
        """CREATE INDEX alert_history_at ON alert_history(at);""",
    )

private val allMigrations = listOf(migrationV1)

suspend fun ISQLite.migrateDb() {
    val current =
        fetchAll("PRAGMA user_version;")
            .getOrNull()
            ?.rows
            ?.getOrNull(0)
            ?.get(0)
            ?.asLong()
            ?.toInt()
            ?: 0

    if (current >= allMigrations.size) return

    for (version in (current + 1)..allMigrations.size) {
        allMigrations[version - 1].forEach { statement -> execute(statement).getOrThrow() }
        execute("PRAGMA user_version = $version;").getOrThrow()
    }
}
