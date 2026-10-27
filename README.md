# Mandi Bidding Engine (Agri-Market Price Forecasting & Mandi Bidding Engine)

Production-grade Java 17+ backend for a mandi (agricultural market) that runs a
**double auction** between farmers (asks) and buyers (bids), screens bids for
**price manipulation**, and settles every trade with **full ACID guarantees**
in MySQL. All monetary amounts and quantities use `java.math.BigDecimal`.

## Quick start (for anyone opening this project)

**You need:** JDK 17+ · MySQL 8.0+ running on `localhost:3306` · nothing else (the MySQL driver ships in `lib/`).

```bash
# 1. clone
git clone https://github.com/pavanvasagiri/MandiBiddingEngine.git
cd MandiBiddingEngine

# 2. point the app at your MySQL (one time)
cp config/db.properties.example config/db.properties    # then edit user + password
#   Windows: copy config\db.properties.example config\db.properties

# 3a. run the web dashboard -> open http://localhost:8080
./run.sh server        # macOS/Linux      |  Windows: double-click start-server.bat

# 3b. or run the scripted live simulation (console report + CSV export)
./run.sh               # macOS/Linux      |  Windows: double-click run-simulation.bat
```

No clone needed to *look* around? If the host keeps a tunnel running, share its
`https://*.trycloudflare.com` link — visitors only need a browser.

## Architecture

| Package | Contents |
| --- | --- |
| `com.mandi.model` | Thread-safe, BigDecimal-only domain: `Bid` (Max-Heap `Comparable`), `Ask` (Min-Heap `Comparable`), `AuctionLot`, `Buyer`, `Farmer`, `Crop`, `Settlement` (+ `LotStatus`, `BidStatus`) |
| `com.mandi.engine` | `DoubleAuctionEngine`: `ConcurrentHashMap` of per-crop `PriorityQueue` order books guarded by `ReentrantLock`s, MSP floor, 50%-above-rolling-average anomaly screen, midpoint clearing `(bid + ask) / 2` with partial fills |
| `com.mandi.dao` | `DatabaseConnection` (thread-safe JDBC pool), `DatabaseInitializer` (schema bootstrap), `SettlementDAO` (SERIALIZABLE transactions + `SELECT ... FOR UPDATE` pessimistic locking), `MarketAnalyticsDAO` (SQL window functions), plus crop/farmer/buyer/lot/bid DAOs |
| `com.mandi.service` | `SeedDataService`, `MarketService` (orchestration), `CsvExporter` |
| `com.mandi.server` | Local HTTP application: trading dashboard + JSON API (`MandiHttpServer`, `MandiServerMain`, `DashboardRenderer`, `JsonUtil`) |
| `com.mandi.main` | `Main` - live simulation CLI (`server [port]` arg switches to HTTP mode) |
| `com.mandi.exception` | Domain-checked exceptions |

Legacy prototype sources are preserved under `legacy/`; the pre-v2 SQL scripts
remain under `sql/` for history. Current schema reference: `sql/mandi_schema_v2.sql`.

## Requirements

- JDK 17+ (built and tested on JDK 25)
- MySQL 8.0+ running on `localhost:3306` (window functions are required)
- MySQL Connector/J 8.3.0 (already in `lib/`)

## Configuration

`config/db.properties` holds the local settings (URL parts, user, password,
`db.pool.size=10`, and `db.reset=true` to drop/recreate the schema per start
for deterministic demos). Environment variables `MANDI_DB_URL`, `MANDI_DB_USER`,
`MANDI_DB_PASSWORD` override the file for real deployments - keep secrets out
of source control.

## Run as an application

**Option A - standalone executable JAR (recommended)**

```bash
bash dist.sh                           # one-time package -> dist/MandiBiddingEngine.jar
java -jar dist/MandiBiddingEngine.jar  # self-contained: app + MySQL Connector/J
```

**Option B - developer loop**

```bash
./run.sh    # clean compile + regression tests + simulation
```

**Option C - VS Code**

Open the folder in VS Code (with the Extension Pack for Java installed) and
press F5; `.vscode/launch.json` ships run configurations for the simulation,
the local server, and the engine regression tests.

## Run as a local server (web application)

### Recommended: `./start-mandi.sh` (reliable, works at any time)

