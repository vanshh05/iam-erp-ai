# IAM ERP AI Assistant

An AI-powered ERP assistant that lets business owners and managers query their ERP data conversationally using natural language, powered by Google Gemini.

---

## Architecture

```
Chat UI (Vercel) → Spring Boot API (Render) → Gemini API
                                           → Supabase AI Tables
                                           ↑
                         Sync Job (every 2 min) ← Main ERP DB (GCP)
```

---

## Prerequisites

- Java 17+
- Maven 3.8+
- Supabase project (already created)
- Gemini API key
- Access to your main ERP database

---

## Setup

### 1. Configure application.properties

Open `src/main/resources/application.properties` and fill in:

```properties
# Supabase DB password (from Supabase dashboard → Settings → Database)
spring.datasource.password=YOUR_SUPABASE_DB_PASSWORD

# Your Gemini API key
gemini.api.key=YOUR_GEMINI_API_KEY

# Your main ERP database connection
erp.datasource.url=jdbc:postgresql://your-erp-host:5432/your-erp-db
erp.datasource.username=your-erp-username
erp.datasource.password=your-erp-password
```

### 2. Configure the Sync Job

Open `src/main/java/com/iamerp/ai/sync/SyncJob.java`.

Each sync method has a `TODO` comment — replace the placeholder SQL with your actual ERP table/column names:

| Method | Replace query in |
|---|---|
| `syncStock()` | `your_erp_stock_table` |
| `syncPartyStatus()` | `your_erp_party_master_table` |
| `syncSales()` | `your_erp_sales_table` |
| `syncDailySummary()` | `your_erp_sales_table` (aggregation) |
| `syncLedgerSummary()` | `your_erp_ledger_summary_table` |
| `syncCollections()` | `your_erp_receipts_table` |
| `syncStockMovements()` | `your_erp_stock_movement_table` |

---

## Running Locally

```bash
# Build
mvn clean install

# Run
mvn spring-boot:run
```

Server starts on `http://localhost:8080`

---

## API Endpoints

### POST /api/chat
Send a message and get a response.

**Request:**
```json
{
  "message": "What is today's sale?",
  "history": []
}
```

**Response:**
```json
{
  "success": true,
  "reply": "Today's total sales are ₹1,24,500 across 38 bills..."
}
```

### GET /api/health
Health check endpoint.

---

## Sample Prompts

```
What is the current stock of Tata Salt?
How much did we sell today?
What is the status of ABC Traders?
Show me all overdue parties
What was our total sale this month?
How many bills were raised today?
Get me the ledger of XYZ Company for this month
Which items are low on stock?
How much collection did we do today?
Show me the stock movement of Tata Salt this week
```

---

## Deploying to Render

1. Push code to GitHub
2. Create new Web Service on Render
3. Connect your GitHub repo
4. Set environment:
   - Build Command: `mvn clean install -DskipTests`
   - Start Command: `java -jar target/iam-erp-ai-1.0.0.jar`
5. Add environment variables (same as application.properties)

---

## Moving to GCP (Production)

When ready to move from Render to GCP:

| Current | GCP Equivalent |
|---|---|
| Render Web Service | Cloud Run |
| Render PostgreSQL | Cloud SQL |
| Render Cron | Cloud Scheduler + Cloud Run Job |
| Vercel Frontend | Cloud Run or Firebase Hosting |

---

## Project Structure

```
src/main/java/com/iamerp/ai/
├── MainApplication.java          Entry point
├── config/
│   └── AppConfig.java            DB connections + CORS
├── api/
│   └── ChatController.java       REST endpoints
├── gemini/
│   └── GeminiService.java        Gemini API + function calling
├── functions/
│   └── ErpFunctions.java         All ERP query functions
└── sync/
    └── SyncJob.java              2-minute sync from ERP to Supabase
```
