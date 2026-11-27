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
