package com.financialmanager.app.categorise

/**
 * Works out what a transaction was for.
 *
 * Three layers, first match wins:
 *   1. Rules the user wrote by hand.
 *   2. Payments to a person, which are transfers whatever the name looks like.
 *   3. Merchant keywords.
 *
 * A category set by hand is never overwritten by any of this.
 */
object Categoriser {

    enum class Kind { EXPENSE, INCOME, TRANSFER }

    data class CategoryDef(val name: String, val kind: Kind, val colour: Long, val icon: String)

    val DEFAULTS = listOf(
        CategoryDef("Groceries", Kind.EXPENSE, 0xFF4CAF50, "🛒"),
        CategoryDef("Restaurants & Cafés", Kind.EXPENSE, 0xFFFF7043, "🍽️"),
        CategoryDef("Transport", Kind.EXPENSE, 0xFF42A5F5, "🚆"),
        CategoryDef("Fuel & Parking", Kind.EXPENSE, 0xFF5C6BC0, "⛽"),
        CategoryDef("Shopping", Kind.EXPENSE, 0xFFEC407A, "🛍️"),
        CategoryDef("Bills & Utilities", Kind.EXPENSE, 0xFF26A69A, "💡"),
        CategoryDef("Rent & Mortgage", Kind.EXPENSE, 0xFF8D6E63, "🏠"),
        CategoryDef("Subscriptions", Kind.EXPENSE, 0xFFAB47BC, "🔁"),
        CategoryDef("Health & Fitness", Kind.EXPENSE, 0xFF66BB6A, "💊"),
        CategoryDef("Entertainment", Kind.EXPENSE, 0xFFFFA726, "🎬"),
        CategoryDef("Travel", Kind.EXPENSE, 0xFF29B6F6, "✈️"),
        CategoryDef("Cash & ATM", Kind.EXPENSE, 0xFF78909C, "💵"),
        CategoryDef("Fees & Charges", Kind.EXPENSE, 0xFFEF5350, "🏷️"),
        CategoryDef("Education", Kind.EXPENSE, 0xFF7E57C2, "📚"),
        CategoryDef("Gifts & Donations", Kind.EXPENSE, 0xFFF06292, "🎁"),
        CategoryDef("Insurance", Kind.EXPENSE, 0xFF546E7A, "🛡️"),
        CategoryDef("Savings & Investments", Kind.TRANSFER, 0xFF00897B, "📈"),
        CategoryDef("Transfers", Kind.TRANSFER, 0xFF90A4AE, "↔️"),
        CategoryDef("Salary", Kind.INCOME, 0xFF2E7D32, "💼"),
        CategoryDef("Other Income", Kind.INCOME, 0xFF43A047, "➕"),
        CategoryDef("Uncategorised", Kind.EXPENSE, 0xFF9E9E9E, "❓"),
    )

