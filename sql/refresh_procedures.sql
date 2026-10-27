-- Reinstalls only the application stored procedures. It does not alter tables or data.

DELIMITER //

DROP PROCEDURE IF EXISTS sp_settle_trade //
CREATE PROCEDURE sp_settle_trade(
    IN p_lot_id VARCHAR(100),
    IN p_buyer_id VARCHAR(100),
    IN p_farmer_id VARCHAR(100),
    IN p_price_per_quintal DECIMAL(18,2),
    IN p_quantity_quintals DECIMAL(18,4)
)
proc: BEGIN
    DECLARE v_farmer_id VARCHAR(100) DEFAULT NULL;
    DECLARE v_status VARCHAR(32) DEFAULT NULL;
    DECLARE v_remaining_quantity DECIMAL(18,4) DEFAULT NULL;
    DECLARE v_escrow_balance DECIMAL(30,2) DEFAULT NULL;
    DECLARE v_locked_farmer_id VARCHAR(100) DEFAULT NULL;
    DECLARE v_gross_total DECIMAL(30,2);
    DECLARE v_mandi_tax DECIMAL(30,2);
    DECLARE v_net_payout DECIMAL(30,2);
    DECLARE v_new_remaining_quantity DECIMAL(18,4);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_price_per_quintal IS NULL OR p_price_per_quintal <= 0 OR p_quantity_quintals IS NULL OR p_quantity_quintals <= 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Price and quantity must be positive';
    END IF;

    START TRANSACTION;
    SELECT farmer_id, status, remaining_quantity_quintals
      INTO v_farmer_id, v_status, v_remaining_quantity
      FROM Auction_Lots WHERE lot_id = p_lot_id FOR UPDATE;

    IF v_status IS NULL OR v_status NOT IN ('OPEN', 'PARTIALLY_SETTLED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Lot is not available for settlement';
    END IF;
    IF v_farmer_id <> p_farmer_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ask farmer does not own this lot';
    END IF;
    IF p_quantity_quintals > v_remaining_quantity THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Settlement quantity exceeds remaining lot quantity';
    END IF;

    SELECT escrow_balance INTO v_escrow_balance FROM Buyers WHERE buyer_id = p_buyer_id FOR UPDATE;
    IF v_escrow_balance IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Buyer not found';
    END IF;
    SELECT farmer_id INTO v_locked_farmer_id FROM Farmers WHERE farmer_id = v_farmer_id FOR UPDATE;
    IF v_locked_farmer_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Farmer not found';
    END IF;

    SET v_gross_total = ROUND(p_price_per_quintal * p_quantity_quintals, 2);
    SET v_mandi_tax = ROUND(v_gross_total * 0.02, 2);
    SET v_net_payout = v_gross_total - v_mandi_tax;
    IF v_escrow_balance < v_gross_total THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Insufficient buyer escrow';
    END IF;

    SET v_new_remaining_quantity = v_remaining_quantity - p_quantity_quintals;
    UPDATE Buyers SET escrow_balance = escrow_balance - v_gross_total WHERE buyer_id = p_buyer_id;
    UPDATE Farmers SET wallet_balance = wallet_balance + v_net_payout WHERE farmer_id = v_farmer_id;
    UPDATE Auction_Lots SET remaining_quantity_quintals = v_new_remaining_quantity,
        status = IF(v_new_remaining_quantity = 0, 'SETTLED', 'PARTIALLY_SETTLED') WHERE lot_id = p_lot_id;
    INSERT INTO Settlements (settlement_id, lot_id, winning_buyer_id, winning_bid_per_quintal, quantity_quintals, gross_total, mandi_tax_deducted, farmer_net_payout)
    VALUES (UUID(), p_lot_id, p_buyer_id, p_price_per_quintal, p_quantity_quintals, v_gross_total, v_mandi_tax, v_net_payout);
    COMMIT;
END //

DROP PROCEDURE IF EXISTS sp_list_settlements //
CREATE PROCEDURE sp_list_settlements()
BEGIN
    SELECT s.lot_id, s.winning_buyer_id, l.farmer_id, s.gross_total, s.mandi_tax_deducted, s.farmer_net_payout, s.settled_at
      FROM Settlements AS s INNER JOIN Auction_Lots AS l ON l.lot_id = s.lot_id
     ORDER BY s.settled_at DESC, s.settlement_id DESC;
END //

DELIMITER ;
