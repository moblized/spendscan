package com.moblized.spendscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {
    private val today = LocalDate.of(2026, 11, 4)
    private fun parse(text: String) = ReceiptParser.parse(text.trimIndent().lines(), today)

    @Test
    fun bigBoxGrocery() {
        val r = parse(
            """
            Walmart
            Save money. Live better.
            (704) 555 - 0142
            MANAGER DANA REYES
            2610 N TRYON ST
            CHARLOTTE, NC 28206
            ST# 02114 OP# 009041 TE# 41 TR# 05312
            GV 2% MILK 007874235186 F 3.48 N
            BANANAS 000000004011 F 1.18 N
            1.71 lb @ 1 lb /0.69
            TIDE PODS 003700087134 14.97 T
            BREAD 007225003706 F 2.98 N
            SUBTOTAL 22.61
            TAX 1 7.250 % 1.09
            TOTAL 23.70
            VISA TEND 23.70
            ACCOUNT # **** **** **** 4242
            APPROVAL # 053871
            CHANGE DUE 0.00
            # ITEMS SOLD 4
            11/02/26 14:31:07
            """,
        )
        assertEquals("Walmart", r.store)
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(2370L, r.totalCents)
        assertEquals(2261L, r.subtotalCents)
        assertEquals(109L, r.taxCents)
        assertEquals("Visa 4242", r.payment)
        assertEquals(Chains.GROCERIES, r.suggestedCategory)
        assertEquals(listOf("GV 2% MILK", "BANANAS", "TIDE PODS", "BREAD"), r.items.map { it.name })
        assertEquals(2261L, r.items.sumOf { it.amountCents })
        assertTrue(r.unsure.isEmpty())
    }

    @Test
    fun warehouseClubWithInstantSavings() {
        val r = parse(
            """
            COSTCO WHOLESALE
            Matthews #1320
            E 1234567 KS WATER 40CT 4.49 A
            E 987654 ROTISSERIE 4.99 A
            E 555123 PAPER TOWEL 22.99 A
            432100 /555123 4.00-
            SUBTOTAL 28.47
            TAX 1.99
            **** TOTAL 30.46
            XXXXXXXXXXXX1881
            APPROVED - PURCHASE
            AID: A0000000031010
            Visa 30.46
            TOTAL NUMBER OF ITEMS SOLD = 3
            10/28/2026 17:02
            """,
        )
        assertEquals("Costco", r.store)
        assertEquals(3046L, r.totalCents)
        assertEquals(199L, r.taxCents)
        assertEquals(400L, r.discountCents)
        assertEquals(listOf("KS WATER 40CT", "ROTISSERIE", "PAPER TOWEL"), r.items.map { it.name })
        assertEquals("Visa 1881", r.payment)
        assertEquals(LocalDate.of(2026, 10, 28), r.date)
        assertTrue(r.unsure.isEmpty())
    }

    @Test
    fun restaurantIgnoresPrintedTipSuggestions() {
        val r = parse(
            """
            The Copper Fig
            417 East Blvd
            Charlotte, NC 28203
            Server: Maya   Table 12
            Guests: 2
            Nov 1, 2026 7:42 PM
            1 Burger 15.00
            2 Draft Beer 14.00
            1 Fries 5.00
            Subtotal $34.00
            Sales Tax $2.46
            Total $36.46
            Suggested Gratuity
            18% tip = $6.12 Total $42.58
            20% tip = $6.80 Total $43.26
            25% tip = $8.50 Total $44.96
            """,
        )
        assertEquals("The Copper Fig", r.store)
        assertEquals(LocalDate.of(2026, 11, 1), r.date)
        assertEquals(3646L, r.totalCents)
        assertEquals(246L, r.taxCents)
        assertNull(r.tipCents)
        assertEquals(Chains.DINING, r.suggestedCategory)
        assertEquals(listOf("Burger", "Draft Beer", "Fries"), r.items.map { it.name })
        assertEquals(listOf(1.0, 2.0, 1.0), r.items.map { it.quantity })
        // A name the app doesn't know is worth a look; the money adds up, so that isn't.
        assertEquals(setOf(ReceiptField.STORE), r.unsure)
    }

    @Test
    fun restaurantWithTipAddedCountsTheHigherTotal() {
        val r = parse(
            """
            OLIVE GARDEN
            Subtotal 40.00
            Tax 3.00
            Total 43.00
            Tip 8.00
            Total 51.00
            MASTERCARD ending in 5454 51.00
            11/03/2026
            """,
        )
        assertEquals("Olive Garden", r.store)
        assertEquals(5100L, r.totalCents)
        assertEquals(800L, r.tipCents)
        assertEquals("Mastercard 5454", r.payment)
        assertTrue(r.unsure.isEmpty())
    }

    @Test
    fun gasPumpWithoutSubtotal() {
        val r = parse(
            """
            SHELL
            1200 INDEPENDENCE BLVD
            CHARLOTTE NC 28204
            10/30/26  08:14 AM
            PUMP 5
            REGULAR
            12.345 GAL @ 3.459/GAL
            FUEL SALE $42.70
            DEBIT
            ************1234
            """,
        )
        assertEquals("Shell", r.store)
        assertEquals(4270L, r.totalCents)
        assertEquals(Chains.AUTO, r.suggestedCategory)
        assertEquals(LocalDate.of(2026, 10, 30), r.date)
        assertEquals("Debit 1234", r.payment)
    }

    @Test
    fun fuelReceiptsGiveGallonsAndPrice() {
        val pump = parse(
            """
            SHELL
            PUMP 5
            REGULAR
            12.345 GAL @ 3.459/GAL
            FUEL SALE ${'$'}42.70
            """,
        )
        assertEquals(ParsedFuel(12345, 3459), pump.fuel)

        fun fuelOf(text: String) = parse(text).fuel
        assertEquals(ParsedFuel(22400, 3759), fuelOf("Exxon\nGALLONS: 22.400\nPRICE/GAL ${'$'}3.759\nTOTAL ${'$'}84.20"))
        assertEquals(ParsedFuel(22400, 3759), fuelOf("Road Stop\nDIESEL 22.40G ${'$'}3.759/G\nTOTAL 84.20"))
        assertEquals(ParsedFuel(10000, 3599), fuelOf("Sunoco\nVolume 10.000\nPPG 3.599\nTotal 35.99"))
        assertEquals(ParsedFuel(15120, 3299), fuelOf("Pump 3\n15.120 @ 3.299\nTotal 49.88"))
        // No price printed: the gallons are still useful.
        assertEquals(ParsedFuel(12345, null), fuelOf("BP\n12.345 GAL\nTotal 42.70"))
    }

    @Test
    fun otherGallonsAreNotFuel() {
        assertNull(parse("KROGER\n2% MILK GAL 3.49\nTOTAL 3.49").fuel)
        assertNull(parse("KROGER\nMILK 1.00 GAL 3.49\nTOTAL 3.49").fuel)
        // A gas station that sells milk.
        assertNull(parse("WAWA\nMILK 1.00 GAL 3.49\nTOTAL 3.49").fuel)
        assertNull(parse("WAWA\nGAL MILK 3.49\nTOTAL 3.49").fuel)
        // Coffee and a snack at a gas station.
        assertNull(parse("SHELL\nCOFFEE 2.49\nCHIPS 1.99\nTOTAL 4.48").fuel)
        // Paint by the gallon at a hardware store.
        assertNull(parse("HOME DEPOT\nINTERIOR PAINT 1.000 GAL 34.98\nTOTAL 34.98").fuel)
        // Gallons that can't be right for the money.
        assertNull(parse("Pump 3\n50.000 GAL\nTotal 10.00").fuel)
    }

    @Test
    fun cashAndChangeNeverBecomeTheTotal() {
        val r = parse(
            """
            Corner Market
            Coffee 2.50
            Muffin 3.25
            Subtotal 5.75
            Tax 0.40
            Total 6.15
            CASH TENDERED 20.00
            CHANGE 13.85
            """,
        )
        assertEquals(615L, r.totalCents)
        assertEquals("Cash", r.payment)
        assertEquals(listOf("Coffee", "Muffin"), r.items.map { it.name })
    }

    @Test
    fun readingMistakesInsideNumbersAreFixed() {
        val r = parse(
            """
            KROGER
            EGGS 4.5O
            OATS I2.99
            SUBTOTAL 17.49
            TAX 1.O5
            TOTAL 18.54
            VISA 18.54
            """,
        )
        assertEquals(1854L, r.totalCents)
        assertEquals(105L, r.taxCents)
        assertEquals(listOf(450L, 1299L), r.items.map { it.amountCents })
        assertTrue(ReceiptField.TOTAL !in r.unsure)
    }

    @Test
    fun amountOnTheRowBelowTheWord() {
        val r = parse(
            """
            Hilltop Hardware
            Hinges 12.00
            TOTAL
            12.84
            """,
        )
        assertEquals(1284L, r.totalCents)
        assertEquals(listOf("Hinges"), r.items.map { it.name })
    }

    @Test
    fun nameOnOneRowPriceOnTheNext() {
        val r = parse(
            """
            KROGER
            ORGANIC BANANAS
            2.31 lb @ 0.69/lb 1.59
            HONEYCRISP APPLES
            3 @ 1.25 3.75
            2 @ 0.98
            YOGURT 1.96
            SUBTOTAL 7.30
            TOTAL 7.30
            """,
        )
        assertEquals(listOf("ORGANIC BANANAS", "HONEYCRISP APPLES", "YOGURT"), r.items.map { it.name })
        assertEquals(listOf(1.0, 3.0, 2.0), r.items.map { it.quantity })
        assertEquals(listOf(159L, 375L, 196L), r.items.map { it.amountCents })
        assertEquals(730L, r.totalCents)
    }

    @Test
    fun noWordTotalFallsBackAndAsksForACheck() {
        val r = parse(
            """
            Farm Stand
            Tomatoes 4.00
            Corn 3.00
            """,
        )
        assertEquals(400L, r.totalCents)
        assertTrue(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun cardChargeStandsInForAMissingTotal() {
        val r = parse(
            """
            CVS pharmacy
            Tylenol 8.99
            VISA 9.62
            """,
        )
        assertEquals("CVS", r.store)
        assertEquals(962L, r.totalCents)
        assertEquals(Chains.HEALTH, r.suggestedCategory)
        assertTrue(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun totalSavingsAndItemCountsAreNotTheTotal() {
        val r = parse(
            """
            PUBLIX
            Rice 5.00
            Beans 3.00
            Order Total 8.56
            Sales Tax 0.56
            Grand Total 8.56
            Your Savings at Publix 12.50
            Total Savings 12.50
            Total Items 2
            """,
        )
        assertEquals(856L, r.totalCents)
        assertEquals(1250L, r.discountCents)
    }

    @Test
    fun rewardsBalanceIsNotTheTotal() {
        val r = parse(
            """
            Bean Town Coffee
            Latte 5.25
            Total 5.67
            Rewards Balance 112.50
            Gift Card Balance 45.00
            """,
        )
        assertEquals(567L, r.totalCents)
    }

    @Test
    fun largeAmountsWithThousands() {
        val r = parse(
            """
            BEST BUY
            LG 65 OLED TV 1,799.99
            SUBTOTAL 1,799.99
            TAX 130.50
            TOTAL 1,930.49
            AMEX ending 1005 1,930.49
            """,
        )
        assertEquals(193049L, r.totalCents)
        assertEquals(179999L, r.subtotalCents)
        assertEquals("Amex 1005", r.payment)
        assertEquals(Chains.SHOPPING, r.suggestedCategory)
    }

    @Test
    fun dateFormats() {
        fun dateOf(row: String) = ReceiptParser.parse(listOf("Shop", row, "Total 1.00"), today).date
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("11/02/2026"))
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("11-02-26"))
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("2026-11-02"))
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("Nov 2, 2026"))
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("NOV 02 2026"))
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("2 Nov 2026"))
        assertEquals(LocalDate.of(2026, 11, 2), dateOf("November 2nd, 2026"))
        assertEquals(LocalDate.of(2026, 3, 25), dateOf("25/03/2026"))
    }

    @Test
    fun datesThatAreNotThePurchaseAreSkipped() {
        val r = parse(
            """
            Shop
            Coupon expires 12/31/2026
            Return by 01/15/2027
            Sold 10/20/2026 3:15 PM
            Total 5.00
            """,
        )
        assertEquals(LocalDate.of(2026, 10, 20), r.date)
    }

    @Test
    fun futureAndAncientDatesAreIgnored() {
        assertNull(parse("Shop\n01/01/2030\nTotal 2.00").date)
        assertNull(parse("Shop\n01/01/2001\nTotal 2.00").date)
        assertTrue(ReceiptField.DATE in parse("Shop\nTotal 2.00").unsure)
    }

    @Test
    fun rowsAreRebuiltFromSeparateColumns() {
        val lines = listOf(
            OcrLine("Milk", 40, 100, 200, 130),
            OcrLine("Bread", 40, 150, 220, 180),
            OcrLine("TOTAL", 40, 200, 180, 230),
            OcrLine("3.49", 500, 103, 590, 133),
            OcrLine("2.99", 500, 147, 590, 177),
            OcrLine("6.48", 500, 198, 590, 228),
        )
        assertEquals(listOf("Milk 3.49", "Bread 2.99", "TOTAL 6.48"), ReceiptParser.rows(lines))
    }

    @Test
    fun rowsAreRebuiltOnASlantedPhoto() {
        // About 5 degrees: the price sits a whole row lower than its name.
        val slope = 0.09
        fun at(text: String, x: Int, row: Int, width: Int): OcrLine {
            val top = 100 + row * 44 + (x * slope).toInt()
            return OcrLine(text, x, top, x + width, top + 30 + (width * slope).toInt(), slope, 30)
        }
        val lines = listOf(
            at("Milk", 40, 0, 120), at("Bread", 40, 1, 150), at("TOTAL", 40, 2, 140),
            at("3.49", 520, 0, 90), at("2.99", 520, 1, 90), at("6.48", 520, 2, 90),
        )
        assertEquals(listOf("Milk 3.49", "Bread 2.99", "TOTAL 6.48"), ReceiptParser.rows(lines))
    }

    @Test
    fun storeNamesAreTidied() {
        assertEquals("Blue Heron Books", parse("BLUE HERON BOOKS #204\nTotal 9.00").store)
        assertEquals("Trader Joe's", parse("TRADER JOE’S\nTotal 9.00").store)
        // Skips printed greetings to find the name.
        assertEquals("Maple Deli", parse("Welcome to\nMAPLE DELI\nTotal 9.00").store)
    }

    @Test
    fun departmentStoreWithTaxOnTheSameRow() {
        val r = parse(
            """
            TARGET
            Charlotte - 1234 Main St
            704-555-0100
            11/01/2026 06:12 PM
            GROCERY
            212000123 GG MILK NF 4.29
            071000456 CHEERIOS T 5.49
            HOME
            070000789 BOUNTY T 12.99
            SUBTOTAL ${'$'}22.77
            T = NC TAX 7.25% on ${'$'}18.48 ${'$'}1.34
            TOTAL ${'$'}24.11
            *1234 VISA CHARGE ${'$'}24.11
            """,
        )
        assertEquals("Target", r.store)
        assertEquals(2411L, r.totalCents)
        assertEquals(134L, r.taxCents)
        assertEquals(2277L, r.subtotalCents)
        assertEquals(listOf("GG MILK NF", "CHEERIOS", "BOUNTY"), r.items.map { it.name })
        assertEquals("Visa 1234", r.payment)
        assertTrue(r.unsure.isEmpty())
    }

    @Test
    fun balanceToPayWithQuantitiesBelowTheItem() {
        val r = parse(
            """
            TRADER JOE'S
            431 Fenton St
            R-CARROTS SHREDDED 10 OZ 1.29
            BANANA 0.58
            2 @ 0.29
            Items in Transaction: 3
            Balance to pay ${'$'}1.87
            Visa ${'$'}1.87
            11-02-2026 10:15AM
            """,
        )
        assertEquals("Trader Joe's", r.store)
        assertEquals(187L, r.totalCents)
        assertEquals(listOf("R-CARROTS SHREDDED 10 OZ", "BANANA"), r.items.map { it.name })
        assertEquals(listOf(1.0, 2.0), r.items.map { it.quantity })
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertTrue(r.unsure.isEmpty())
    }

    @Test
    fun hardwareStoreCodesAndMarkers() {
        val r = parse(
            """
            THE HOME DEPOT
            SALE
            012345678901 2X4 STUD 3.98
            2@1.99
            012345678902 SCREWS <A> 7.98
            SUBTOTAL 11.96
            SALES TAX 0.84
            TOTAL ${'$'}12.80
            XXXXXXXXXXXX1234 VISA
            USD${'$'} 12.80
            AUTH CODE 012345
            """,
        )
        assertEquals("Home Depot", r.store)
        assertEquals(1280L, r.totalCents)
        assertEquals(listOf("2X4 STUD", "SCREWS"), r.items.map { it.name })
        assertEquals(listOf(2.0, 1.0), r.items.map { it.quantity })
        assertEquals(Chains.HOME, r.suggestedCategory)
        assertEquals("Visa 1234", r.payment)
    }

    @Test
    fun wordsStuckToAmounts() {
        val r = parse(
            """
            Maple Deli
            Sandwich${'$'}9.00
            Subtotal:9.00
            Tax:${'$'}0.63
            TOTAL${'$'}9.63
            """,
        )
        assertEquals(963L, r.totalCents)
        assertEquals(63L, r.taxCents)
        assertEquals(900L, r.subtotalCents)
        assertEquals(listOf("Sandwich"), r.items.map { it.name })
        assertTrue(ReceiptField.TOTAL !in r.unsure)
    }

    @Test
    fun misreadDollarSignIsNotAFive() {
        val r = parse("Shop\nTea S3.00\nTOTAL S3.21")
        assertEquals(321L, r.totalCents)
        assertEquals(300L, r.items.single().amountCents)
    }

    @Test
    fun spacedOutAndMisreadTotalWords() {
        assertEquals(500L, parse("Shop\nT O T A L 5.00").totalCents)
        assertEquals(500L, parse("Shop\nT0TAL 5.00").totalCents)
        assertEquals(500L, parse("Shop\nTOTAI 5.00").totalCents)
        assertEquals(400L, parse("Shop\nSUBT0TAL 4.00\nTOTAL 4.28").subtotalCents)
    }

    @Test
    fun tipSuggestionsWithoutAHeading() {
        val r = parse(
            """
            Luna Cafe
            Soup 8.00
            Subtotal 8.00
            Tax 0.56
            Total 8.56
            15% 1.28 Total 9.84
            18% 1.54 Total 10.10
            20% 1.71 Total 10.27
            """,
        )
        assertEquals(856L, r.totalCents)
        assertNull(r.tipCents)
    }

    @Test
    fun cashHandedOverIsNotTheTotal() {
        val r = parse(
            """
            Corner Store
            Soda 2.00
            Total 2.14
            Total Paid 5.00
            Change 2.86
            """,
        )
        assertEquals(214L, r.totalCents)
    }

    @Test
    fun aBrandInTheItemsIsNotTheStore() {
        val r = parse(
            """
            Quick Stop Fuel
            STARBUCKS FRAPPUCCINO 3.99
            Total 4.27
            """,
        )
        assertEquals("Quick Stop Fuel", r.store)
    }

    @Test
    fun realProductsAreNotMistakenForNoise() {
        val r = parse(
            """
            Hilltop Market
            KOOL AID 2.99
            PHONE CASE 9.99
            CASHEWS 7.49
            TRANS FAT FREE SPREAD 3.25
            TABLE SALT 1.29
            Subtotal 25.01
            Total 25.01
            """,
        )
        assertEquals(listOf("KOOL AID", "PHONE CASE", "CASHEWS", "TRANS FAT FREE SPREAD", "TABLE SALT"), r.items.map { it.name })
    }

    @Test
    fun milkByTheGallonIsNotGas() {
        val r = parse("Hilltop Market\n2% MILK GAL 3.49\nTotal 3.49")
        assertNull(r.suggestedCategory)
        val pump = parse("Roadside Fuel\n12.345 GAL @ 3.459/GAL\nTotal 42.70")
        assertEquals(Chains.AUTO, pump.suggestedCategory)
    }

    @Test
    fun quantityWithPriceInTheName() {
        val r = parse("Cafe\n2 x Latte @ ${'$'}4.50 ${'$'}9.00\nTotal 9.00")
        assertEquals("Latte", r.items.single().name)
        assertEquals(2.0, r.items.single().quantity, 0.0)
        assertEquals(900L, r.items.single().amountCents)
    }

    @Test
    fun decimalCommas() {
        val r = parse("Cafe\nTea 3,50\nTotal 3,50")
        assertEquals(350L, r.totalCents)
    }

    @Test
    fun timeIsNotAnAmount() {
        val r = parse("Cafe\nOpen until 9.30 PM\nTea 3.50\nTotal 3.50")
        assertEquals(listOf("Tea"), r.items.map { it.name })
    }

    @Test
    fun emptyReceipt() {
        val r = ReceiptParser.parse(emptyList(), today)
        assertNull(r.totalCents)
        assertNull(r.store)
        assertTrue(r.items.isEmpty())
        assertEquals(setOf(ReceiptField.STORE, ReceiptField.DATE, ReceiptField.TOTAL), r.unsure)
    }
}