    /**
     * Keyword lists, matched whole-word against the merchant and description.
     * Whole-word matters: a plain substring test files NETFLIX under Transport,
     * because "tfl" hides inside "neTFLix".
     */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "Groceries" to listOf(
            "tesco", "sainsbury", "asda", "aldi", "lidl", "morrisons", "waitrose",
            "co-op", "coop food", "coop", "iceland", "whole foods", "spar",
            "carrefour", "mercadona", "albert heijn", "rewe", "edeka", "grocer",
            // Italian chains
            "eurospin", "esselunga", "conad", "pam", "in's mercato", "penny market",
            "bennet", "famila", "todis", "crai", "iper", "prix", "despar",
            "il gigante", "alimentari", "supermercato", "panificio",
        ),
        "Restaurants & Cafés" to listOf(
            "starbucks", "costa", "pret", "cafe", "café", "coffee", "mcdonald",
            "kfc", "burger", "pizza", "nando", "wagamama", "deliveroo", "uber eats",
            "ubereats", "just eat", "justeat", "restaurant", "greggs", "subway",
            "dominos", "bakery", "pub",
            // Italian: the bar is where the coffee happens
            "bar", "caffe", "caffè", "cafè", "trattoria", "osteria", "pizzeria",
            "ristorante", "gelateria", "pasticceria", "rosticceria", "paninoteca",
            "birreria", "enoteca", "forno", "glovo", "selecta",
        ),
        "Transport" to listOf(
            "tfl", "transport for london", "trainline", "national rail", "uber",
            "bolt", "lyft", "bus", "metro", "railway", "citymapper", "lime",
            "sncf", "db bahn", "taxi",
            // Italian rail, city transport and ticketing. "azienda trasporti"
            // has to win over "atm" below — Milan's bus company is called ATM.
            "trenitalia", "trenord", "italo treno", "my cicero", "mycicero",
            "azienda trasporti", "atac", "flixbus", "blablacar", "freenow",
            "autolinee", "autoservizi", "biglietto",
        ),
        "Fuel & Parking" to listOf(
            "shell", "bp", "esso", "texaco", "petrol", "fuel", "parking", "ringgo",
            "parkopedia", "euro garages", "gulf", "eni station", "q8", "tamoil",
            "autostrade", "telepass", "benzina", "carburante", "parcheggio",
        ),
        "Shopping" to listOf(
            "amazon", "ebay", "argos", "asos", "zara", "h&m", "primark", "ikea",
            "next retail", "john lewis", "aliexpress", "etsy", "shein", "decathlon",
            "uniqlo", "sports direct",
            "tigotà", "tigota", "action", "kik", "temu", "bershka", "media world",
            "mediaworld", "unieuro", "zalando", "acqua e sapone", "pull&bear",
            "stradivarius", "oysho", "flying tiger", "tedi", "leroy merlin",
            "tabaccheria", "cartoleria", "libreria", "profumeria",
        ),
        "Bills & Utilities" to listOf(
            "british gas", "octopus energy", "edf", "e.on", "eon", "ovo energy",
            "thames water", "water plc", "council tax", "vodafone", "o2", "ee",
            "three", "giffgaff", "virgin media", "sky uk", "bt group", "broadband",
            "electric", "utility",
            "iliad", "windtre", "wind tre", "fastweb", "ho mobile", "very mobile",
            "enel", "a2a", "acea", "sorgenia", "pagopa", "canone rai", "bolletta",
        ),
        "Rent & Mortgage" to listOf(
            "rent", "landlord", "mortgage", "letting", "property manage",
            "affitto", "locazione",
        ),
        "Subscriptions" to listOf(
            "netflix", "spotify", "disney", "apple.com/bill", "apple music",
            "icloud", "google storage", "youtube premium", "amazon prime",
            "audible", "patreon", "adobe", "microsoft 365", "openai", "notion",
            "dropbox", "github", "subscription", "claude", "anthropic",
        ),
        "Health & Fitness" to listOf(
            "pharmacy", "boots", "superdrug", "nhs", "dentist", "optician", "gym",
            "puregym", "fitness", "peloton", "clinic", "hospital", "doctor",
            "farmacia", "parafarmacia", "dentistico", "dentista", "ospedale",
            "poliambulatorio", "centro medico", "ottica",
        ),
        "Entertainment" to listOf(
            "cinema", "odeon", "vue", "cineworld", "theatre", "ticketmaster",
            "steam games", "playstation", "xbox", "nintendo", "eventbrite", "concert",
        ),
        "Travel" to listOf(
            "airbnb", "booking.com", "hotel", "hostel", "ryanair", "easyjet",
            "british airways", "wizz air", "lufthansa", "expedia", "skyscanner",
            "eurostar", "airlines", "airways", "flight",
        ),
        "Cash & ATM" to listOf("atm", "cash withdrawal", "cash at", "withdrawal"),
        "Fees & Charges" to listOf("fee", "charge", "interest charged", "overdraft", "commission"),
        "Education" to listOf(
            "university", "college", "tuition", "udemy", "coursera", "school",
            "student loan", "università", "universita", "politecnico", "telematica",
        ),
        "Gifts & Donations" to listOf(
            "charity", "donation", "justgiving", "gofundme", "oxfam", "unicef", "red cross",
        ),
        "Insurance" to listOf(
            "insurance", "aviva", "axa", "admiral", "direct line", "churchill", "hastings",
        ),
        "Savings & Investments" to listOf(
            "vanguard", "trading 212", "trading212", "freetrade", "coinbase",
            "kraken", "binance", "etoro", "hargreaves", "nutmeg", "isa", "pension",
            "vault", "savings", "trade republic", "revpoints", "spare change",
            "round-up", "roundup",
        ),
        "Salary" to listOf("salary", "payroll", "wages", "hmrc paye"),
        "Transfers" to listOf(
            "transfer", "to revolut", "from revolut", "top-up", "topup", "exchange",
            "faster payment",
        ),
    )

    /**
     * Lookarounds rather than \b, so keywords containing punctuation
     * ("apple.com/bill", "co-op", "h&m") still anchor correctly.
     */
    private val COMPILED: List<Pair<String, List<Regex>>> = KEYWORDS.map { (name, words) ->
        name to words.map {
            Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
        }
    }

    /**
     * Revolut labels a payment between people "To Anna Rossi" / "From Marco
     * Bianchi". The name is a person, not a shop, so merchant keywords must not
     * be matched against it.
     */
    private val PERSON_TRANSFER = Regex("""^(to|from)\s+\S""", RegexOption.IGNORE_CASE)

    /**
     * Chains whose name is too short or too common to search for inside a longer
     * string: "MD" is an Italian supermarket, but "Md Shamsuddin" is a person.
     * These match the whole merchant name and nothing less.
     */
    private val EXACT_MERCHANTS = mapOf("md" to "Groceries")

    private val TRANSFER_HINTS = listOf(
        "transfer to", "transfer from", "to savings", "own account", "internal",
    )

    data class Result(val category: String, val isTransfer: Boolean)

    fun categorise(description: String, merchant: String?, amountMinor: Long): Result {
        val haystack = listOfNotNull(description, merchant).joinToString(" ").lowercase()
        val isTransfer = TRANSFER_HINTS.any { haystack.contains(it) }

        if (PERSON_TRANSFER.containsMatchIn(description.trim())) {
            return Result("Transfers", true)
        }

        EXACT_MERCHANTS[(merchant ?: description).trim().lowercase()]?.let {
            return Result(it, isTransfer)
        }

        for ((name, patterns) in COMPILED) {
            if (patterns.any { it.containsMatchIn(haystack) }) return Result(name, isTransfer)
        }

        if (isTransfer) return Result("Transfers", true)
        if (amountMinor > 0) return Result("Other Income", false)
        return Result("Uncategorised", false)
    }
}
