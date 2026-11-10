package com.moblized.spendscan.parse

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A greyscale picture: one brightness from 0 to 255 per pixel, row by row. */
class Gray(val width: Int, val height: Int, val luma: IntArray) {
    init {
        require(luma.size == width * height) { "Expected ${width * height} pixels, got ${luma.size}" }
    }
}

/**
 * A row of digits off a display. [text] is the best reading, with a '?' for any digit that can't be told for sure.
 * [options] is every reading the picture allows, likeliest first, for [PumpReader] to pick the one that adds up.
 */
data class DigitRow(val text: String, val options: List<String>)

/**
 * Reads the "digital clock" digits on gas pump displays, which a text reader can't make sense of: each digit is up to
 * seven separate bars, usually slanted, lit on some pumps and dark on others, and often behind glare.
 *
 * What it leans on: every bar on a display is the same brightness, and thinner than anything reflected in the glass.
 * So it keeps only thin marks that reach that brightness, straightens the slant, gathers the bars into digits and rows,
 * and reads each digit from which of its seven places are filled.
 */
object SegmentReader {
    private const val MAX_SIDE = 900

    // Bar thicknesses to look for, as a share of the picture's shorter side. Each catches bars up to about half as thick.
    private val SCALES = doubleArrayOf(0.02, 0.04, 0.08, 0.16)

    // The least a bar has to stand out from what's around it, out of 255.
    private const val MIN_CONTRAST = 12

    fun read(image: Gray): List<DigitRow> {
        val g = shrink(image)
        var best: List<Number> = emptyList()
        for (bright in booleanArrayOf(true, false)) {
            val src = if (bright) g.luma else IntArray(g.luma.size) { 255 - g.luma[it] }
            // A pump's rows are often different sizes, and each is read best at the thickness of its own bars. So
            // every thickness has a go, and each spot on the display keeps whichever reading of it came out best.
            val found = ArrayList<Number>()
            for (scale in SCALES) {
                val k = max(5, (min(g.width, g.height) * scale).roundToInt())
                found += readAt(src, g.width, g.height, k)?.numbers.orEmpty()
            }
            val kept = ArrayList<Number>()
            for (number in found.sortedWith(compareBy({ -it.sure }, { it.unsure }))) {
                if (kept.none { it.overlaps(number) }) kept += number
            }
            if (kept.sumOf { it.score } > best.sumOf { it.score }) best = kept
        }
        if (best.isEmpty()) return emptyList()
        // Top to bottom, and left to right for numbers on the same line.
        val line = 0.5 * best.minOf { it.bottom - it.top }
        return best.sortedWith(compareBy({ ((it.top + it.bottom) / 2 / line).roundToInt() }, { it.left })).map { it.row }
    }

    /** One number read off the display, and where in the picture it was. */
    private class Number(val left: Double, val top: Double, val right: Double, val bottom: Double, val row: DigitRow, val sure: Int, val unsure: Int) {
        val score get() = 2 * sure - unsure

        fun overlaps(other: Number): Boolean {
            val down = min(bottom, other.bottom) - max(top, other.top)
            val across = min(right, other.right) - max(left, other.left)
            return across > 0 && down > 0.4 * min(bottom - top, other.bottom - other.top)
        }
    }

    private class Reading(val numbers: List<Number>) {
        val score = numbers.sumOf { it.score }
    }

