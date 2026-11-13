package dev.suanniao

import dev.suanniao.WorkbenchLayoutV3.Container.BALL
import dev.suanniao.WorkbenchLayoutV3.Container.KEYMOUSE
import dev.suanniao.WorkbenchLayoutV3.Container.QUICK
import dev.suanniao.WorkbenchLayoutV3.Container.RAIL
import dev.suanniao.WorkbenchLayoutV3.Slot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchLayoutV3Test {
    private val ids = setOf("f4", "e", "ctrlA", "t1", "t2", "t3")
    private val defaultBall = listOf("fn:voice", "fn:keymouse", "fn:pointer", "fn:taskSwitch", "button:f4", "button:e", null, null)

    /** quick = null：不写 quickText 这个键（旧的电脑发来的就是这样） */
    private fun json(
        ball: List<String?> = defaultBall,
        voice: List<String> = listOf("key:backspace"),
        keymouse: List<String> = listOf("key:backspace", "button:ctrlA"),
        rail: List<String> = emptyList(),
        quick: List<String>? = null,
    ): JSONObject = JSONObject()
        .put("schemaVersion", 3)
        .put("slots", JSONObject()
            .put("ball", JSONArray().apply { ball.forEach { put(it ?: JSONObject.NULL) } })
            .put("voiceRow", JSONArray(voice))
            .put("keymouse", JSONArray(keymouse))
            .put("rail", JSONArray(rail))
            .apply { if (quick != null) put("quickText", JSONArray(quick)) })
        .put("geometry", WorkbenchLayoutV3.fallback().toJson().getJSONObject("geometry"))

    private fun layout() = WorkbenchLayoutV3.parse(json(), ids)!!

    @Test fun parsesAndRoundTripsCanonicalJson() {
        val l = layout()
        assertEquals(l.signature(), WorkbenchLayoutV3.parse(l.toJson(), ids)!!.signature())
        assertEquals(WorkbenchRef.Button("f4"), l.ball[4])
        assertNull(l.ball[6])
    }

    @Test fun fixedFunctionsMustStayInTheBall() {
        assertNull(WorkbenchLayoutV3.parse(json(ball = defaultBall.map { if (it == "fn:pointer") null else it }), ids))
        assertNull(WorkbenchLayoutV3.parse(json(voice = listOf("fn:pointer")), ids))
    }

    @Test fun buttonsAndFunctionsAppearOnlyOnce() {
        assertNull(WorkbenchLayoutV3.parse(json(rail = listOf("button:f4")), ids))
        assertNull(WorkbenchLayoutV3.parse(json(rail = listOf("fn:taskSwitch")), ids))
    }

    @Test fun basicKeysRepeatAcrossRowsButNotWithinOne() {
        assertNotNull(WorkbenchLayoutV3.parse(json(voice = listOf("key:enter"), keymouse = listOf("key:enter")), ids))
        assertNull(WorkbenchLayoutV3.parse(json(voice = listOf("key:enter", "key:enter")), ids))
    }

    @Test fun missingOfficialButtonIsSkippedNotFatal() {
        val l = WorkbenchLayoutV3.parse(json(ball = defaultBall.map { if (it == "button:e") "button:gone" else it }), ids)!!
        assertNull(l.ball[5])
    }

    @Test fun wrongSchemaOrBallSizeIsRejected() {
        assertNull(WorkbenchLayoutV3.parse(json().put("schemaVersion", 2), ids))
        assertNull(WorkbenchLayoutV3.parse(json(ball = defaultBall.take(7)), ids))
        assertNull(WorkbenchLayoutV3.parse(null, ids))
    }

    @Test fun removeEmptiesBallSlotButNeverFixedOnes() {
        val l = layout()
        assertNull(l.remove(Slot(BALL, 4)).ball[4])
        assertSame(l, l.remove(Slot(BALL, 0)))
        assertTrue(l.isFixed(Slot(BALL, 2)))
        assertFalse(l.isFixed(Slot(BALL, 3)))
    }

    @Test fun placingAnOfficialButtonMovesIt() {
        val moved = layout().place(WorkbenchRef.Button("f4"), Slot(RAIL, 0))
        assertNull(moved.ball[4])
        assertEquals(listOf(WorkbenchRef.Button("f4")), moved.rail)
    }

    @Test fun placingIntoOccupiedBallSlotSwapsWithinBallOtherwiseDisplaces() {
        val l = layout()
        val swapped = l.place(WorkbenchRef.Button("f4"), Slot(BALL, 3))
        assertEquals(WorkbenchRef.Button("f4"), swapped.ball[3])
        assertEquals(WorkbenchRef.Fn("taskSwitch"), swapped.ball[4])
        val fromRow = l.place(WorkbenchRef.Button("ctrlA"), Slot(BALL, 5))
        assertEquals(WorkbenchRef.Button("ctrlA"), fromRow.ball[5])
        assertNull(fromRow.find(WorkbenchRef.Button("e")))
        assertFalse(fromRow.keymouse.contains(WorkbenchRef.Button("ctrlA")))
    }

    @Test fun fixedSlotsOnlySwapWithinBall() {
        val l = layout()
        assertSame(l, l.place(WorkbenchRef.Button("f4"), Slot(BALL, 0)))
        val moved = l.place(WorkbenchRef.Fn("voice"), Slot(BALL, 6))
        assertEquals(WorkbenchRef.Fn("voice"), moved.ball[6])
        assertNull(moved.ball[0])
        assertSame(l, l.place(WorkbenchRef.Fn("voice"), Slot(RAIL, 0)))
    }

    @Test fun moveWithinReordersRow() {
        val r = layout().moveWithin(KEYMOUSE, 1, 0)
        assertEquals(listOf(WorkbenchRef.Button("ctrlA"), WorkbenchRef.Key("backspace")), r.keymouse)
    }

    @Test fun duplicateKeyInSameRowIsRefusedButMovingKeysWorks() {
        val l = layout()
        assertSame(l, l.place(WorkbenchRef.Key("backspace"), Slot(KEYMOUSE, 0), null))
        val r = l.place(WorkbenchRef.Key("backspace"), Slot(RAIL, 0), Slot(KEYMOUSE, 0))
        assertEquals(listOf(WorkbenchRef.Button("ctrlA")), r.keymouse)
        assertEquals(listOf(WorkbenchRef.Key("backspace")), r.rail)
    }

    @Test fun geometryEditsStayPerOrientation() {
        val l = layout()
        val r = l.withTool(true, "voice", WorkbenchLayoutV3.Tool(0.3, 0.4, 0.5, 1.2)).withBall(true, 0.5, 0.5)
        assertEquals(0.3, r.geometry(true).voice.x, 1e-9)
        assertEquals(0.5, r.geometry(true).ballX, 1e-9)
        assertEquals(l.geometry(false), r.geometry(false))
    }

    @Test fun fallbackIsValidWithoutOfficialButtons() {
        assertNotNull(WorkbenchLayoutV3.parse(WorkbenchLayoutV3.fallback().toJson(), emptySet()))
    }

    @Test fun fallbackPutsSendFileInTheLastBallSlot() {
        val f = WorkbenchLayoutV3.fallback()
        assertEquals(WorkbenchRef.Fn("sendFile"), f.ball[7])
        assertEquals(Slot(BALL, 7), f.find(WorkbenchRef.Fn("sendFile")))
        assertTrue(f.quickText.isEmpty())
    }

    @Test fun quickTextIsOptionalAndAlwaysWritten() {
        val old = layout()
        assertTrue(old.quickText.isEmpty())
        assertTrue(old.toJson().getJSONObject("slots").has("quickText"))
        assertEquals(0, old.toJson().getJSONObject("slots").getJSONArray("quickText").length())
        val withNull = json().apply { getJSONObject("slots").put("quickText", JSONObject.NULL) }
        assertNotNull(WorkbenchLayoutV3.parse(withNull, ids))
        val l = WorkbenchLayoutV3.parse(json(quick = listOf("button:t1", "button:t2")), ids)!!
        assertEquals(listOf(WorkbenchRef.Button("t1"), WorkbenchRef.Button("t2")), l.quickText)
        val again = WorkbenchLayoutV3.parse(l.toJson(), ids)!!
        assertEquals(l.signature(), again.signature())
        assertEquals(l.quickText, again.quickText)
    }

    @Test fun quickTextHoldsOnlyOfficialButtons() {
        assertNull(WorkbenchLayoutV3.parse(json(quick = listOf("fn:captureQq")), ids))
        assertNull(WorkbenchLayoutV3.parse(json(quick = listOf("key:enter")), ids))
        assertNull(WorkbenchLayoutV3.parse(json(quick = listOf("fn:pointer")), ids))
        // 不是数组：结构不对
        assertNull(WorkbenchLayoutV3.parse(json().apply { getJSONObject("slots").put("quickText", "button:t1") }, ids))
    }

    @Test fun quickTextButtonsAreUniqueAcrossTheLayoutAndAtMost24() {
        assertNull(WorkbenchLayoutV3.parse(json(quick = listOf("button:f4")), ids))
        assertNull(WorkbenchLayoutV3.parse(json(quick = listOf("button:ctrlA")), ids))
        assertNull(WorkbenchLayoutV3.parse(json(quick = listOf("button:t1", "button:t1")), ids))
        val many = (1..25).map { "q$it" }
        assertNull(WorkbenchLayoutV3.parse(json(quick = many.map { "button:$it" }), ids + many))
        assertNotNull(WorkbenchLayoutV3.parse(json(quick = many.take(24).map { "button:$it" }), ids + many))
    }

    @Test fun quickTextSkipsDeletedButtons() {
        val l = WorkbenchLayoutV3.parse(json(quick = listOf("button:gone", "button:t1")), ids)!!
        assertEquals(listOf(WorkbenchRef.Button("t1")), l.quickText)
    }

    @Test fun quickTextFindRemoveReorderAndPlace() {
        val l = WorkbenchLayoutV3.parse(json(quick = listOf("button:t1", "button:t2", "button:t3")), ids)!!
        assertEquals(Slot(QUICK, 2), l.find(WorkbenchRef.Button("t3")))
        assertEquals(listOf("t2", "t1", "t3"), l.moveWithin(QUICK, 1, 0).quickText.map { (it as WorkbenchRef.Button).id })
        assertEquals(listOf("t3", "t1", "t2"), l.moveWithin(QUICK, 2, 0).quickText.map { (it as WorkbenchRef.Button).id })
        assertEquals(listOf("t1", "t3"), l.remove(Slot(QUICK, 1)).quickText.map { (it as WorkbenchRef.Button).id })
        // 从悬浮球搬进快捷发送：原来那格空出来
        val moved = l.place(WorkbenchRef.Button("f4"), Slot(QUICK, 0))
        assertEquals(WorkbenchRef.Button("f4"), moved.quickText[0])
        assertNull(moved.ball[4])
        // 从快捷发送搬到侧栏
        val toRail = l.place(WorkbenchRef.Button("t2"), Slot(RAIL, 0))
        assertEquals(listOf(WorkbenchRef.Button("t2")), toRail.rail)
        assertFalse(toRail.quickText.contains(WorkbenchRef.Button("t2")))
        // 动作、基础键放不进快捷发送
        assertSame(l, l.place(WorkbenchRef.Fn("captureQq"), Slot(QUICK, 0)))
        assertSame(l, l.place(WorkbenchRef.Key("enter"), Slot(QUICK, 0), null))
        assertSame(l, l.place(WorkbenchRef.Fn("taskSwitch"), Slot(QUICK, 0)))
    }

    @Test fun draftDropsButtonsDeletedOnThePc() {
        val l = WorkbenchLayoutV3.parse(json(quick = listOf("button:t1", "button:t2"), rail = listOf("button:t3")), ids)!!
        assertSame(l, l.withoutMissing(ids))
        assertEquals(0, l.missingButtons(ids))
        val left = ids - setOf("e", "t1", "t3")
        assertEquals(3, l.missingButtons(left))
        val pruned = l.withoutMissing(left)
        assertNull(pruned.ball[5])
        assertEquals(WorkbenchRef.Button("f4"), pruned.ball[4])
        assertEquals(listOf(WorkbenchRef.Button("t2")), pruned.quickText)
        assertTrue(pruned.rail.isEmpty())
        assertEquals(listOf(WorkbenchRef.Key("backspace"), WorkbenchRef.Button("ctrlA")), pruned.keymouse)
        assertEquals(0, pruned.missingButtons(left))
        // 草稿拿掉以后还是合格的布局，电脑收得下
        assertNotNull(WorkbenchLayoutV3.parse(pruned.toJson(), left))
    }

    @Test fun draftPicksUpWhatThePcAddedMeanwhile() {
        val base = WorkbenchLayoutV3.parse(json(quick = listOf("button:t1")), ids)!!
        // 调整布局里把 t1 挪到侧栏、拿掉了 f4
        val draft = base.place(WorkbenchRef.Button("t1"), Slot(RAIL, 0)).remove(Slot(BALL, 4))
        // 这期间电脑上又建了一条快捷发送 t2，还把 t3 放进了悬浮球第 7 格
        val theirs = WorkbenchLayoutV3.parse(json(
            ball = defaultBall.take(6) + "button:t3" + null, quick = listOf("button:t1", "button:t2")), ids)!!
        val merged = draft.withAdditions(base, theirs)
        assertEquals(listOf(WorkbenchRef.Button("t2")), merged.quickText) // 补上了，t1 还是在侧栏（草稿说了算）
        assertEquals(listOf(WorkbenchRef.Button("t1")), merged.rail)
        assertEquals(WorkbenchRef.Button("t3"), merged.ball[6])
        assertNull(merged.ball[4]) // 草稿里拿掉的不会被加回来
        // 没多出东西：原样返回
        assertSame(draft, draft.withAdditions(base, base))
    }

    @Test fun additionsGoToTheFirstFreeBallSlotOrAreSkipped() {
        val base = layout()
        val draft = base.place(WorkbenchRef.Button("t1"), Slot(BALL, 6))
        val theirs = WorkbenchLayoutV3.parse(json(ball = defaultBall.take(6) + "button:t2" + null), ids)!!
        // 第 7 格草稿里放了 t1：t2 放到第一个空格（第 8 格）
        assertEquals(WorkbenchRef.Button("t2"), draft.withAdditions(base, theirs).ball[7])
        // 悬浮球满了：放不下就不放
        val full = draft.place(WorkbenchRef.Button("t3"), Slot(BALL, 7))
        assertSame(full, full.withAdditions(base, theirs))
    }

    @Test fun sendFileIsAnOrdinaryPlaceableAction() {
        val l = WorkbenchLayoutV3.parse(json(ball = defaultBall.take(7) + "fn:sendFile"), ids)!!
        assertEquals(WorkbenchRef.Fn("sendFile"), l.ball[7])
        val moved = l.place(WorkbenchRef.Fn("sendFile"), Slot(KEYMOUSE, 0))
        assertNull(moved.ball[7])
        assertEquals(WorkbenchRef.Fn("sendFile"), moved.keymouse[0])
        assertNull(WorkbenchLayoutV3.parse(json(ball = defaultBall.take(7) + "fn:sendFile", rail = listOf("fn:sendFile")), ids))
    }
}
