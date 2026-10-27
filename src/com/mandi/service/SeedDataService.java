package com.mandi.service;

import com.mandi.dao.AuctionLotDAO;
import com.mandi.dao.BuyerDAO;
import com.mandi.dao.CropDAO;
import com.mandi.dao.FarmerDAO;
import com.mandi.dao.MarketAnalyticsDAO;
import com.mandi.model.AuctionLot;
import com.mandi.model.Buyer;
import com.mandi.model.Crop;
import com.mandi.model.Farmer;
import com.mandi.model.LotStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the deterministic reference data used by the simulation:
 * crops (with MSPs), farmers, funded buyers, farmer auction lots and ten
 * days of historical mandi prices that feed the 7-day rolling average.
 */
public final class SeedDataService {

    /** Everything seeded, in database order. */
    public record SeedResult(List<Crop> crops, List<Farmer> farmers, List<Buyer> buyers, List<AuctionLot> lots) { }

    /**
     * National crop catalogue: government-MSP cereals, pulses, oilseeds and
     * fibre crops plus high-volume vegetable crops that move across mandis
     * country-wide. (Vegetable entries carry an indicative mandi floor price -
     * there is no statutory MSP for perishables.)
     */
    private static final String[][] CROPS = {
            // major cereals / foodgrains
            {"Wheat (Gehun)", "2275.00"},
            {"Paddy Rice (Dhan)", "2183.00"},
            {"Maize (Makka)", "2090.00"},
            {"Barley (Jau)", "1985.00"},
            {"Bajra (Pearl Millet)", "2625.00"},
            {"Jowar (Sorghum)", "3297.00"},
            // pulses
            {"Gram (Chana)", "5640.00"},
            {"Tur (Arhar)", "7550.00"},
            {"Moong (Green Gram)", "8682.00"},
            {"Urad (Black Gram)", "7000.00"},
            {"Masur (Lentil)", "6425.00"},
            // oilseeds & fibre
            {"Soybean", "4892.00"},
            {"Mustard (Sarson)", "5650.00"},
            {"Groundnut", "6783.00"},
            {"Sunflower", "7280.00"},
            {"Cotton (Kapas)", "6620.00"},
            // vegetables travelling across the country
            {"Potato (Aloo)", "1250.00"},
            {"Onion (Pyaz)", "1550.00"},
            {"Tomato (Tamatar)", "1350.00"},
            {"Cauliflower (Phool Gobhi)", "1050.00"},
            {"Cabbage (Patta Gobhi)", "950.00"},
            {"Green Peas (Matar)", "2400.00"},
            {"Lady Finger (Bhindi)", "2600.00"},
            {"Brinjal (Baingan)", "1600.00"},
            {"Green Chilli (Hari Mirch)", "3600.00"},
            {"Garlic (Lehsun)", "6200.00"},
            {"Ginger (Adrak)", "4100.00"},
            {"Carrot (Gajar)", "1400.00"}
    };
    private static final String[][] FARMERS = {
            {"Ramesh Kumar", "Ludhiana", "SBIN0001234"},
            {"Suresh Patel", "Anand", "HDFC0005678"},
            {"Lakshmi Narayan", "Guntur", "ICIC0009012"},
            {"Anil Yadav", "Kanpur", "PUNB0003456"},
            {"Meena Devi", "Indore", "BARB0007890"},
            {"Jagdish Sharma", "Kota", "SBIJ0011122"},
            {"Vithal Shetty", "Hubballi", "CANB0012345"},
            {"Karuppasamy G", "Erode", "TMBL0004567"},
            {"Prakash Jadhav", "Nashik", "MSME0019876"},
            {"Hariram Meena", "Dungarpur", "RBBG0022334"},
            {"Sukhdev Brar", "Patiala", "PSIB0033445"},
            {"Gitabali Behera", "Sambalpur", "UTIB0044556"}
    };
    private static final String[] BUYERS = {
            "AgroFoods Pvt Ltd", "BigBasket Traders", "Cargill India", "Delhi Grain House",
            "Everfresh Exports", "Fortune Mills", "Grocery Bazaar", "Harvest Commodities",
            "IndiaAgri Procurement", "Janta Vypar Mandi", "KisanFoods Processors", "Lokmesh Traders LLP",
            "Metro Cash Mandi", "National Agro Exports", "Oriya Bazaar Syndicate", "Punjab Agro Mills",
            "Quality Spice Company", "Rashtriya Upbhokta Bazar", "Southern Foods Impex", "United Grain Corp"
    };
    /** Opening escrow funded per buyer (Rs.). */
    public static final BigDecimal BUYER_ESCROW = new BigDecimal("3000000.00");
    /** Reserve premium (basis points over MSP) and lot size patterns, cycled per lot. */
    private static final int[] RESERVE_PREMIUM_BPS = {80, 45, 110, 25};
    private static final int[] LOT_QUANTITIES = {80, 50, 120, 60};
    /** Daily deviation from MSP (basis points) for the 10 seeded history days. */
    private static final int[] HISTORY_DEVIATION_BPS = {0, 120, -80, 200, 50, -150, 300, 100, -50, 180};

