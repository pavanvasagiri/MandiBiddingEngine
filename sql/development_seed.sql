-- Development data only. Do not run this against a production database.
-- Run once: mysql -u root -p mandi_db < sql/development_seed.sql

INSERT INTO Farmers (farmer_id, wallet_balance)
VALUES ('FARMER_DEMO_01', 0.00);

INSERT INTO Buyers (buyer_id, escrow_balance)
VALUES ('BUYER_DEMO_01', 100000.00);

INSERT INTO Auction_Lots (lot_id, crop_id, farmer_id, original_quantity_quintals, remaining_quantity_quintals, status)
VALUES ('LOT_WHEAT_DEMO_01', 'CROP_WHEAT_01', 'FARMER_DEMO_01', 50.0000, 50.0000, 'OPEN');
