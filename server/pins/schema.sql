-- Одна отметка на установку приложения и адрес (повторная отметка заменяет прежнюю).
CREATE TABLE IF NOT EXISTS pins (
  install TEXT NOT NULL,
  region  TEXT NOT NULL,
  address TEXT NOT NULL,
  lon     REAL NOT NULL,
  lat     REAL NOT NULL,
  ip_hash TEXT NOT NULL,
  app     TEXT,
  created INTEGER NOT NULL,
  PRIMARY KEY (install, region, address)
);
CREATE INDEX IF NOT EXISTS pins_region ON pins(region, address);
CREATE INDEX IF NOT EXISTS pins_install_day ON pins(install, created);
CREATE INDEX IF NOT EXISTS pins_ip_day ON pins(ip_hash, created);

-- Свежие графики от сборщика (GitHub Actions): приложение читает их отсюда без задержки CDN.
CREATE TABLE IF NOT EXISTS files (
  path    TEXT PRIMARY KEY,
  body    TEXT NOT NULL,
  updated INTEGER NOT NULL
);

-- Личный Telegram-бот: владелец, webhook, состояние тревог.
CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

-- Отзывы из приложения (копия того, что ушло в Telegram; sent=0 — бот ещё не был настроен).
CREATE TABLE IF NOT EXISTS feedback (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  install TEXT NOT NULL,
  ip_hash TEXT NOT NULL,
  text    TEXT NOT NULL,
  contact TEXT,
  app     TEXT,
  region  TEXT,
  device  TEXT,
  created INTEGER NOT NULL,
  sent    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS feedback_install_day ON feedback(install, created);
CREATE INDEX IF NOT EXISTS feedback_ip_day ON feedback(ip_hash, created);
