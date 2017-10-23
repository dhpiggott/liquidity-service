CREATE DATABASE liquidity_analytics;

USE liquidity_analytics;

CREATE TABLE zones (
  zone_id VARCHAR(36) NOT NULL,
  modified TIMESTAMP NULL,
  equity_account_id VARCHAR(36) NOT NULL,
  created TIMESTAMP NOT NULL,
  expires TIMESTAMP NOT NULL,
  metadata JSON NULL,
  PRIMARY KEY (zone_id)
);

CREATE TABLE zone_name_changes (
  zone_id VARCHAR(36) NOT NULL,
  change_id INT NOT NULL AUTO_INCREMENT,
  changed TIMESTAMP NOT NULL,
  name VARCHAR(160) NULL,
  PRIMARY KEY (change_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE members (
  zone_id CHAR(36) NOT NULL,
  member_id VARCHAR(36) NOT NULL,
  created TIMESTAMP NOT NULL,
  PRIMARY KEY (zone_id, member_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE member_updates (
  zone_id CHAR(36) NOT NULL,
  member_id VARCHAR(36) NOT NULL,
  update_id INT NOT NULL AUTO_INCREMENT,
  updated TIMESTAMP NOT NULL,
  name VARCHAR(160) NULL,
  metadata JSON NULL,
  PRIMARY KEY (update_id),
  INDEX (zone_id, member_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
  FOREIGN KEY (zone_id, member_id) REFERENCES members(zone_id, member_id)
);

CREATE TABLE member_owners (
  update_id INT NOT NULL,
  public_key BLOB NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  PRIMARY KEY (update_id),
  FOREIGN KEY (update_id) REFERENCES member_updates(update_id)
);

CREATE TABLE accounts (
  zone_id CHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  created TIMESTAMP NOT NULL,
  balance TEXT NOT NULL,
  PRIMARY KEY (zone_id, account_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE account_updates (
  zone_id CHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  update_id INT NOT NULL AUTO_INCREMENT,
  updated TIMESTAMP NOT NULL,
  name VARCHAR(160) NULL,
  metadata JSON NULL,
  PRIMARY KEY (update_id),
  INDEX (zone_id, account_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
  FOREIGN KEY (zone_id, account_id) REFERENCES accounts(zone_id, account_id)
);

CREATE TABLE account_owners (
  update_id INT NOT NULL,
  member_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (update_id),
  FOREIGN KEY (update_id) REFERENCES account_updates(update_id)
);

CREATE TABLE transactions (
  zone_id CHAR(36) NOT NULL,
  transaction_id VARCHAR(36) NOT NULL,
  `from` VARCHAR(36) NOT NULL,
  `to` VARCHAR(36) NOT NULL,
  `value` TEXT NOT NULL,
  creator VARCHAR(36) NOT NULL,
  created TIMESTAMP NOT NULL,
  description VARCHAR(160) NULL,
  metadata JSON NULL,
  PRIMARY KEY (zone_id, transaction_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
  FOREIGN KEY (zone_id, `from`) REFERENCES accounts(zone_id, account_id),
  FOREIGN KEY (zone_id, `to`) REFERENCES accounts(zone_id, account_id),
  FOREIGN KEY (zone_id, creator) REFERENCES members(zone_id, member_id)
);

CREATE TABLE client_sessions (
  zone_id CHAR(36) NOT NULL,
  session_id INT NOT NULL AUTO_INCREMENT,
  remote_address VARCHAR(45) NULL,
  actor_ref VARCHAR(100) NOT NULL,
  public_key BLOB NULL,
  fingerprint CHAR(64) NULL,
  joined TIMESTAMP NOT NULL,
  quit TIMESTAMP NULL,
  PRIMARY KEY (session_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE journal_sequence_numbers (
  zone_id VARCHAR(36) NOT NULL,
  sequence_number INT NOT NULL,
  PRIMARY KEY (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);