```bash
./start-mandi.sh              # dashboard on http://localhost:8080
MANDI_PORT=9090 ./start-mandi.sh   # custom port
```

This script:
1. Starts MySQL automatically if it isn't reachable on `127.0.0.1:3306`
2. Compiles the Java sources if `out/` is missing or out of date
3. Cleans up any stale `server.pid` from a previous run
4. Starts `MandiServerMain` in the background and waits for `/api/health` to report `UP`
5. Opens `http://localhost:PORT` in your browser

Companion scripts: `./stop-mandi.sh` and `./status-mandi.sh`.

### Alternative: executable JAR

```bash
bash dist.sh                                   # one-time package -> dist/MandiBiddingEngine.jar
java -jar dist/MandiBiddingEngine.jar server   # dashboard on http://localhost:8080
java -jar dist/MandiBiddingEngine.jar server 9090   # custom port (or MANDI_PORT env)
```

(or double-click `mandi-server.command` on macOS). With `db.reset=true` the
server rebuilds a freshly seeded market on every start; set `db.reset=false`
to keep data across restarts (open lots are reloaded into the ask book).

Then open http://localhost:8080 for the trading dashboard (place bids, fire a
concurrent bidding burst, clear markets, download the CSV) or call the API:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | HTML trading dashboard |
| GET | `/api/health` | Liveness + database probe |
| GET | `/api/crops` | Crops, MSPs, latest 7-day rolling averages |
| GET | `/api/book` | Order-book depth per crop |
| POST | `/api/bids` | Place a bid `{cropId, buyerId, price, quantity}` (MSP + anomaly screened, persisted) |
| POST | `/api/asks` | Consign a new lot `{cropId, farmerId, quantity, reservePrice}` |
| POST | `/api/clear` | Run clearing + settle every matched trade transactionally |
| GET | `/api/settlements` | Settlement ledger + totals |
| GET | `/api/trends` | 7-day rolling average series |
| GET | `/api/export.csv` | Download the settlement log |
| POST | `/api/simulate/burst` | Fire N concurrent bids via the 10-thread pool (`{count}` or `?count=`) |
| POST | `/api/admin/reseed` | Recreate + reseed a fresh market |

This is a **local demo server**: it binds to localhost only and has no
authentication - keep it that way, or put it behind a real gateway with auth
before exposing it beyond your machine.

## What the simulation does

1. Bootstraps `mandi_db` (drops/recreates when `db.reset=true`).
2. Seeds 28 nationally-traded crops with MSPs / market floors - cereals
   (Wheat 2275, Paddy 2183, Maize 2090, Barley, Bajra, Jowar), pulses (Gram,
   Tur, Moong, Urad, Masur), oilseeds (Soybean, Mustard, Groundnut, Sunflower),
   Cotton, and 12 vegetables (Potato, Onion, Tomato, Cauliflower, Cabbage,
   Green Peas, Lady Finger, Brinjal, Green Chilli, Garlic, Ginger, Carrot);
   12 farmers across Indian mandi belts, 20 buyers funded with Rs.30,00,000
   escrow each, 112 auction lots (4 per crop), and 10 days of price history.
3. Fires 60 scripted bids from a **10-thread `ExecutorService`** - three are
   deliberately below MSP and two are 160% of the 7-day rolling average, all
   of which the engine auto-rejects/flags. Every bid (accepted or rejected)
   is persisted to the `Bids` audit table.
4. Runs the clearing phase: highest bid x lowest ask at
   `clearing price = (bid + ask) / 2`, partially filling either side.
5. Settles each trade in its own `TRANSACTION_SERIALIZABLE` JDBC transaction
   with `FOR UPDATE` row locks on `Auction_Lots` and `Buyers`: verifies escrow,
   debits `gross + 2% mandi tax` from the buyer, accumulates the lot fill
   (lot flips to SETTLED when fully consumed), inserts the `Settlements` row,
   and commits - rolling back on any failure.
6. Prints payout summaries, total mandi tax, and the 7-day rolling price trend
   (window function `AVG() OVER (... ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)`),
   then exports `historical_mandi_bids.csv`.

## Settlement money model

`gross = clearingPrice x quantity`; `mandiTax = 2% of gross`; the buyer is
debited `gross + mandiTax`; the farmer payout equals `gross`.