    private final CropDAO cropDAO = new CropDAO();
    private final FarmerDAO farmerDAO = new FarmerDAO();
    private final BuyerDAO buyerDAO = new BuyerDAO();
    private final AuctionLotDAO lotDAO = new AuctionLotDAO();
    private final MarketAnalyticsDAO analyticsDAO = new MarketAnalyticsDAO();

    /** Inserts crops, farmers, buyers and four auction lots per crop. */
    public SeedResult seedReferenceData() {
        List<Crop> crops = new ArrayList<>();
        for (String[] row : CROPS) {
            Crop crop = new Crop(row[0], new BigDecimal(row[1]));
            cropDAO.insert(crop);
            crops.add(crop);
        }
        List<Farmer> farmers = new ArrayList<>();
        for (String[] row : FARMERS) {
            Farmer farmer = new Farmer(row[0], row[1], "ACC" + row[2].substring(4));
            farmerDAO.insert(farmer);
            farmers.add(farmer);
        }
        List<Buyer> buyers = new ArrayList<>();
        for (String name : BUYERS) {
            Buyer buyer = new Buyer(name, BUYER_ESCROW);
            buyerDAO.insert(buyer);
            buyers.add(buyer);
        }
        List<AuctionLot> lots = new ArrayList<>();
        for (Crop crop : crops) {
            for (int i = 0; i < RESERVE_PREMIUM_BPS.length; i++) { // four lots per crop
                AuctionLot lot = new AuctionLot(crop.getCropId(),
                        farmers.get(((crop.getCropId() - 1) * RESERVE_PREMIUM_BPS.length + i) % farmers.size()).getFarmerId(),
                        BigDecimal.valueOf(LOT_QUANTITIES[i]),
                        premiumOverMsp(crop.getMspPricePerQuintal(), RESERVE_PREMIUM_BPS[i]));
                lotDAO.insert(lot);
                lots.add(lot);
            }
        }
        return new SeedResult(crops, farmers, buyers, lots);
    }

    /** Writes {@code daysBack} days of daily average prices (ending yesterday) per crop. */
    public void seedPriceHistory(List<Crop> crops, int daysBack) {
        LocalDate today = LocalDate.now();
        int patternLength = HISTORY_DEVIATION_BPS.length;
        for (Crop crop : crops) {
            for (int daysAgo = daysBack; daysAgo >= 1; daysAgo--) {
                int basisPoints = HISTORY_DEVIATION_BPS[(daysBack - daysAgo) % patternLength];
                analyticsDAO.upsertDailyAverage(crop.getCropId(), today.minusDays(daysAgo),
                        premiumOverMsp(crop.getMspPricePerQuintal(), basisPoints));
            }
        }
    }

    private static BigDecimal premiumOverMsp(BigDecimal msp, int basisPoints) {
        return msp.add(msp.multiply(BigDecimal.valueOf(basisPoints, 4))).setScale(2, RoundingMode.HALF_UP);
    }
}
