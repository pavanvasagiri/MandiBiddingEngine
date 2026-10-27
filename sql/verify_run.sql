-- Post-run verification of committed transactions in mandi_db.
SELECT '== TABLE COUNTS ==' AS section;
SELECT (SELECT COUNT(*) FROM Crops) AS crops,
       (SELECT COUNT(*) FROM Farmers) AS farmers,
       (SELECT COUNT(*) FROM Buyers) AS buyers,
       (SELECT COUNT(*) FROM Auction_Lots) AS lots,
       (SELECT COUNT(*) FROM Bids) AS bids,
       (SELECT COUNT(*) FROM Settlements) AS settlements,
       (SELECT COUNT(*) FROM Price_History) AS price_rows;

SELECT '== LOT STATUS ==' AS section;
SELECT status, COUNT(*) AS total FROM Auction_Lots GROUP BY status;

SELECT '== BID STATUS ==' AS section;
SELECT status, anomaly_flag, COUNT(*) AS total FROM Bids GROUP BY status, anomaly_flag;

SELECT '== MONEY INTEGRITY (0 rows = perfect) ==' AS section;
SELECT settlement_id FROM Settlements
WHERE buyer_debit <> gross_amount + mandi_tax
   OR mandi_tax <> ROUND(gross_amount * 0.02, 2)
   OR farmer_payout <> gross_amount;

SELECT '== CLEARING PRICE RULE (0 rows = perfect: price=(bid+reserve)/2, bid>=reserve) ==' AS section;
SELECT s.settlement_id
FROM Settlements s
JOIN Bids b ON b.bid_id = s.bid_id
JOIN Auction_Lots l ON l.lot_id = s.lot_id
WHERE s.clearing_price_per_quintal <> ROUND((b.bid_price_per_quintal + l.reserve_price_per_quintal) / 2, 2)
   OR b.bid_price_per_quintal < l.reserve_price_per_quintal;

SELECT '== LOT FILL INTEGRITY (0 rows = perfect) ==' AS section;
SELECT lot_id FROM Auction_Lots
WHERE filled_quantity <> quantity_quintals OR status <> 'SETTLED';

SELECT '== ESCROW RECONCILIATION ==' AS section;
SELECT ROUND(SUM(buyer_debit), 2) AS total_debited FROM Settlements;
SELECT ROUND(SUM(escrow_balance), 2) AS escrow_now FROM Buyers;
SELECT ROUND((SELECT COUNT(*) FROM Buyers) * 3000000 - (SELECT COALESCE(SUM(buyer_debit),0) FROM Settlements), 2) AS expected_escrow,
       (SELECT ROUND(SUM(escrow_balance), 2) FROM Buyers) AS actual_escrow;

SELECT '== 7-DAY ROLLING AVERAGE (window fn, last 2 days per crop) ==' AS section;
SELECT crop_id, trade_date, avg_price_per_quintal AS daily_avg,
       ROUND(AVG(avg_price_per_quintal) OVER (
           PARTITION BY crop_id ORDER BY trade_date
           ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS rolling_7d
FROM Price_History
WHERE trade_date >= CURDATE() - INTERVAL 2 DAY
ORDER BY crop_id, trade_date;

SELECT '== SAMPLE SETTLEMENTS ==' AS section;
SELECT settlement_id, lot_id, bid_id, buyer_id, farmer_id, crop_id,
       clearing_price_per_quintal, quantity_quintals, gross_amount, mandi_tax, buyer_debit
FROM Settlements ORDER BY settlement_id LIMIT 5;
