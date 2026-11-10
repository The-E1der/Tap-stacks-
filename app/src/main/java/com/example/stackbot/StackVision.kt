package com.example.stackbot

import java.nio.ByteBuffer

object StackVision {
    private const val T = 28          // brightness threshold (0-255)
    private const val GAP = 18        // dark gap allowed inside a block (px)
    private const val MIN_RUN = 140   // a block row has a bright run at least this long
    private const val K = 5           // column belongs to block if >= K bright pixels in the band
    private const val MISS_LIMIT = 3  // consecutive non-block rows that end a band

    class Block(val y0: Int, val y1: Int, val l: Int, val r: Int)

    private fun lum(row: ByteArray, x: Int): Int {
        val o = x * 4
        val r = row[o].toInt() and 0xFF
        val g = row[o + 1].toInt() and 0xFF
        val b = row[o + 2].toInt() and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private fun readRow(buf: ByteBuffer, stride: Int, w: Int, y: Int, row: ByteArray) {
        buf.position(y * stride)
        buf.get(row, 0, w * 4)
    }

    private fun rowIsBlock(row: ByteArray, w: Int): Boolean {
        var runStart = -1
        var last = -1000
        for (x in 0 until w) {
            if (lum(row, x) > T) {
                if (runStart < 0 || x - last > GAP + 1) runStart = x
                last = x
                if (last - runStart + 1 >= MIN_RUN) return true
            }
        }
        return false
    }

    private fun extent(
        buf: ByteBuffer, stride: Int, w: Int, y0: Int, y1: Int, row: ByteArray
    ): IntArray? {
        val cnt = IntArray(w)
        for (y in y0..y1) {
            readRow(buf, stride, w, y, row)
            for (x in 0 until w) if (lum(row, x) > T) cnt[x]++
        }
        var l = -1
        var r = -1
        for (x in 0 until w) {
            if (cnt[x] >= K) {
                if (l < 0) l = x
                r = x
            }
        }
        return if (l < 0) null else intArrayOf(l, r)
    }

    /** Returns up to two blocks, top to bottom: [moving block, block below it]. */
    fun detect(buf: ByteBuffer, stride: Int, w: Int, h: Int): List<Block> {
        val row = ByteArray(w * 4)
        val out = ArrayList<Block>()
        val minH = (w * 0.053f).toInt()
        val maxH = (w * 0.097f).toInt()
        val yEnd = (h * 0.97f).toInt()
        var start = -1
        var last = -1
        var y = (h * 0.25f).toInt()
        while (y < yEnd && out.size < 2) {
            readRow(buf, stride, w, y, row)
            if (rowIsBlock(row, w)) {
                if (start < 0) start = y
                last = y
            } else if (start >= 0 && y - last >= MISS_LIMIT) {
                val bh = last - start + 1
                if (bh in minH..maxH) {
                    val e = extent(buf, stride, w, start, last, row)
                    if (e != null) out.add(Block(start, last, e[0], e[1]))
                }
                start = -1
            }
            y++
        }
        return out
    }
}
