package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T227: the "+" tile is the first free shelf cell after the last book, at every column count. */
class T227Test {
    @Test fun theAddTileIsTheFirstEmptyCellAfterTheLastBookAtEveryColumnCount() {
        for (columns in 1..8) {
            for (count in 0..24) {
                val tile = LibraryLayout.addTileSlot(count, columns)
                assertEquals(count, tile.index)
                assertEquals(LibraryLayout.addTileIndex(count), tile.index)
                assertEquals(count / columns, tile.row)
                assertEquals(count % columns, tile.column)
                if (count == 0) {
                    assertEquals(0, tile.row)
                    assertEquals(0, tile.column)
                } else {
                    val last = LibraryLayout.addTileSlot(count - 1, columns)
                    assertEquals(count, last.index + 1)
                    val nextInRow = tile.row == last.row && tile.column == last.column + 1
                    val nextRow = last.column == columns - 1 && tile.row == last.row + 1 && tile.column == 0
                    assertTrue("count=$count columns=$columns tile=$tile last=$last", nextInRow || nextRow)
                }
            }
        }
    }

    @Test fun theAddTileComesFirstWhenTheLibraryIsEmpty() {
        for (columns in 1..8) {
            assertEquals(LibraryShelfSlot(0, 0, 0), LibraryLayout.addTileSlot(0, columns))
        }
    }

    @Test fun anImpossibleColumnCountFallsBackToASingleColumn() {
        assertEquals(LibraryShelfSlot(3, 3, 0), LibraryLayout.addTileSlot(3, 0))
        assertEquals(0, LibraryLayout.addTileSlot(-5, 3).index)
    }
}
