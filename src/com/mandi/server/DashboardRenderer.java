package com.mandi.server;

import com.mandi.dao.BidDAO;
import com.mandi.dao.BuyerDAO;
import com.mandi.dao.CropDAO;
import com.mandi.dao.FarmerDAO;
import com.mandi.dao.MarketAnalyticsDAO;
import com.mandi.dao.SettlementDAO;
import com.mandi.engine.DoubleAuctionEngine;
import com.mandi.model.Bid;
import com.mandi.model.Buyer;
import com.mandi.model.Crop;
import com.mandi.model.Settlement;
import com.mandi.service.MarketService;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the single-page HTML trading dashboard served at
 * {@code http://localhost:PORT/}. Fully self-contained: inline CSS, inline SVG
 * price charts, and toast-based UX; the single Google-Fonts import falls back
 * to system fonts when offline.
 */
final class DashboardRenderer {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd MMM");
    private static final NumberFormat MONEY = moneyFormat();

    private final CropDAO cropDAO = new CropDAO();
    private final BuyerDAO buyerDAO = new BuyerDAO();
    private final FarmerDAO farmerDAO = new FarmerDAO();
    private final BidDAO bidDAO = new BidDAO();
    private final SettlementDAO settlementDAO = new SettlementDAO();

    String render(MarketService market) {
        List<Crop> crops = cropDAO.findAll();
        Map<Integer, BigDecimal> rolling = market.latest7DayRollingAverages();
        Map<Integer, String> cropNames = new HashMap<>();
        for (Crop crop : crops) cropNames.put(crop.getCropId(), crop.getCropName());
        Map<Integer, String> buyerNames = new HashMap<>();
        for (Buyer buyer : buyerDAO.findAll()) buyerNames.put(buyer.getBuyerId(), buyer.getName());
        List<Settlement> settlements = settlementDAO.findAll();
        List<BidDAO.BidRow> bidRows = bidDAO.findAllRows();
        DoubleAuctionEngine engine = market.engine();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal payout = BigDecimal.ZERO;
        for (Settlement s : settlements) {
            gross = gross.add(s.getGrossAmount());
            tax = tax.add(s.getMandiTax());
            payout = payout.add(s.getFarmerPayout());
        }
        int openBids = 0;
        int openAsks = 0;
        for (Crop crop : crops) {
            openBids += engine.pendingBidCount(crop.getCropId());
            openAsks += engine.pendingAskCount(crop.getCropId());
        }

        StringBuilder html = new StringBuilder(32_768);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<title>Mandi Bidding Engine</title><style>")
            .append("@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;800&display=swap');")
            .append("*{box-sizing:border-box;margin:0;padding:0}")
            .append("body{font-family:'Inter',-apple-system,'Segoe UI',Roboto,sans-serif;background:linear-gradient(160deg,#f2f8f2,#e9f2ea 55%,#eef5ee);min-height:100vh;color:#14251a;padding:26px 20px}")
            .append(".wrap{max-width:1120px;margin:0 auto}")
            .append(".hero{background:linear-gradient(120deg,#0f3d22,#15803d 55%,#22c55e);border-radius:18px;padding:24px 28px;color:#fff;display:flex;align-items:center;gap:18px;box-shadow:0 10px 30px rgba(21,128,61,.28);flex-wrap:wrap}")
            .append(".hero .logo{width:54px;height:54px;border-radius:14px;background:rgba(255,255,255,.16);display:flex;align-items:center;justify-content:center;font-size:28px}")
            .append(".hero h1{font-size:21px;font-weight:800}")
            .append(".hero p{opacity:.88;font-size:12.5px;margin-top:3px}")
            .append(".hero .spacer{flex:1}")
            .append(".live{display:inline-flex;align-items:center;gap:7px;background:rgba(255,255,255,.15);padding:6px 12px;border-radius:999px;font-size:12px;font-weight:700}")
            .append(".live i{width:9px;height:9px;border-radius:50%;background:#4ade80;animation:pulse 1.6s infinite}")
            .append("@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(74,222,128,.7)}70%{box-shadow:0 0 0 8px rgba(74,222,128,0)}100%{box-shadow:0 0 0 0 rgba(74,222,128,0)}}")
            .append(".btn{display:inline-block;padding:9px 16px;border:0;border-radius:10px;background:#16a34a;color:#fff;font-weight:600;font-size:13px;cursor:pointer;text-decoration:none;transition:.15s;font-family:inherit}")
            .append(".btn:hover{transform:translateY(-1px);box-shadow:0 6px 14px rgba(22,163,74,.35)}")
            .append(".hero .btn{background:rgba(255,255,255,.16)}.hero .btn:hover{background:rgba(255,255,255,.3)}")
            .append(".btn.ghost{background:#fff;color:#15803d;border:1.5px solid #bbf7d0}.btn.ghost:hover{background:#f0fdf4}");

        html.append(".kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(168px,1fr));gap:14px;margin:18px 0}")
            .append(".kpi{background:#fff;border-radius:14px;padding:15px 17px;box-shadow:0 4px 14px rgba(20,37,26,.06);display:flex;gap:13px;align-items:center}")
            .append(".kpi .ico{width:42px;height:42px;border-radius:11px;display:flex;align-items:center;justify-content:center;font-size:20px;flex:none}")
            .append(".kpi .lbl{font-size:11px;color:#5b6b5e;font-weight:700;text-transform:uppercase;letter-spacing:.5px}")
            .append(".kpi .val{font-size:18px;font-weight:800;margin-top:2px;white-space:nowrap}")
            .append(".card{background:#fff;border-radius:14px;box-shadow:0 4px 14px rgba(20,37,26,.06);padding:20px 22px;margin-bottom:18px}")
            .append(".card h2{font-size:15px;font-weight:800;color:#14532d;margin-bottom:12px}")
            .append("table{width:100%;border-collapse:collapse;font-size:13.5px}")
            .append("th{background:#f0fdf4;color:#166534;text-align:right;padding:9px 12px;font-size:11px;text-transform:uppercase;letter-spacing:.4px;border-bottom:2px solid #bbf7d0}")
            .append("td{padding:8px 12px;text-align:right;border-bottom:1px solid #eef4ee}")
            .append("th.l,td.l{text-align:left}tbody tr:hover{background:#f7fcf7}tr:last-child td{border-bottom:0}")
            .append(".badge{display:inline-block;padding:3px 10px;border-radius:999px;font-size:11px;font-weight:700}")
            .append(".b-acc{background:#dcfce7;color:#166534}.b-rej{background:#fee2e2;color:#b91c1c}.b-mut{background:#f1f5f1;color:#64748b}")
            .append(".up{color:#15803d;font-weight:700}.down{color:#b91c1c;font-weight:700}.mut{color:#64748b}")
            .append(".charts{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:14px}")
            .append(".chart-card{background:#fff;border-radius:14px;box-shadow:0 4px 14px rgba(20,37,26,.06);padding:16px 18px}")
            .append(".chart-card h3{font-size:13.5px;color:#14532d;display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}")
            .append("svg{width:100%;height:auto;display:block}")
            .append("form .row{display:flex;flex-wrap:wrap;gap:12px;align-items:end}")
            .append("form label{font-size:11.5px;font-weight:700;color:#3f5244;display:flex;flex-direction:column;gap:5px;text-transform:uppercase;letter-spacing:.4px}")
            .append("input,select{padding:9px 12px;border:1.5px solid #d7e5d7;border-radius:9px;font:inherit;font-size:13.5px;background:#fbfdfb}")
            .append("input:focus,select:focus{outline:2px solid #86efac;border-color:#16a34a}")
            .append("#hint{font-size:12.5px;color:#5b6b5e;margin-top:10px}")
            .append("#hint .warn{color:#b45309;font-weight:700}")
            .append("#toasts{position:fixed;right:18px;bottom:18px;display:flex;flex-direction:column;gap:10px;z-index:50;max-width:390px}")
            .append(".toast{background:#fff;border-radius:12px;box-shadow:0 10px 28px rgba(0,0,0,.2);padding:13px 15px;border-left:5px solid #16a34a;animation:slide .25s ease}")
            .append(".toast.err{border-left-color:#dc2626}")
            .append(".toast b{font-size:13.5px}")
            .append(".toast pre{font-size:11px;background:#f6f8f6;border-radius:8px;padding:8px;margin-top:7px;overflow:auto;max-height:170px;white-space:pre-wrap}")
            .append("@keyframes slide{from{transform:translateX(30px);opacity:0}to{transform:none;opacity:1}}")
            .append(".foot{color:#6b7f6e;font-size:12px;text-align:center;margin:20px 0 4px}")
            .append("</style></head><body><div class=\"wrap\">");

        html.append("<div class=\"hero\">")
            .append("<div class=\"logo\">\uD83C\uDF3E</div>")
            .append("<div><h1>Agri-Market Price Forecasting &amp; Mandi Bidding Engine</h1>")
            .append("<p>Double-auction marketplace &middot; MSP floor &middot; price-manipulation screening &middot; SERIALIZABLE MySQL settlements</p></div>")
            .append("<div class=\"spacer\"></div>")
            .append("<span class=\"live\"><i></i>LIVE</span>")
            .append("<button class=\"btn\" onclick=\"location.reload()\">\u21BB Refresh</button>")
            .append("<a class=\"btn\" style=\"background:#fff;color:#15803d\" href=\"/api/export.csv\">\u2B07 CSV</a>")
            .append("</div>");

        html.append("<div class=\"kpis\">")
            .append(kpi("\uD83D\uDCBC", "Settled trades", String.valueOf(settlements.size()), "#dcfce7", "kpi-settlements"))
            .append(kpi("\uD83D\uDCB0", "Gross traded", money(gross), "#fef9c3", "kpi-gross"))
            .append(kpi("\uD83E\uDDFE", "Mandi tax (2%)", money(tax), "#fee2e2", "kpi-tax"))
            .append(kpi("\uD83D\uDC68\u200D\uD83C\uDF3E", "Farmer payouts", money(payout), "#dbeafe", "kpi-payout"))
            .append(kpi("\uD83D\uDED2", "Open bids", String.valueOf(openBids), "#ede9fe", "kpi-bids"))
            .append(kpi("\uD83C\uDFF7\uFE0F", "Open asks", String.valueOf(openAsks), "#ccfbf1", "kpi-asks"))
            .append("</div>");

        html.append("<div class=\"card\"><h2>Market overview</h2><table><thead><tr>")
            .append("<th class=\"l\">Crop</th><th>MSP (\u20B9/q)</th><th>7-day rolling avg</th><th>Bid ceiling (1.5\u00D7 avg)</th><th>Open bids</th><th>Open asks</th>")
            .append("</tr></thead><tbody>");
        for (Crop crop : crops) {
            BigDecimal roll = rolling.getOrDefault(crop.getCropId(), crop.getMspPricePerQuintal());
            BigDecimal ceiling = roll.multiply(DoubleAuctionEngine.ANOMALY_THRESHOLD_MULTIPLIER).setScale(2, java.math.RoundingMode.HALF_UP);
            html.append("<tr><td class=\"l\"><span class=\"badge b-mut\">#").append(crop.getCropId()).append("</span> ")
                .append(esc(crop.getCropName()))
                .append("</td><td>").append(crop.getMspPricePerQuintal())
                .append("</td><td>").append(roll)
                .append("</td><td class=\"mut\">").append(ceiling)
                .append("</td><td id=\"book-bids-").append(crop.getCropId()).append("\">").append(engine.pendingBidCount(crop.getCropId()))
                .append("</td><td id=\"book-asks-").append(crop.getCropId()).append("\">").append(engine.pendingAskCount(crop.getCropId()))
                .append("</td></tr>");
        }
        html.append("</tbody></table></div>");

        html.append("<div class=\"card\"><h2>Recent bids <span class=\"mut\" style=\"font-weight:400;font-size:12px\">(latest 10, committed)</span></h2>");
        if (bidRows.isEmpty()) {
            html.append("<p class=\"mut\">No bids yet - place the first one below.</p></div>");
        } else {
            html.append("<table><thead><tr><th>ID</th><th class=\"l\">Buyer</th><th class=\"l\">Crop</th><th>Price (\u20B9/q)</th><th>Qty (q)</th><th class=\"l\">Screening outcome</th></tr></thead><tbody>");
            for (int i = bidRows.size() - 1, shown = 0; i >= 0 && shown < 10; i--, shown++) {
                BidDAO.BidRow row = bidRows.get(i);
                Bid bid = row.bid();
                html.append("<tr><td>").append(bid.getBidId())
                    .append("</td><td class=\"l\">").append(esc(buyerNames.getOrDefault(bid.getBuyerId(), "Buyer#" + bid.getBuyerId())))
                    .append("</td><td class=\"l\">").append(esc(cropNames.getOrDefault(bid.getCropId(), "Crop#" + bid.getCropId())))
                    .append("</td><td>").append(bid.getBidPricePerQuintal())
                    .append("</td><td>").append(bid.getQuantityQuintals())
                    .append("</td><td class=\"l\"><span class=\"badge ").append(row.status().isAccepted() ? "b-acc" : "b-rej").append("\">")
                    .append(row.status().isAccepted() ? "ACCEPTED" : row.status()).append("</span></td></tr>");
            }
            html.append("</tbody></table></div>");
        }

        html.append("<div class=\"card\"><h2>Settlements <span class=\"mut\" style=\"font-weight:400;font-size:12px\">(latest 12)</span></h2>");
        if (settlements.isEmpty()) {
            html.append("<p class=\"mut\">Nothing settled yet - place bids, then run <b>Clear markets &amp; settle</b>.</p></div>");
        } else {
            Map<Integer, String> farmerNames = new HashMap<>();
            for (com.mandi.model.Farmer farmer : farmerDAO.findAll()) farmerNames.put(farmer.getFarmerId(), farmer.getName());
            html.append("<table><thead><tr><th>ID</th><th>Lot</th><th class=\"l\">Buyer</th><th class=\"l\">Farmer</th><th>Clearing (\u20B9/q)</th><th>Qty (q)</th><th>Gross (\u20B9)</th><th>Mandi tax (\u20B9)</th><th>Farmer payout (\u20B9)</th></tr></thead><tbody>");
            for (int i = Math.max(0, settlements.size() - 12); i < settlements.size(); i++) {
                Settlement s = settlements.get(i);
                html.append("<tr><td>S#").append(s.getSettlementId()).append("</td><td>").append(s.getLotId())
                    .append("</td><td class=\"l\">").append(esc(buyerNames.getOrDefault(s.getBuyerId(), "Buyer#" + s.getBuyerId())))
                    .append("</td><td class=\"l\">").append(esc(farmerNames.getOrDefault(s.getFarmerId(), "Farmer#" + s.getFarmerId())))
                    .append("</td><td>").append(s.getClearingPricePerQuintal())
                    .append("</td><td>").append(s.getQuantityQuintals())
                    .append("</td><td>").append(s.getGrossAmount())
                    .append("</td><td>").append(s.getMandiTax())
                    .append("</td><td class=\"up\">").append(s.getFarmerPayout()).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }

        Map<String, List<MarketAnalyticsDAO.RollingAverage>> trends = new LinkedHashMap<>();
        for (MarketAnalyticsDAO.RollingAverage row : market.rolling7DayTrends()) {
            trends.computeIfAbsent(row.cropName(), name -> new ArrayList<>()).add(row);
        }
        html.append("<h2 style=\"font-size:15px;font-weight:800;color:#14532d;margin:4px 0 12px\">7-day rolling average price trend</h2><div class=\"charts\">");
        for (Map.Entry<String, List<MarketAnalyticsDAO.RollingAverage>> entry : trends.entrySet()) {
            List<MarketAnalyticsDAO.RollingAverage> points = entry.getValue();
            int from = Math.max(0, points.size() - 8);
            BigDecimal firstRoll = points.get(from).rolling7DayAvg();
            BigDecimal lastRoll = points.get(points.size() - 1).rolling7DayAvg();
            double pct = firstRoll.signum() == 0 ? 0
                    : lastRoll.subtract(firstRoll).divide(firstRoll, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
            html.append("<div class=\"chart-card\"><h3><span>").append(esc(entry.getKey()))
                .append("</span><span class=\"").append(pct >= 0 ? "up" : "down").append("\">")
                .append(pct >= 0 ? "\u25B2 " : "\u25BC ").append(String.format(Locale.US, "%.2f%%", Math.abs(pct)))
                .append("</span></h3>")
                .append(sparkline(points, entry.getKey()))
                .append("</div>");
        }
        html.append("</div>");

        html.append("<div class=\"card\"><h2>Place a bid</h2><form onsubmit=\"return false\"><div class=\"row\">")
            .append("<label>Crop<select id=\"crop\" onchange=\"onCropChange()\">");
        for (Crop crop : crops) {
            BigDecimal roll = rolling.getOrDefault(crop.getCropId(), crop.getMspPricePerQuintal());
            html.append("<option value=\"").append(crop.getCropId())
                .append("\" data-msp=\"").append(crop.getMspPricePerQuintal())
                .append("\" data-roll=\"").append(roll).append("\">")
                .append(esc(crop.getCropName())).append("</option>");
        }
        html.append("</select></label><label>Buyer<select id=\"buyer\">");
        for (Buyer buyer : buyerDAO.findAll()) {
            html.append("<option value=\"").append(buyer.getBuyerId()).append("\">").append(esc(buyer.getName())).append("</option>");
        }
        html.append("</select></label>")
            .append("<label>Price (\u20B9/q)<input id=\"price\" type=\"number\" step=\"0.01\" min=\"0\" style=\"width:120px\"></label>")
            .append("<label>Quantity (q)<input id=\"qty\" type=\"number\" step=\"1\" min=\"1\" value=\"10\" style=\"width:90px\"></label>")
            .append("<button class=\"btn\" onclick=\"placeBid()\">Place bid</button>")
            .append("</div></form><div id=\"hint\"></div>")
            .append("<div style=\"margin-top:14px\">")
            .append("<button class=\"btn ghost\" onclick=\"burst()\">\u26A1 Burst: 40 concurrent bids</button>")
            .append("<button class=\"btn ghost\" onclick=\"clearMarkets()\">\u2696 Clear markets &amp; settle</button>")
            .append("<button class=\"btn ghost\" onclick=\"reseed()\">\uD83C\uDF31 Reseed fresh market</button>")
            .append("</div></div>");

        html.append("<div class=\"foot\">Mandi Bidding Engine &middot; local demo server (no auth) &middot; data: MySQL <b>mandi_db</b> &middot; order book auto-refreshes every 15s &middot; <span id=\"stamp\" class=\"mut\">live</span></div>")
            .append("<div id=\"toasts\"></div>")
            .append("<script>")
            .append("const money=n=>'\\u20B9'+Number(n).toLocaleString('en-IN',{minimumFractionDigits:2,maximumFractionDigits:2});")
            .append("function toast(title,body,ok){const t=document.createElement('div');t.className='toast'+(ok?'':' err');")
            .append("t.innerHTML='<b>'+title+'</b><pre>'+body+'</pre>';document.getElementById('toasts').appendChild(t);setTimeout(()=>t.remove(),6000);}")
            .append("async function post(url,body){try{const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});")
            .append("const d=await r.json();")
            .append("if(d.error){toast('Request failed',JSON.stringify(d,null,2),false);return;}")
            .append("if(d.status){toast('Bid '+d.status,(d.reason||'')+(d.bidId?'\\nbid #'+d.bidId:'')+(d.referencePrice?'\\nreference: '+d.referencePrice:''),d.status==='ACCEPTED');}")
            .append("else if(d.executedTrades!==undefined){toast('Markets cleared',d.executedTrades+' trades executed \\u2192 '+d.settled+' settled, '+d.rolledBack+' rolled back',d.rolledBack===0);}")
            .append("else if(d.submitted!==undefined){toast('Burst complete',d.submitted+' concurrent bids in '+d.elapsedMs+' ms\\n'+JSON.stringify(d.outcomes),true);}")
            .append("else if(d.status==='reseeded'){toast('Market reseeded','A fresh seeded market is ready',true);}")
            .append("else{toast('Done',JSON.stringify(d,null,2),true);}")
            .append("setTimeout(()=>location.reload(),1800);}catch(e){toast('Network error',String(e),false);}}")
            .append("function placeBid(){post('/api/bids',{cropId:+crop.value,buyerId:+buyer.value,price:+price.value,quantity:+qty.value});}")
            .append("function burst(){post('/api/simulate/burst',{count:40});}")
            .append("function clearMarkets(){post('/api/clear',{});}")
            .append("function reseed(){post('/api/admin/reseed',{});}")
            .append("function onCropChange(){const o=crop.selectedOptions[0];const msp=+o.dataset.msp,roll=+o.dataset.roll;")
            .append("price.value=(msp*1.02).toFixed(2);const limit=(roll*1.5).toFixed(2);")
            .append("let h='MSP \\u20B9'+msp.toFixed(2)+' \\u00B7 7-day avg \\u20B9'+roll.toFixed(2)+' \\u00B7 manipulation limit \\u20B9'+limit;")
            .append("if(+price.value>limit)h+=' <span class=\\\"warn\\\">\\u26A0 above limit - will be flagged</span>';hint.innerHTML=h;}")
            .append("onCropChange();")
            .append("function set(id,v){const el=document.getElementById(id);if(el)el.textContent=v;}")
            .append("async function refreshLive(){try{const b=await(await fetch('/api/book')).json();let tb=0,ta=0;")
            .append("b.forEach(r=>{const e1=document.getElementById('book-bids-'+r.cropId),e2=document.getElementById('book-asks-'+r.cropId);")
            .append("if(e1)e1.textContent=r.openBids;if(e2)e2.textContent=r.openAsks;tb+=r.openBids;ta+=r.openAsks;});")
            .append("const s=await(await fetch('/api/settlements')).json();")
            .append("set('kpi-settlements',s.count);set('kpi-gross',money(s.grossAmount));set('kpi-tax',money(s.mandiTax));set('kpi-payout',money(s.farmerPayout));")
            .append("set('kpi-bids',tb);set('kpi-asks',ta);")
            .append("const st=document.getElementById('stamp');if(st)st.textContent='auto-refreshed '+new Date().toLocaleTimeString();}catch(e){}}")
            .append("setInterval(refreshLive,15000);")
            .append("</script></body></html>");
        return html.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String kpi(String icon, String label, String value, String iconBg, String id) {
        return "<div class=\"kpi\"><div class=\"ico\" style=\"background:" + iconBg + "\">" + icon
                + "</div><div><div class=\"lbl\">" + label + "</div><div class=\"val\" id=\"" + id
                + "\">" + value + "</div></div></div>";
    }

    private static String money(BigDecimal value) {
        return "\u20B9" + MONEY.format(value);
    }

    /** Indian-style digit grouping (lakh/crore) with 2 decimals. */
    private static NumberFormat moneyFormat() {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    /**
     * Inline SVG sparkline of the last 8 trading days: grey dashed daily
     * average, bold rolling average with gradient area fill, and a labelled
     * latest point. Pure SVG - no chart library needed.
     */
    private static String sparkline(List<MarketAnalyticsDAO.RollingAverage> all, String cropName) {
        int from = Math.max(0, all.size() - 8);
        List<MarketAnalyticsDAO.RollingAverage> pts = all.subList(from, all.size());
        int n = pts.size();
        if (n < 2) return "<p class=\"mut\">Not enough price history yet.</p>";

        int w = 640, h = 150, padL = 52, padR = 16, padT = 16, padB = 30;
        BigDecimal lo = pts.get(0).avgPrice();
        BigDecimal hi = lo;
        for (MarketAnalyticsDAO.RollingAverage p : pts) {
            lo = lo.min(p.avgPrice()).min(p.rolling7DayAvg());
            hi = hi.max(p.avgPrice()).max(p.rolling7DayAvg());
        }
        double min = lo.doubleValue();
        double max = hi.doubleValue();
        double span = Math.max(max - min, 1.0);
        min -= span * 0.12;
        max += span * 0.12;
        double usableH = h - padT - padB;
        double stepX = (w - padL - padR) / (n - 1);

        double[] dx = new double[n];
        double[] dyRoll = new double[n];
        double[] dyDaily = new double[n];
        for (int i = 0; i < n; i++) {
            dx[i] = padL + i * stepX;
            dyRoll[i] = padT + (max - pts.get(i).rolling7DayAvg().doubleValue()) / (max - min) * usableH;
            dyDaily[i] = padT + (max - pts.get(i).avgPrice().doubleValue()) / (max - min) * usableH;
        }

        String key = "g" + cropName.replaceAll("[^A-Za-z0-9]", "");
        StringBuilder line = new StringBuilder();
        StringBuilder daily = new StringBuilder();
        for (int i = 0; i < n; i++) {
            line.append(i == 0 ? "" : " ").append(fmt(dx[i])).append(',').append(fmt(dyRoll[i]));
            daily.append(i == 0 ? "" : " ").append(fmt(dx[i])).append(',').append(fmt(dyDaily[i]));
        }

        StringBuilder svg = new StringBuilder(2048);
        svg.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(h).append("\" role=\"img\">")
           .append("<defs><linearGradient id=\"").append(key).append("\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
           .append("<stop offset=\"0\" stop-color=\"#22c55e\" stop-opacity=\".40\"/>")
           .append("<stop offset=\"1\" stop-color=\"#22c55e\" stop-opacity=\".02\"/></linearGradient></defs>");
        for (int g = 0; g <= 2; g++) {
            double v = min + (max - min) * g / 2.0;
            double y = padT + (max - v) / (max - min) * usableH;
            svg.append("<line x1=\"").append(padL).append("\" y1=\"").append(fmt(y))
               .append("\" x2=\"").append(w - padR).append("\" y2=\"").append(fmt(y))
               .append("\" stroke=\"#e4efe4\"/>")
               .append("<text x=\"").append(padL - 6).append("\" y=\"").append(fmt(y + 3))
               .append("\" text-anchor=\"end\" font-size=\"9.5\" fill=\"#7c8f7e\">")
               .append(String.format(Locale.US, "%.0f", v)).append("</text>");
        }
        svg.append("<path d=\"M").append(line.toString().replace(" ", " L "))
           .append(" L ").append(fmt(dx[n - 1])).append(',').append(h - padB)
           .append(" L ").append(fmt(dx[0])).append(',').append(h - padB)
           .append(" Z\" fill=\"url(#").append(key).append(")\"/>")
           .append("<polyline fill=\"none\" stroke=\"#94c997\" stroke-width=\"1.6\" stroke-dasharray=\"4 3\" points=\"").append(daily).append("\"/>")
           .append("<polyline fill=\"none\" stroke=\"#16a34a\" stroke-width=\"2.6\" stroke-linejoin=\"round\" points=\"").append(line).append("\"/>");
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            svg.append("<circle cx=\"").append(fmt(dx[i])).append("\" cy=\"").append(fmt(dyRoll[i]))
               .append("\" r=\"").append(last ? 4.5 : 3)
               .append("\" fill=\"").append(last ? "#15803d" : "#4ade80")
               .append("\" stroke=\"#fff\" stroke-width=\"1.5\"/>");
        }
        svg.append("<text x=\"").append(fmt(dx[n - 1])).append("\" y=\"").append(fmt(Math.max(dyRoll[n - 1] - 9, 10)))
           .append("\" text-anchor=\"end\" font-size=\"11\" font-weight=\"700\" fill=\"#14532d\">")
           .append(pts.get(n - 1).rolling7DayAvg()).append("</text>")
           .append("<text x=\"").append(fmt(dx[0])).append("\" y=\"").append(h - 8)
           .append("\" font-size=\"10\" fill=\"#7c8f7e\">").append(DAY_LABEL.format(pts.get(0).tradeDate())).append("</text>")
           .append("<text x=\"").append(w - padR).append("\" y=\"").append(h - 8)
           .append("\" text-anchor=\"end\" font-size=\"10\" fill=\"#7c8f7e\">")
           .append(DAY_LABEL.format(pts.get(n - 1).tradeDate())).append("</text>")
           .append("</svg>");
        return svg.toString();
    }
}
