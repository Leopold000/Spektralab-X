package com.dearded.spektralab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 常用边框 keeps the frame, film edge and date back, and leaves the photo's crop alone otherwise. */
class BorderFavoriteTest {
    @Test fun savedCombinationRoundTripsThroughJson() {
        val edit = Edit(
            borderId = "mat_museum",
            frame = FrameSpec("mat_museum", 0.12f, 0.18f, 0.12f, paper = FrameSpec.PAPER_CREAM,
                caption = true, perf = Perforation(where = "top")),
            filmEdge = true, edgeFormat = "120_6x7", edgeMode = "filed", edgeHoles = "black",
            edgeGate = "rounded", edgeCarrier = "black", edgeFog = 2.5, edgeLeaks = 1.5,
            edgeRealName = false, edgeFrameNumber = 12, edgeCameraSeed = 31, edgeFrameSeed = 47,
            dateImprint = true, dateStyle = "dots", dateCorner = "tl", dateOrder = "us",
            dateWithData = true, datePlacement = "rebate", dateSize = 1.4, dateEv = 5.0,
            dateInsetX = 5.0, dateInsetY = 6.0,
        )
        val saved = BorderFavorite.of("我的卡纸", edit)
        assertEquals(saved, BorderFavorite.fromJson(JSONObject(saved.toJson().toString())))
    }

    @Test fun applyingRestoresTheSavedFrameAndDateSettings() {
        val source = Edit(borderId = "gallery", frame = FrameSpec("gallery", 0.05f, 0.05f, 0.05f),
            dateImprint = true, dateText = "'26 11 15")
        val saved = BorderFavorite.of("签名卡纸", source.copy(
            borderId = "mat_signed",
            frame = FrameSpec("mat_signed", 0.08f, 0.2f, 0.08f, paper = FrameSpec.PAPER_KRAFT,
                caption = true, sign = true, signText = "山田"),
            dateStyle = "dots", dateCorner = "bl", dateSize = 1.6,
        ))
        val next = saved.applyTo(Edit(borderId = "none", dateText = "'26 11 15"), 3000, 2000)
        assertEquals("mat_signed", next.borderId)
        assertEquals(saved.frame, next.frame)
        assertTrue(next.dateImprint)
        assertEquals("dots", next.dateStyle)
        assertEquals("bl", next.dateCorner)
        assertEquals(1.6, next.dateSize, 0.0)
        // The photo's own crop is not part of the combination.
        assertEquals(Edit().cropW, next.cropW, 0.0)
    }

    @Test fun filmEdgeCombosCropToTheirGate() {
        val saved = BorderFavorite.of("6x7", Edit(
            borderId = "edge_6x7", filmEdge = true, edgeFormat = "120_6x7", edgeGate = "rounded",
        ))
        val next = saved.applyTo(Edit(), 3000, 2000)
        assertEquals("120_6x7", next.edgeFormat)
        assertEquals("rounded", next.edgeGate)
        assertTrue(next.filmEdge)
        val aspect = next.cropW * 3000 / (next.cropH * 2000)
        assertEquals(69.5 / 56.0, aspect, 0.01)
    }

    @Test fun aDateBackStaysOffWhenThePhotoHasNoDate() {
        val saved = BorderFavorite.of("橙色日期", Edit(
            borderId = "date_lcd", dateImprint = true, dateStyle = "lcd", dateText = "'26 11 15",
        ))
        val next = saved.applyTo(Edit(dateText = ""), 1000, 1500)
        assertFalse(next.dateImprint)
        assertEquals("lcd", next.dateStyle)
        assertTrue(saved.matches(Edit(borderId = "date_lcd", dateImprint = true, dateStyle = "lcd",
            dateText = "'26 11 15", datePlacement = "frame")))
    }
}
