package app.auriel.edenlauncher.views

/**
 * Which grid cells of a page are taken. Backed by a flat [BooleanArray] rather than a 2-D array:
 * one allocation, one cache line per row, and no per-access bounds check on an inner array.
 *
 * Ported from `GridOccupancy` (AOSP 8; AOSP 7 kept the same data inline in `CellLayout`).
 */
class GridOccupancy(@JvmField val countX: Int, @JvmField val countY: Int) {

    private val cells = BooleanArray(countX * countY)

    operator fun get(x: Int, y: Int): Boolean =
        if (x in 0 until countX && y in 0 until countY) cells[y * countX + x] else true

    operator fun set(x: Int, y: Int, occupied: Boolean) {
        if (x in 0 until countX && y in 0 until countY) cells[y * countX + x] = occupied
    }

    fun markCells(cellX: Int, cellY: Int, spanX: Int, spanY: Int, occupied: Boolean) {
        if (cellX < 0 || cellY < 0) return
        for (x in cellX until min(cellX + spanX, countX)) {
            for (y in cellY until min(cellY + spanY, countY)) {
                cells[y * countX + x] = occupied
            }
        }
    }

    fun clear() = cells.fill(false)

    /** True when the [spanX] x [spanY] block anchored at ([cellX], [cellY]) is entirely free. */
    fun isRegionVacant(cellX: Int, cellY: Int, spanX: Int, spanY: Int): Boolean {
        if (cellX < 0 || cellY < 0 || cellX + spanX > countX || cellY + spanY > countY) return false
        for (x in cellX until cellX + spanX) {
            for (y in cellY until cellY + spanY) {
                if (cells[y * countX + x]) return false
            }
        }
        return true
    }

    /**
     * Finds the first free block in reading order and writes its origin into [outCell].
     * Returns false when the page is full.
     */
    fun findVacantCell(outCell: IntArray, spanX: Int, spanY: Int): Boolean {
        for (y in 0..countY - spanY) {
            for (x in 0..countX - spanX) {
                if (isRegionVacant(x, y, spanX, spanY)) {
                    outCell[0] = x
                    outCell[1] = y
                    return true
                }
            }
        }
        return false
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b
}
