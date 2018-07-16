CREATE DATABASE liquidity_analytics;

USE liquidity_analytics;

CREATE TABLE zones (
  zone_id VARCHAR(36) NOT NULL,
  modified TIMESTAMP(3) NULL,
  equity_account_id VARCHAR(36) NOT NULL,
  created TIMESTAMP(3) NOT NULL,
  expires TIMESTAMP(3) NOT NULL,
  metadata JSON NULL,
  PRIMARY KEY (zone_id)
);

CREATE TABLE zone_name_changes (
  zone_id VARCHAR(36) NOT NULL,
  change_id INT NOT NULL AUTO_INCREMENT,
  changed TIMESTAMP(3) NOT NULL,
  name VARCHAR(160) NULL,
  PRIMARY KEY (change_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE zone_counts (
  `time` TIMESTAMP(3) NOT NULL,
  `count` INT NOT NULL,
  PRIMARY KEY (`time`)
);

CREATE TRIGGER zone_counts_trigger
  AFTER INSERT ON zones
  FOR EACH ROW
  INSERT INTO zone_counts(`time`, `count`)
  SELECT newer_zones.created, (SELECT COUNT(*) FROM zones as older_zones WHERE older_zones.created <= newer_zones.created)
    FROM zones AS newer_zones
    WHERE newer_zones.created >= new.created
  ON DUPLICATE KEY
  UPDATE `time` = VALUES(`time`), `count` = VALUES(`count`);

CREATE TABLE members (
  zone_id CHAR(36) NOT NULL,
  member_id VARCHAR(36) NOT NULL,
  created TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (zone_id, member_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE member_updates (
  zone_id CHAR(36) NOT NULL,
  member_id VARCHAR(36) NOT NULL,
  update_id INT NOT NULL AUTO_INCREMENT,
  updated TIMESTAMP(3) NOT NULL,
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

CREATE TABLE public_key_counts (
  `time` TIMESTAMP(3) NOT NULL,
  `count` INT NOT NULL,
  PRIMARY KEY (`time`)
);

CREATE TRIGGER public_key_counts_trigger
  AFTER INSERT ON member_owners
  FOR EACH ROW
  INSERT INTO public_key_counts(`time`, `count`)
  SELECT newer_member_updates.updated, (SELECT COUNT(DISTINCT member_owners.public_key) FROM member_updates AS older_member_updates JOIN member_owners ON member_owners.update_id = older_member_updates.update_id WHERE older_member_updates.updated <= newer_member_updates.updated)
    FROM member_updates AS newer_member_updates
    WHERE newer_member_updates.updated >= (SELECT updated FROM member_updates WHERE update_id = new.update_id)
  ON DUPLICATE KEY UPDATE `time` = VALUES(`time`), `count` = VALUES(`count`);

CREATE TABLE member_counts (
  `time` TIMESTAMP(3) NOT NULL,
  `count` INT NOT NULL,
  PRIMARY KEY (`time`)
);

CREATE TRIGGER member_counts_trigger
  AFTER INSERT ON members
  FOR EACH ROW
  INSERT INTO member_counts(`time`, `count`)
  SELECT newer_members.created, (SELECT COUNT(*) FROM members as older_members WHERE older_members.created <= newer_members.created)
    FROM members AS newer_members
    WHERE newer_members.created >= new.created
  ON DUPLICATE KEY UPDATE `time` = VALUES(`time`), `count` = VALUES(`count`);

CREATE TABLE accounts (
  zone_id CHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  created TIMESTAMP(3) NOT NULL,
  balance TEXT NOT NULL,
  PRIMARY KEY (zone_id, account_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE account_updates (
  zone_id CHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  update_id INT NOT NULL AUTO_INCREMENT,
  updated TIMESTAMP(3) NOT NULL,
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

CREATE TABLE account_counts (
  `time` TIMESTAMP(3) NOT NULL,
  `count` INT NOT NULL,
  PRIMARY KEY (`time`)
);

CREATE TRIGGER account_counts_trigger
  AFTER INSERT ON accounts
  FOR EACH ROW
  INSERT INTO account_counts(`time`, `count`)
  SELECT newer_accounts.created, (SELECT COUNT(*) FROM accounts as older_accounts WHERE older_accounts.created <= newer_accounts.created)
    FROM accounts AS newer_accounts
    WHERE newer_accounts.created >= new.created
  ON DUPLICATE KEY UPDATE `time` = VALUES(`time`), `count` = VALUES(`count`);

CREATE TABLE transactions (
  zone_id CHAR(36) NOT NULL,
  transaction_id VARCHAR(36) NOT NULL,
  `from` VARCHAR(36) NOT NULL,
  `to` VARCHAR(36) NOT NULL,
  `value` TEXT NOT NULL,
  creator VARCHAR(36) NOT NULL,
  created TIMESTAMP(3) NOT NULL,
  description VARCHAR(160) NULL,
  metadata JSON NULL,
  PRIMARY KEY (zone_id, transaction_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
  FOREIGN KEY (zone_id, `from`) REFERENCES accounts(zone_id, account_id),
  FOREIGN KEY (zone_id, `to`) REFERENCES accounts(zone_id, account_id),
  FOREIGN KEY (zone_id, creator) REFERENCES members(zone_id, member_id)
);

CREATE TABLE transaction_counts (
  `time` TIMESTAMP(3) NOT NULL,
  `count` INT NOT NULL,
  PRIMARY KEY (`time`)
);

CREATE TRIGGER transaction_counts_trigger
  AFTER INSERT ON transactions
  FOR EACH ROW
  INSERT INTO transaction_counts(`time`, `count`)
  SELECT newer_transactions.created, (SELECT COUNT(*) FROM transactions as older_transactions WHERE older_transactions.created <= newer_transactions.created)
    FROM transactions AS newer_transactions
    WHERE newer_transactions.created >= new.created
  ON DUPLICATE KEY UPDATE `time` = VALUES(`time`), `count` = VALUES(`count`);

CREATE TABLE client_sessions (
  zone_id CHAR(36) NOT NULL,
  session_id INT NOT NULL AUTO_INCREMENT,
  remote_address VARCHAR(45) NULL,
  actor_ref VARCHAR(100) NOT NULL,
  public_key BLOB NULL,
  fingerprint CHAR(64) NULL,
  joined TIMESTAMP(3) NOT NULL,
  quit TIMESTAMP(3) NULL,
  PRIMARY KEY (session_id),
  INDEX (zone_id),
  FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

CREATE TABLE tag_offsets (
  tag VARCHAR(10) NOT NULL,
  offset INT NOT NULL,
  PRIMARY KEY (tag)
);
