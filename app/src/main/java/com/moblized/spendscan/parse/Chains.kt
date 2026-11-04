package com.moblized.spendscan.parse

/** Stores that print their name in a recognizable way, and where their receipts usually belong. */
object Chains {
    const val GROCERIES = "Groceries"
    const val DINING = "Dining out"
    const val AUTO = "Gas & auto"
    const val HOME = "Home"
    const val HEALTH = "Health"
    const val SHOPPING = "Shopping"
    const val FUN = "Fun"

    data class Chain(val name: String, val category: String, val pattern: Regex)

    private fun chain(name: String, category: String, pattern: String) =
        Chain(name, category, Regex("""(?<![a-z])(?:$pattern)(?![a-z])""", RegexOption.IGNORE_CASE))

    private val ALL = listOf(
        chain("Walmart", GROCERIES, """wal\s?[-*.]?\s?mart|wm supercenter"""),
        chain("Target", GROCERIES, """target"""),
        chain("Costco", GROCERIES, """costco"""),
        chain("Sam's Club", GROCERIES, """sam'?s club"""),
        chain("BJ's", GROCERIES, """bj'?s wholesale"""),
        chain("Kroger", GROCERIES, """kroger"""),
        chain("Publix", GROCERIES, """publix"""),
        chain("Aldi", GROCERIES, """aldi"""),
        chain("Lidl", GROCERIES, """lidl"""),
        chain("Whole Foods", GROCERIES, """whole\s?foods"""),
        chain("Trader Joe's", GROCERIES, """trader\s?joe'?s?"""),
        chain("Safeway", GROCERIES, """safeway"""),
        chain("Albertsons", GROCERIES, """albertsons"""),
        chain("Meijer", GROCERIES, """meijer"""),
        chain("H-E-B", GROCERIES, """h-?e-?b"""),
        chain("Wegmans", GROCERIES, """wegmans"""),
        chain("Harris Teeter", GROCERIES, """harris teeter"""),
        chain("Food Lion", GROCERIES, """food lion"""),
        chain("Giant", GROCERIES, """giant (food|eagle)"""),
        chain("Stop & Shop", GROCERIES, """stop\s?(&|and)\s?shop"""),
        chain("ShopRite", GROCERIES, """shop\s?rite"""),
        chain("Hannaford", GROCERIES, """hannaford"""),
        chain("Sprouts", GROCERIES, """sprouts"""),
        chain("WinCo", GROCERIES, """winco"""),
        chain("Dollar General", SHOPPING, """dollar general"""),
        chain("Dollar Tree", SHOPPING, """dollar tree"""),
        chain("Family Dollar", SHOPPING, """family dollar"""),
        chain("Five Below", SHOPPING, """five below"""),
        chain("Amazon", SHOPPING, """amazon"""),
        chain("Best Buy", SHOPPING, """best\s?buy"""),
        chain("Marshalls", SHOPPING, """marshalls"""),
        chain("T.J. Maxx", SHOPPING, """t\.?j\.?\s?maxx"""),
        chain("Ross", SHOPPING, """ross dress"""),
        chain("Kohl's", SHOPPING, """kohl'?s"""),
        chain("Macy's", SHOPPING, """macy'?s"""),
        chain("Old Navy", SHOPPING, """old navy"""),
        chain("Nike", SHOPPING, """nike"""),
        chain("Staples", SHOPPING, """staples"""),
        chain("Office Depot", SHOPPING, """office depot"""),
        chain("Barnes & Noble", SHOPPING, """barnes\s?(&|and)\s?noble"""),
        chain("Dick's Sporting Goods", SHOPPING, """dick'?s sporting"""),
        chain("Home Depot", HOME, """home\s?depot"""),
        chain("Lowe's", HOME, """lowe'?s"""),
        chain("Ace Hardware", HOME, """ace hardware"""),
        chain("Menards", HOME, """menards"""),
        chain("Harbor Freight", HOME, """harbor freight"""),
        chain("Tractor Supply", HOME, """tractor supply"""),
        chain("IKEA", HOME, """ikea"""),
        chain("HomeGoods", HOME, """home\s?goods"""),
        chain("PetSmart", HOME, """petsmart"""),
        chain("Petco", HOME, """petco"""),
        chain("CVS", HEALTH, """cvs"""),
        chain("Walgreens", HEALTH, """walgreens"""),
        chain("Rite Aid", HEALTH, """rite\s?aid"""),
        chain("Shell", AUTO, """shell"""),
        chain("Exxon", AUTO, """exxon(\s?mobil)?"""),
        chain("Mobil", AUTO, """mobil"""),
        chain("Chevron", AUTO, """chevron"""),
        chain("BP", AUTO, """bp"""),
        chain("Sunoco", AUTO, """sunoco"""),
        chain("Marathon", AUTO, """marathon"""),
        chain("Valero", AUTO, """valero"""),
        chain("Citgo", AUTO, """citgo"""),
        chain("Speedway", AUTO, """speedway"""),
        chain("Circle K", AUTO, """circle k"""),
        chain("Wawa", AUTO, """wawa"""),
        chain("Sheetz", AUTO, """sheetz"""),
        chain("QuikTrip", AUTO, """quik\s?trip"""),
        chain("RaceTrac", AUTO, """race\s?trac"""),
        chain("Love's", AUTO, """love'?s travel"""),
        chain("Pilot", AUTO, """pilot (travel|flying)"""),
        chain("AutoZone", AUTO, """auto\s?zone"""),
        chain("O'Reilly", AUTO, """o'?reilly"""),
        chain("Advance Auto Parts", AUTO, """advance auto"""),
        chain("NAPA", AUTO, """napa auto"""),
        chain("7-Eleven", AUTO, """7[- ]?eleven"""),
        chain("McDonald's", DINING, """mc\s?donald'?s?"""),
        chain("Burger King", DINING, """burger king"""),
        chain("Wendy's", DINING, """wendy'?s"""),
        chain("Taco Bell", DINING, """taco bell"""),
        chain("KFC", DINING, """kfc|kentucky fried"""),
        chain("Subway", DINING, """subway"""),
        chain("Chick-fil-A", DINING, """chick[- ]fil[- ]a"""),
        chain("Popeyes", DINING, """popeye'?s"""),
        chain("Arby's", DINING, """arby'?s"""),
        chain("Sonic", DINING, """sonic drive"""),
        chain("Dairy Queen", DINING, """dairy queen"""),
        chain("Five Guys", DINING, """five guys"""),
        chain("Shake Shack", DINING, """shake shack"""),
        chain("Chipotle", DINING, """chipotle"""),
        chain("Panera", DINING, """panera"""),
        chain("Starbucks", DINING, """starbucks"""),
        chain("Dunkin'", DINING, """dunkin'?"""),
        chain("Domino's", DINING, """domino'?s"""),
        chain("Pizza Hut", DINING, """pizza hut"""),
        chain("Papa Johns", DINING, """papa john'?s?"""),
        chain("Applebee's", DINING, """applebee'?s"""),
        chain("Olive Garden", DINING, """olive garden"""),
        chain("Chili's", DINING, """chili'?s"""),
        chain("Denny's", DINING, """denny'?s"""),
        chain("IHOP", DINING, """ihop"""),
        chain("Waffle House", DINING, """waffle house"""),
        chain("Cracker Barrel", DINING, """cracker barrel"""),
        chain("Texas Roadhouse", DINING, """texas roadhouse"""),
        chain("Regal", FUN, """regal cinemas?"""),
        chain("AMC", FUN, """amc theatres?"""),
    )

