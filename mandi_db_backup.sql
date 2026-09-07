-- MySQL dump 10.13  Distrib 8.0.32, for macos13 (x86_64)
--
-- Host: localhost    Database: mandi_db
-- ------------------------------------------------------
-- Server version	8.0.32

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `Auction_Lots`
--

DROP TABLE IF EXISTS `Auction_Lots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `Auction_Lots` (
  `lot_id` varchar(36) NOT NULL,
  `farmer_id` varchar(36) NOT NULL,
  `crop_id` varchar(36) NOT NULL,
  `quality_grade` enum('GRADE_A','GRADE_B','GRADE_C') NOT NULL,
  `quantity_quintals` decimal(10,2) NOT NULL,
  `reserve_price_per_quintal` decimal(10,2) NOT NULL,
  `status` enum('OPEN','SETTLED','EXPIRED','CANCELLED') DEFAULT 'OPEN',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` timestamp NOT NULL,
  PRIMARY KEY (`lot_id`),
  KEY `farmer_id` (`farmer_id`),
  KEY `idx_auction_lots_crop_status` (`crop_id`,`status`),
  CONSTRAINT `auction_lots_ibfk_1` FOREIGN KEY (`farmer_id`) REFERENCES `Farmers` (`farmer_id`),
  CONSTRAINT `auction_lots_ibfk_2` FOREIGN KEY (`crop_id`) REFERENCES `Crops` (`crop_id`),
  CONSTRAINT `auction_lots_chk_1` CHECK ((`quantity_quintals` > 0.00))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `Auction_Lots`
--

LOCK TABLES `Auction_Lots` WRITE;
/*!40000 ALTER TABLE `Auction_Lots` DISABLE KEYS */;
INSERT INTO `Auction_Lots` VALUES ('LOT_WHEAT_101','FARMER_RAMESH','CROP_WHEAT_01','GRADE_A',50.00,2300.00,'SETTLED','2026-09-03 09:23:49','2026-09-04 09:23:49'),('LOT_WHEAT_102','FARMER_SURESH','CROP_WHEAT_01','GRADE_B',30.00,2280.00,'OPEN','2026-09-03 09:23:49','2026-09-04 09:23:49');
/*!40000 ALTER TABLE `Auction_Lots` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `Bids`
--

DROP TABLE IF EXISTS `Bids`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `Bids` (
  `bid_id` varchar(36) NOT NULL,
  `lot_id` varchar(36) NOT NULL,
  `buyer_id` varchar(36) NOT NULL,
  `bid_price_per_quintal` decimal(10,2) NOT NULL,
  `quantity_quintals` decimal(10,2) NOT NULL,
  `bid_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`bid_id`),
  KEY `lot_id` (`lot_id`),
  KEY `buyer_id` (`buyer_id`),
  CONSTRAINT `bids_ibfk_1` FOREIGN KEY (`lot_id`) REFERENCES `Auction_Lots` (`lot_id`),
  CONSTRAINT `bids_ibfk_2` FOREIGN KEY (`buyer_id`) REFERENCES `Buyers` (`buyer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `Bids`
--

LOCK TABLES `Bids` WRITE;
/*!40000 ALTER TABLE `Bids` DISABLE KEYS */;
/*!40000 ALTER TABLE `Bids` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `Buyers`
--

DROP TABLE IF EXISTS `Buyers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `Buyers` (
  `buyer_id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `phone` varchar(15) NOT NULL,
  `escrow_balance` decimal(12,2) NOT NULL DEFAULT '0.00',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`buyer_id`),
  UNIQUE KEY `phone` (`phone`),
  CONSTRAINT `buyers_chk_1` CHECK ((`escrow_balance` >= 0.00))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `Buyers`
--

LOCK TABLES `Buyers` WRITE;
/*!40000 ALTER TABLE `Buyers` DISABLE KEYS */;
INSERT INTO `Buyers` VALUES ('BUYER_TRADER_A','Agro Trader Alpha','9123456789',430550.00,'2026-09-03 09:23:49'),('BUYER_TRADER_B','Grain Merchant Beta','9123456790',500000.00,'2026-09-03 09:23:49');
/*!40000 ALTER TABLE `Buyers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `Crops`
--

DROP TABLE IF EXISTS `Crops`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `Crops` (
  `crop_id` varchar(36) NOT NULL,
  `crop_name` varchar(100) NOT NULL,
  `msp_price_per_quintal` decimal(10,2) NOT NULL,
  `grade_a_premium_pct` decimal(5,2) DEFAULT '0.00',
  `grade_c_discount_pct` decimal(5,2) DEFAULT '10.00',
  PRIMARY KEY (`crop_id`),
  UNIQUE KEY `crop_name` (`crop_name`),
  CONSTRAINT `crops_chk_1` CHECK ((`msp_price_per_quintal` > 0.00))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `Crops`
--

LOCK TABLES `Crops` WRITE;
/*!40000 ALTER TABLE `Crops` DISABLE KEYS */;
INSERT INTO `Crops` VALUES ('CROP_RICE_01','Paddy Rice (Dhan)',2183.00,0.00,10.00),('CROP_WHEAT_01','Wheat (Gehun)',2275.00,0.00,10.00);
/*!40000 ALTER TABLE `Crops` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `Farmers`
--

DROP TABLE IF EXISTS `Farmers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `Farmers` (
  `farmer_id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `phone` varchar(15) NOT NULL,
  `bank_account_no` varchar(20) NOT NULL,
  `ifsc_code` varchar(11) NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`farmer_id`),
  UNIQUE KEY `phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `Farmers`
--

LOCK TABLES `Farmers` WRITE;
/*!40000 ALTER TABLE `Farmers` DISABLE KEYS */;
INSERT INTO `Farmers` VALUES ('FARMER_RAMESH','Ramesh Kumar','9876543210','ACC1000123','SBIN0001234','2026-09-03 09:23:49'),('FARMER_SURESH','Suresh Patel','9876543211','ACC1000124','SBIN0001234','2026-09-03 09:23:49');
/*!40000 ALTER TABLE `Farmers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `Settlements`
--

DROP TABLE IF EXISTS `Settlements`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `Settlements` (
  `settlement_id` varchar(36) NOT NULL,
  `lot_id` varchar(36) NOT NULL,
  `winning_buyer_id` varchar(36) NOT NULL,
  `winning_bid_per_quintal` decimal(10,2) NOT NULL,
  `gross_total` decimal(12,2) NOT NULL,
  `mandi_tax_deducted` decimal(10,2) NOT NULL,
  `farmer_net_payout` decimal(12,2) NOT NULL,
  `settled_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`settlement_id`),
  UNIQUE KEY `lot_id` (`lot_id`),
  KEY `winning_buyer_id` (`winning_buyer_id`),
  KEY `idx_settlements_settled_at` (`settled_at` DESC),
  CONSTRAINT `settlements_ibfk_1` FOREIGN KEY (`lot_id`) REFERENCES `Auction_Lots` (`lot_id`),
  CONSTRAINT `settlements_ibfk_2` FOREIGN KEY (`winning_buyer_id`) REFERENCES `Buyers` (`buyer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `Settlements`
--

LOCK TABLES `Settlements` WRITE;
/*!40000 ALTER TABLE `Settlements` DISABLE KEYS */;
INSERT INTO `Settlements` VALUES ('ff617f19-27f4-47c0-a01e-e3256dbcbe0c','LOT_WHEAT_101','BUYER_TRADER_A',2315.00,69450.00,1389.00,68061.00,'2026-09-03 09:30:48');
/*!40000 ALTER TABLE `Settlements` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-04 12:39:51
