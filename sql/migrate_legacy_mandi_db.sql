-- One-time upgrade for the legacy schema in ../mandi_db.sql.
-- It preserves existing farmers, buyers, lots, bids, and settlements.
-- Take a backup before running: mysqldump -u root -p mandi_db > mandi_db_backup.sql
-- Run only after the legacy dump has been imported. Do not run against a fresh database.

ALTER TABLE Farmers
    ADD COLUMN wallet_balance DECIMAL(30,2) NOT NULL DEFAULT 0.00;

ALTER TABLE Auction_Lots
    MODIFY COLUMN quantity_quintals DECIMAL(18,4) NOT NULL,
    ADD COLUMN original_quantity_quintals DECIMAL(18,4) NULL,
    ADD COLUMN remaining_quantity_quintals DECIMAL(18,4) NULL;

UPDATE Auction_Lots
   SET original_quantity_quintals = quantity_quintals,
       remaining_quantity_quintals = CASE WHEN status = 'SETTLED' THEN 0.0000 ELSE quantity_quintals END
 WHERE original_quantity_quintals IS NULL
    OR remaining_quantity_quintals IS NULL;

ALTER TABLE Auction_Lots
    MODIFY COLUMN original_quantity_quintals DECIMAL(18,4) NOT NULL,
    MODIFY COLUMN remaining_quantity_quintals DECIMAL(18,4) NOT NULL,
    MODIFY COLUMN status ENUM('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'EXPIRED', 'CANCELLED') NOT NULL DEFAULT 'OPEN';

-- Legacy Settlements allowed only one settlement per lot. Partial fills require many.
ALTER TABLE Settlements ADD INDEX idx_settlements_lot_id (lot_id);
ALTER TABLE Settlements DROP INDEX lot_id;
ALTER TABLE Settlements
    ADD COLUMN quantity_quintals DECIMAL(18,4) NULL AFTER winning_bid_per_quintal;

-- Historical settlements were full-lot settlements under the legacy model.
UPDATE Settlements AS settlement
INNER JOIN Auction_Lots AS lot ON lot.lot_id = settlement.lot_id
   SET settlement.quantity_quintals = lot.original_quantity_quintals
 WHERE settlement.quantity_quintals IS NULL;

ALTER TABLE Settlements
    MODIFY COLUMN quantity_quintals DECIMAL(18,4) NOT NULL;
