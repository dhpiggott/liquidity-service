CREATE DATABASE liquidity_administrators;

USE liquidity_administrators;

CREATE TABLE administrators (
  administrator_id INT NOT NULL AUTO_INCREMENT,
  public_key BLOB NOT NULL,
  PRIMARY KEY (administrator_id)
);