    /** Looks at the top of the receipt for a store it knows. */
    fun find(topRows: List<String>): Chain? {
        val text = topRows.joinToString("\n").replace('’', '\'')
        return ALL.firstOrNull { it.pattern.containsMatchIn(text) }
    }

    /** Looks up a store the person typed or an earlier receipt named. */
    fun categoryOfStore(store: String): String? = find(listOf(store))?.category

    private val WORDS = listOf(
        DINING to Regex("""\b(server|gratuity|guests?|dine in|take ?out|drive ?thru|table \d+)\b""", RegexOption.IGNORE_CASE),
        AUTO to Regex("""\b(unleaded|diesel|gallons|pump \d+|fuel)\b|\d\.\d{2,3}\s*gal\b""", RegexOption.IGNORE_CASE),
        HEALTH to Regex("""\b(pharmacy|rx|prescription|copay)\b""", RegexOption.IGNORE_CASE),
        GROCERIES to Regex("""\b(produce|grocery|deli|dairy|bakery|ebt|snap)\b""", RegexOption.IGNORE_CASE),
    )

    /** For a store it doesn't know, what the receipt's wording suggests. */
    fun categoryFromText(rows: List<String>): String? {
        val text = rows.joinToString("\n")
        return WORDS.firstOrNull { it.second.containsMatchIn(text) }?.first
    }
}