    /** Averages blocks of pixels so the longest side is at most [MAX_SIDE]. */
    fun shrink(g: Gray): Gray {
        val factor = (max(g.width, g.height) + MAX_SIDE - 1) / MAX_SIDE
        if (factor <= 1) return g
        val w = g.width / factor
        val h = g.height / factor
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until factor) for (dx in 0 until factor) sum += g.luma[(y * factor + dy) * g.width + x * factor + dx]
            out[y * w + x] = sum / (factor * factor)
        }
        return Gray(w, h, out)
    }

    // The picture with everything thinner than a bar taken out

    /** The smallest (or largest) value within [r] steps along one line of the picture. */
    private fun slide(a: IntArray, start: Int, step: Int, n: Int, r: Int, smallest: Boolean, out: IntArray, queue: IntArray) {
        var head = 0
        var tail = 0
        var next = 0
        for (i in 0 until n) {
            val last = min(n - 1, i + r)
            while (next <= last) {
                val v = a[start + next * step]
                while (tail > head) {
                    val back = a[start + queue[tail - 1] * step]
                    if (if (smallest) back >= v else back <= v) tail-- else break
                }
                queue[tail++] = next
                next++
            }
            while (queue[head] < i - r) head++
            out[start + i * step] = a[start + queue[head] * step]
        }
    }

    private fun spread(a: IntArray, w: Int, h: Int, r: Int, smallest: Boolean): IntArray {
        val across = IntArray(w * h)
        val out = IntArray(w * h)
        val queue = IntArray(max(w, h))
        for (y in 0 until h) slide(a, y * w, 1, w, r, smallest, across, queue)
        for (x in 0 until w) slide(across, x, w, h, r, smallest, out, queue)
        return out
    }

    /** What's left of the picture when every bright mark narrower than [k] is wiped away: the background, glare and all. */
    private fun withoutThinMarks(src: IntArray, w: Int, h: Int, k: Int): IntArray {
        val r = k / 2
        return spread(spread(src, w, h, r, smallest = true), w, h, r, smallest = false)
    }

    /**
     * Marks the pixels that belong to bars: thin, and nearer the brightness of the display's digits than of whatever is
     * behind them. Glare is bright too, but it's wide and soft, so it counts as background. Null if nothing stands out.
     */
    private fun mask(src: IntArray, w: Int, h: Int, k: Int): BooleanArray? {
        val background = withoutThinMarks(src, w, h, k)
        val counts = IntArray(256)
        for (i in src.indices) counts[src[i] - background[i]]++
        // How far the clearest half a percent of thin marks stand out.
        var left = src.size / 200
        var standout = 255
        while (standout > 0 && left > counts[standout]) left -= counts[standout--]
        if (standout < 2 * MIN_CONTRAST) return null

        // The brightness of the digits themselves: the middle of the clearest marks.
        val levels = IntArray(256)
        var clear = 0
        for (i in src.indices) if (src[i] - background[i] >= standout * 0.6) {
            levels[src[i]]++
            clear++
        }
        var level = 0
        var seen = 0
        while (level < 255 && seen + levels[level] <= clear / 2) seen += levels[level++]

        // Glare lifts everything it covers. A lit digit under it is still as bright as the rest, though it stands out
        // less; a dark one stands out as much as ever, though it's no longer as dark. Either is enough.
        return BooleanArray(src.size) { i ->
            val over = src[i] - background[i]
            over >= MIN_CONTRAST && (over >= 0.55 * (level - background[i]) || over >= 0.5 * standout)
        }
    }

    // Marks

    /** A patch of joined-up marked pixels. */
    private class Blob(val label: Int) {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var area = 0

        // Its edges once the picture is straightened: the digits' lean taken out, and the display set level.
        var left = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var bottom = -Double.MAX_VALUE

        // How thick its strokes are.
        var thickness = 1.0

        val runsAcross = ArrayList<Int>()
        val runsDown = ArrayList<Int>()

        val height get() = bottom - top + 1
        val width get() = right - left + 1
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
    }

    /**
     * The picture straightened out. Across, a point moves by how far up it is times the digits' [lean]; down, by how far
     * along it is times the display's [tilt]. After that, upright bars stand in columns and rows of digits run level.
     */
    private class Frame(val w: Int, val h: Int, val lean: Double, val tilt: Double) {
        fun across(x: Int, y: Int) = x + lean * (y - h / 2.0)
        fun down(x: Int, y: Int) = y - tilt * (x - w / 2.0)

        /** Where a straightened point is in the picture. */
        fun x(across: Double, down: Double): Double {
            val x = across - lean * (down - h / 2.0)
            return across - lean * (down + tilt * (x - w / 2.0) - h / 2.0)
        }

        fun y(across: Double, down: Double) = down + tilt * (x(across, down) - w / 2.0)

        /** The same, as an index into the picture's pixels, or -1 if it's off the edge. */
        fun pixel(across: Double, down: Double): Int {
            val column = x(across, down).roundToInt()
            val row = y(across, down).roundToInt()
            return if (column < 0 || row < 0 || column >= w || row >= h) -1 else row * w + column
        }
    }

    private fun blobs(mask: BooleanArray, w: Int, h: Int): Pair<IntArray, List<Blob>> {
        val labels = IntArray(w * h) { -1 }
        val found = ArrayList<Blob>()
        val stack = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start] || labels[start] >= 0) continue
            val blob = Blob(found.size)
            found += blob
            var top = 0
            stack[top++] = start
            labels[start] = blob.label
            while (top > 0) {
                val p = stack[--top]
                val x = p % w
                val y = p / w
                blob.area++
                if (x < blob.minX) blob.minX = x
                if (x > blob.maxX) blob.maxX = x
                if (y < blob.minY) blob.minY = y
                if (y > blob.maxY) blob.maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (mask[q] && labels[q] < 0) {
                        labels[q] = blob.label
                        stack[top++] = q
                    }
                }
            }
        }
        return labels to found
    }

    private fun median(values: List<Double>): Double? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    private fun middle(values: List<Int>): Int = values.sorted()[values.size / 2]

    /** Works out each mark's stroke thickness: the usual length of the short way through it. */
    private fun measure(labels: IntArray, all: List<Blob>, w: Int, h: Int) {
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                val label = labels[y * w + x]
                var end = x + 1
                while (end < w && labels[y * w + end] == label) end++
                if (label >= 0) all[label].runsAcross += end - x
                x = end
            }
        }
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                val label = labels[y * w + x]
                var end = y + 1
                while (end < h && labels[end * w + x] == label) end++
                if (label >= 0) all[label].runsDown += end - y
                y = end
            }
        }
        for (b in all) b.thickness = min(middle(b.runsAcross), middle(b.runsDown)).toDouble()
    }

    /**
     * How far the digits lean: the slant that, taken out, makes each mark stand in the fewest columns. Positive when
     * the tops lean to the right, as on nearly all displays.
     */
    private fun lean(labels: IntArray, all: List<Blob>, keep: BooleanArray, w: Int): Double {
        // Each mark's pixels, measured from its own corner so that marks don't count against each other.
        val counts = IntArray(all.size)
        var total = 0
        for (label in labels) if (label >= 0 && keep[label]) {
            counts[label]++
            total++
        }
        if (total == 0) return 0.0
        val every = max(1, total / 60_000)
        val starts = IntArray(all.size + 1)
        for (i in all.indices) starts[i + 1] = starts[i] + (counts[i] + every - 1) / every
        val xs = IntArray(starts[all.size])
        val ys = IntArray(starts[all.size])
        val filled = IntArray(all.size)
        val skipped = IntArray(all.size)
        for (i in labels.indices) {
            val label = labels[i]
            if (label < 0 || !keep[label]) continue
            if (skipped[label]++ % every != 0) continue
            val at = starts[label] + filled[label]++
            xs[at] = i % w - all[label].minX
            ys[at] = i / w - all[label].minY
        }
        val columns = IntArray(all.maxOf { it.maxX - it.minX } + 2 * all.maxOf { it.maxY - it.minY } + 4)
        var best = 0.0
        var bestEnergy = -1L
        // From upright outwards, so that a tie goes to the smaller lean.
        for (step in 0..40) {
            val lean = (if (step % 2 == 0) step / 2 else -(step + 1) / 2) * 0.02
            var energy = 0L
            for (label in all.indices) {
                val from = starts[label]
                val to = starts[label] + filled[label]
                if (to - from < 8) continue
                val b = all[label]
                val tall = b.maxY - b.minY
                val span = b.maxX - b.minX + 2 * tall + 2
                for (c in 0..span) columns[c] = 0
                for (i in from until to) columns[(xs[i] + lean * ys[i] + tall).roundToInt()]++
                for (c in 0..span) energy += columns[c].toLong() * columns[c]
            }
            if (energy > bestEnergy) {
                bestEnergy = energy
                best = lean
            }
        }
        return best
    }

    // Digits

    // Which of the seven bars each digit lights: top, top right, bottom right, bottom, bottom left, top left, middle.
    private const val BARS = "abcdefg"
    private val DIGITS: List<Pair<Char, String>> = listOf(
        '0' to "abcdef",
        '1' to "bc",
        '2' to "abdeg",
        '3' to "abcdg",
        '4' to "bcfg",
        '5' to "acdfg",
        '6' to "acdefg",
        '6' to "cdefg",
        '7' to "abc",
        '7' to "abcf",
        '8' to "abcdefg",
        '9' to "abcdfg",
        '9' to "abcfg",
    )

    /** The digits that are a single bar away from [digit]: what it would be read as with one bar missed or one too many. */
    fun oneBarFrom(digit: Char): List<Char> = DIGITS.filter { it.first == digit }.flatMap { (_, lit) ->
        DIGITS.filter { (other, bars) -> other != digit && (lit.toSet() - bars.toSet()).size + (bars.toSet() - lit.toSet()).size == 1 }.map { it.first }
    }.distinct()

    /** Bars gathered into one digit, or as much of one as could be seen. */
    private class Glyph(val parts: List<Blob>) {
        val left = parts.minOf { it.left }
        val right = parts.maxOf { it.right }
        val top = parts.minOf { it.top }
        val bottom = parts.maxOf { it.bottom }
        val height = bottom - top + 1
        val width = right - left + 1
        val thickness = median(parts.map { it.thickness }) ?: 1.0
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2

        // Filled in once its row is known. For each column: the longest unbroken run of its pixels above the foot of
        // the row, which is long only where an upright bar stands, and how many of its pixels lie along the foot,
        // where a decimal point that has run into the digit shows up past the last bar.
        var footAt = 0.0
        val run = IntArray(width.toInt() + 3)
        val running = IntArray(width.toInt() + 3)
        val seenAt = IntArray(width.toInt() + 3) { -9 }
        val along = IntArray(width.toInt() + 3)
        var barLeft = left
        var barRight = right
        var dotted = false
    }

    private class Row(val glyphs: MutableList<Glyph>) {
        // To begin with, the tallest digit's; then the top and foot most of its digits share.
        var top = glyphs.first().top
        var bottom = glyphs.first().bottom
        val height get() = bottom - top + 1
        val cy get() = (top + bottom) / 2
    }

    /**
     * One place a digit goes. [options] are the digits it could be, likeliest first; empty if it's none of them.
     * [miss] is how far what's there is from the nearest digit: 0 for a clean one, 1 for a bar out.
     */
    private class Cell(val right: Double, val options: List<Char>, val sure: Boolean, val blank: Boolean, val miss: Double)

    /** The marks that could be part of one row's digits: about the right size and thickness. */
    private class Ink(val labels: IntArray, val all: List<Blob>, val frame: Frame) {
        var tallest = 0.0
        var widest = 0.0
        var thinnest = 0.0
        var thickest = 0.0

        fun at(across: Double, down: Double): Boolean {
            val pixel = frame.pixel(across, down)
            if (pixel < 0) return false
            val label = labels[pixel]
            if (label < 0) return false
            val b = all[label]
            return b.height <= tallest && b.width <= widest && b.thickness >= thinnest && b.thickness <= thickest
        }
    }

    // How far the display might be turned in the photo: each is tried, and the one that reads best is kept.
    private val TILTS = doubleArrayOf(0.0, -0.035, 0.035, -0.07, 0.07, -0.105, 0.105)

    private fun readAt(src: IntArray, w: Int, h: Int, k: Int): Reading? {
        val mask = mask(src, w, h, k) ?: return null
        val (labels, all) = blobs(mask, w, h)
        if (all.isEmpty() || all.size > 20_000) return null
        measure(labels, all, w, h)

        // Not specks or scratches, not the frame around the screen, and not anything cut off by the edge of the picture.
        val minArea = max(6, w * h / 100_000)
        val usable = BooleanArray(all.size) { i ->
            val b = all[i]
            b.area >= minArea && b.thickness >= 2 && b.maxY - b.minY < h * 0.85 && b.maxX - b.minX < w * 0.85 &&
                b.minX > 0 && b.minY > 0 && b.maxX < w - 1 && b.maxY < h - 1
        }
        val lean = lean(labels, all, usable, w)
        var best: Reading? = null
        for (tilt in TILTS) {
            val reading = decode(labels, all, usable, Frame(w, h, lean, tilt)) ?: continue
            val current = best
            if (current == null || reading.score > current.score) best = reading
        }
        return best
    }

    private fun decode(labels: IntArray, all: List<Blob>, usable: BooleanArray, frame: Frame): Reading? {
        val w = frame.w
        for (b in all) {
            b.left = Double.MAX_VALUE
            b.right = -Double.MAX_VALUE
            b.top = Double.MAX_VALUE
            b.bottom = -Double.MAX_VALUE
        }
        for (i in labels.indices) {
            val label = labels[i]
            if (label < 0) continue
            val b = all[label]
            val across = frame.across(i % w, i / w)
            val down = frame.down(i % w, i / w)
            if (across < b.left) b.left = across
            if (across > b.right) b.right = across
            if (down < b.top) b.top = down
            if (down > b.bottom) b.bottom = down
        }
        val marks = all.filter { usable[it.label] }.sortedByDescending { it.area }.take(500)

        // Decimal points: small, solid and round, beside something much taller.
        val points = marks.filter { m ->
            val size = max(m.width, m.height)
            val round = size <= 1.7 * min(m.width, m.height) && m.area >= 0.55 * m.width * m.height
            round && marks.any { it !== m && it.height >= 1.8 * m.height && abs(it.cx - m.cx) < 4.0 * it.height && m.cy > it.top && m.cy < it.bottom + it.height }
        }
        val bars = marks.filter { it !in points }

        // Bars that all but touch, and are equally thick, make up one digit.
        val group = IntArray(bars.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (group[r] != r) r = group[r]
            group[i] = r
            return r
        }
        for (i in bars.indices) for (j in i + 1 until bars.size) {
            val a = bars[i]
            val b = bars[j]
            val thin = min(a.thickness, b.thickness)
            if (max(a.thickness, b.thickness) > 2.2 * thin) continue
            val reach = 0.5 * thin + 1
            val gapX = max(a.left, b.left) - min(a.right, b.right) - 1
            val gapY = max(a.top, b.top) - min(a.bottom, b.bottom) - 1
            // The two bars of a one, one above the other, have the middle bar's empty spot between them.
            val stacked = -gapX >= 0.5 * min(a.width, b.width) && gapY <= 1.5 * thin + 2
            if (gapX <= reach && (gapY <= reach || stacked)) group[find(i)] = find(j)
        }
        val glyphs = bars.indices.groupBy { find(it) }.values.map { members -> Glyph(members.map { bars[it] }) }

        // Whole digits: much taller than their bars are thick, and taller than wide. Rows are digits of one height, side by side.
        val whole = glyphs.filter { it.height >= max(10.0, 4.5 * it.thickness) && it.width <= it.height * 0.95 }
            .sortedByDescending { it.height }
        val rows = ArrayList<Row>()
        for (glyph in whole) {
            // On displays with square-ended bars, a one or a four stops a bar's thickness short at the top and bottom.
            val row = rows.firstOrNull { abs(glyph.cy - it.cy) <= 0.25 * it.height && glyph.height >= 0.6 * it.height }
            if (row != null) row.glyphs += glyph else rows += Row(mutableListOf(glyph))
        }
        if (rows.isEmpty()) return null

        // Each row's top and foot, going by most of its digits so that one caught up in glare doesn't move them. Then
        // which columns of each digit hold its upright bars.
        val glyphOf = arrayOfNulls<Glyph>(all.size)
        for (row in rows) {
            val quarter = (row.glyphs.size - 1) / 4
            val top = row.glyphs.map { it.top }.sorted()[quarter]
            val bottom = row.glyphs.map { it.bottom }.sorted()[row.glyphs.size - 1 - quarter]
            row.top = top
            row.bottom = bottom
            for (g in row.glyphs) {
                g.footAt = row.bottom - 0.15 * row.height
                for (part in g.parts) glyphOf[part.label] = g
            }
        }
        for (i in labels.indices) {
            val label = labels[i]
            if (label < 0) continue
            val g = glyphOf[label] ?: continue
            val y = i / w
            val column = (frame.across(i % w, y) - g.left).roundToInt()
            if (column < 0 || column >= g.run.size) continue
            if (frame.down(i % w, y) >= g.footAt) {
                g.along[column]++
                continue
            }
            // Rows come in order, so a column's pixels do too. A one-pixel break is only the slant's rounding.
            g.running[column] = if (y - g.seenAt[column] <= 2) g.running[column] + y - g.seenAt[column] else 1
            g.seenAt[column] = y
            if (g.running[column] > g.run[column]) g.run[column] = g.running[column]
        }
        for (row in rows) for (g in row.glyphs) {
            val need = 0.22 * row.height
            val first = g.run.indexOfFirst { it >= need }
            val last = g.run.indexOfLast { it >= need }
            if (first < 0) continue
            g.barLeft = g.left + first
            g.barRight = g.left + last
            var past = 0
            for (c in (last + 0.3 * g.thickness).roundToInt() + 1 until g.along.size) past += g.along[c]
            g.dotted = g.right - g.barRight >= 0.6 * g.thickness && past >= 0.35 * g.thickness * g.thickness
        }

        val out = ArrayList<Number>()
        // Left, top, right and bottom of each number read so far. Rows come tallest first.
        val taken = ArrayList<DoubleArray>()
        val ink = Ink(labels, all, frame)
        for (row in rows) {
            val digits = row.glyphs.sortedBy { it.barRight }
            // Bits of glare and dirt in among bigger digits aren't a row of their own.
            if (taken.any { row.cy > it[1] && row.cy < it[3] && digits.first().left < it[2] && digits.last().right > it[0] }) continue
            val stroke = median(digits.map { it.thickness }) ?: continue
            ink.tallest = 1.35 * row.height + 2
            ink.widest = 2.2 * row.height
            ink.thinnest = 0.45 * stroke
            ink.thickest = 2.2 * stroke

            // A digit is as wide as from its left bars to its right ones. A one, three or seven has no left bars to go by.
            val across = digits.map { it.barRight - it.barLeft + 1 }.filter { it >= 0.35 * row.height }
            val reach = digits.map { it.barRight - it.left + 1 + 0.3 * stroke }.filter { it >= 0.35 * row.height }
            val digitWidth = (median(across) ?: reach.maxOrNull() ?: (0.55 * row.height)).coerceIn(0.3 * row.height, 0.8 * row.height)

            // Digits sit a fixed step apart, which says where to look for one that glare has broken up.
            val rights = digits.map { it.barRight }
            val usual = 1.38 * digitWidth
            val steps = rights.zipWithNext { a, b -> b - a }.filter { it >= 0.8 * digitWidth }
            val step = (median(steps.map { it / max(1, (it / usual).roundToInt()) }) ?: usual).coerceIn(1.1 * digitWidth, 1.9 * digitWidth)
            val places = ArrayList<Pair<Double, Glyph?>>()
            for ((i, glyph) in digits.withIndex()) {
                if (i > 0) {
                    val from = places.last().first
                    val gap = glyph.barRight - from
                    if (gap < 0.6 * step) continue
                    val between = (gap / step).roundToInt()
                    for (j in 1 until between) places += from + gap * j / between to null
                }
                places += glyph.barRight to glyph
            }

            // The row's top and foot come from its digits, but a row of ones, fours and sevens has no bar along the
            // foot to show where it is, and on some displays their upright bars stop short of it. So the row is also
            // tried a little taller each way, and the fit that leaves the digits cleanest is kept.
            val firstTop = row.top
            val firstBottom = row.bottom
            var top = firstTop
            var bottom = firstBottom
            fun cellAt(right: Double) = cell(ink, right - digitWidth + 1, right, top, bottom)
            var cells = ArrayList<Cell>()
            places.mapTo(cells) { cellAt(it.first) }
            var miss = cells.sumOf { it.miss }
            if (miss > 0.25 * cells.size) {
                val more = 1.3 * stroke
                for ((up, down) in listOf(0.0 to more, more to 0.0, more to more)) {
                    top = firstTop - up
                    bottom = firstBottom + down
                    val tried = ArrayList<Cell>()
                    places.mapTo(tried) { cellAt(it.first) }
                    if (tried.sumOf { it.miss } < miss - 0.25) {
                        miss = tried.sumOf { it.miss }
                        cells = tried
                        row.top = top
                        row.bottom = bottom
                    }
                }
                top = row.top
                bottom = row.bottom
            }
            val dottedCells = ArrayList<Boolean>()
            places.mapTo(dottedCells) { it.second?.dotted == true }
            // Past each end, as long as there's something of a digit there.
            for (direction in intArrayOf(-1, 1)) {
                var right = (if (direction < 0) places.first().first else places.last().first) + direction * step
                for (more in 1..12) {
                    val next = cellAt(right)
                    if (next.blank || next.options.isEmpty()) break
                    if (direction < 0) {
                        cells.add(0, next)
                        dottedCells.add(0, false)
                    } else {
                        cells += next
                        dottedCells += false
                    }
                    right += direction * step
                }
            }

            // Where a decimal point could go: after the digit it follows, along the foot of the row. How sure, by its size.
            val dots = HashMap<Int, Double>()
            for (dot in points) {
                val size = max(dot.width, dot.height)
                if (size < 0.35 * stroke || size > 2.5 * stroke) continue
                if (dot.cy < row.bottom - 0.25 * row.height || dot.cy > row.bottom + 0.12 * row.height) continue
                val after = cells.indexOfLast { it.right <= dot.cx + 0.3 * size }
                if (after < 0 || dot.cx > cells[after].right + 0.75 * step) continue
                // Inside the next digit's own space it's a piece of that digit.
                if (after + 1 < cells.size && dot.cx > cells[after + 1].right - 0.7 * digitWidth) continue
                dots[after] = max(dots[after] ?: 0.0, dot.area + stroke * stroke)
            }
            dottedCells.forEachIndexed { i, dotted -> if (dotted && i !in dots) dots[i] = 0.5 * stroke * stroke }

            // An empty place, or a sign or letter that's no digit, ends one number and starts the next.
            var from = 0
            while (from < cells.size) {
                if (cells[from].blank || cells[from].options.isEmpty()) {
                    from++
                    continue
                }
                var to = from
                while (to + 1 < cells.size && !cells[to + 1].blank && cells[to + 1].options.isNotEmpty()) to++
                val number = cells.subList(from, to + 1)
                val sure = number.count { it.sure }
                // Edges, dividers and frames all read as ones, so a number has to have some other digit to be believed. And
                // printed words beside the display have the odd letter that passes for a digit, but never most of them.
                val enough = if (number.size <= 3) 2 * sure > number.size else 2 * sure >= number.size
                if (number.size >= 2 && enough && number.any { it.sure && it.options.first() != '1' }) {
                    // A number has one decimal point.
                    val dotAt = (from until to).filter { it in dots }.maxByOrNull { dots.getValue(it) }
                    var choices = listOf("")
                    val text = StringBuilder()
                    number.forEachIndexed { i, cell ->
                        val dot = if (from + i == dotAt) "." else ""
                        text.append(if (cell.sure) cell.options.first() else '?').append(dot)
                        choices = choices.flatMap { start -> cell.options.map { start + it + dot } }.take(MAX_OPTIONS)
                    }
                    val left = number.first().right - digitWidth
                    val right = number.last().right
                    taken += doubleArrayOf(left, row.top - 0.1 * row.height, right, row.bottom + 0.1 * row.height)
                    // Where it is in the picture itself, to set against what other thicknesses found.
                    val xs = doubleArrayOf(frame.x(left, row.top), frame.x(right, row.top), frame.x(left, row.bottom), frame.x(right, row.bottom))
                    val ys = doubleArrayOf(frame.y(left, row.top), frame.y(right, row.top), frame.y(left, row.bottom), frame.y(right, row.bottom))
                    out += Number(xs.min(), ys.min(), xs.max(), ys.max(), DigitRow(text.toString(), choices), sure, number.size - sure)
                }
                from = to + 1
            }
        }
        return if (out.isEmpty()) null else Reading(out)
    }

    private const val MAX_OPTIONS = 24

    /**
     * Reads the digit in one place from how full each of its seven bars' spots is. A bar that's there fills a line
     * straight through its spot; glare or dirt only ever fills part of one.
     */
    private fun cell(ink: Ink, left: Double, right: Double, top: Double, bottom: Double): Cell {
        val width = right - left
        val height = bottom - top

        // A bar lying flat: the fullest row across the middle of the digit, between these heights.
        fun flat(from: Double, to: Double): Double {
            val x0 = (left + 0.3 * width).roundToInt()
            val x1 = (left + 0.7 * width).roundToInt()
            var best = 0.0
            for (y in (top + from * height).roundToInt()..(top + to * height).roundToInt()) {
                var on = 0
                for (x in x0..x1) if (ink.at(x.toDouble(), y.toDouble())) on++
                best = max(best, on.toDouble() / (x1 - x0 + 1))
            }
            return best
        }

        // A bar standing up: the fullest column near one side, between these heights.
        fun standing(rightSide: Boolean, from: Double, to: Double): Double {
            val x0 = (if (rightSide) right - 0.35 * width else left - 0.08 * width).roundToInt()
            val x1 = (if (rightSide) right + 0.08 * width else left + 0.35 * width).roundToInt()
            val y0 = (top + from * height).roundToInt()
            val y1 = (top + to * height).roundToInt()
            var best = 0.0
            for (x in x0..x1) {
                var on = 0
                for (y in y0..y1) if (ink.at(x.toDouble(), y.toDouble())) on++
                best = max(best, on.toDouble() / (y1 - y0 + 1))
            }
            return best
        }

        val fill = doubleArrayOf(
            flat(-0.04, 0.2),
            standing(true, 0.2, 0.4),
            standing(true, 0.6, 0.8),
            flat(0.8, 1.04),
            standing(false, 0.6, 0.8),
            standing(false, 0.2, 0.4),
            flat(0.37, 0.63),
        )
        if (fill.sum() < 0.6) return Cell(right, emptyList(), sure = false, blank = true, miss = 0.0)

        // Every digit is open in the middle of its top half and its bottom half. Something solid there is no digit.
        var inside = 0
        var total = 0
        for (hole in doubleArrayOf(0.3, 0.7)) {
            for (y in (top + (hole - 0.05) * height).roundToInt()..(top + (hole + 0.05) * height).roundToInt()) {
                for (x in (left + 0.36 * width).roundToInt()..(left + 0.64 * width).roundToInt()) {
                    total++
                    if (ink.at(x.toDouble(), y.toDouble())) inside++
                }
            }
        }
        if (inside > 0.35 * total) return Cell(right, emptyList(), sure = false, blank = false, miss = 3.0)

        // How far the picture is from each digit, counting a half-filled bar as half wrong either way.
        val distance = HashMap<Char, Double>()
        for ((digit, lit) in DIGITS) {
            var d = 0.0
            for (i in BARS.indices) d += abs((if (BARS[i] in lit) 1.0 else 0.0) - fill[i])
            val known = distance[digit]
            if (known == null || d < known) distance[digit] = d
        }
        val ranked = distance.entries.sortedBy { it.value }
        val best = ranked[0].value
        val options = ranked.filter { it.value <= best + 0.8 && it.value <= 2.0 }.map { it.key }.take(4)
        val sure = best <= 0.75 && ranked[1].value >= best + 0.5
        return Cell(right, options, sure, blank = false, miss = best)
    }
}
