package ai.taehyeon.giuncha

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 기운차 사주 엔진 — 파이썬 기준 구현(giun_engine.py)과 같은 입력이면 같은 출력.
 * 일주(일간·일진)만 사용: 날짜만으로 정확히 계산되고 절기 영향이 없음.
 */
class Engine(ctx: Context) {
    private val c: JSONObject =
        JSONObject(ctx.assets.open("content.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

    companion object {
        const val STEMS = "갑을병정무기경신임계"
        const val STEM_HANJA = "甲乙丙丁戊己庚辛壬癸"
        const val BRANCHES = "자축인묘진사오미신유술해"
        val ELEM_OF_STEM = listOf("목", "목", "화", "화", "토", "토", "금", "금", "수", "수")
        val GEN = mapOf("목" to "화", "화" to "토", "토" to "금", "금" to "수", "수" to "목")
        val CTRL = mapOf("목" to "토", "토" to "수", "수" to "화", "화" to "금", "금" to "목")
        val GEN_BY = GEN.entries.associate { (k, v) -> v to k }
        val ANIMALS = listOf("쥐", "소", "호랑이", "토끼", "용", "뱀", "말", "양", "원숭이", "닭", "개", "돼지")
        val EMOTIONS = listOf("좋았어요", "뿌듯해요", "괜찮아요", "생각이 많아요", "짜증나요", "불안해요", "우울해요")
        val POSITIVE = setOf("좋았어요", "뿌듯해요")

        /** 받침에 따라 은/는 */
        fun josa(w: String, withFinal: String, noFinal: String): String {
            val c = w.lastOrNull() ?: return w + noFinal
            val hasFinal = c in '가'..'힣' && (c - '가') % 28 != 0
            return w + if (hasFinal) withFinal else noFinal
        }

        fun jdn(d: LocalDate): Long = d.toEpochDay() + 2440588L

        fun dayPillar(d: LocalDate): Pair<Int, Int> {
            val n = jdn(d)
            return Pair(((n + 9) % 10).toInt(), ((n + 1) % 12).toInt())
        }

        fun group(me: String, today: String): String = when {
            me == today -> "비겁"
            GEN[me] == today -> "식상"
            CTRL[me] == today -> "재성"
            CTRL[today] == me -> "관성"
            GEN[today] == me -> "인성"
            else -> error("group")
        }

        /** 띠 — 입춘(2/4 무렵) 전 출생은 전년도 띠. 정밀 절기는 2단계(만세력 표) */
        fun zodiacAnimal(birth: LocalDate): String {
            var y = birth.year
            if (birth.monthValue == 1 || (birth.monthValue == 2 && birth.dayOfMonth < 4)) y -= 1
            return ANIMALS[Math.floorMod(y - 4, 12)]
        }
    }

    private fun JSONArray.pick(seed: Long): String = getString(Math.floorMod(seed, length().toLong()).toInt())

    fun elem(e: String): JSONObject = c.getJSONObject("elements").getJSONObject(e)

    data class Identity(val dayStem: String, val element: String, val elementWord: String,
                        val animal: String, val title: String, val desc: String, val rgb: Int)

    private val identityText = mapOf(
        "목" to Pair("나무 같은 사람", "곧게 자라며 곁에 있는 사람까지 함께 키우는 사람"),
        "화" to Pair("불 같은 사람", "있는 곳을 밝히고 마음을 먼저 데우는 사람"),
        "토" to Pair("흙 같은 사람", "말없이 받쳐 주고 오래 곁을 지키는 사람"),
        "금" to Pair("쇠 같은 사람", "맺고 끊음이 분명하고 약속을 지키는 사람"),
        "수" to Pair("물 같은 사람", "어디든 스며들고 흐르듯 길을 찾는 사람"),
    )

    fun identity(birth: LocalDate): Identity {
        val (s, _) = dayPillar(birth)
        val me = ELEM_OF_STEM[s]
        val (t, d) = identityText.getValue(me)
        val e = elem(me)
        return Identity(STEMS[s] + "(" + STEM_HANJA[s] + ")", me, e.getString("element_word"),
            zodiacAnimal(birth), t, d, rgbOf(e.getJSONArray("rgb")))
    }

    fun rgbOf(a: JSONArray): Int =
        (0xFF shl 24) or (a.getInt(0) shl 16) or (a.getInt(1) shl 8) or a.getInt(2)

    data class Card(
        val rule: String, val element: String?, val elementWord: String?, val colorName: String,
        val rgb: Int, val line: String, val action: String, val help: String?,
        val yesterdayAction: String?, val dayName: String, val animalDay: String,
        val direction: String, val clothes: String, val person: String, val phrase: String,
        val place: String, val reason: String, val goodTime: String, val theme: String,
        val weekendPlace: String,
    )

    fun morning(birth: LocalDate, today: LocalDate, tag: String?, tagDaysAgo: Int,
                crisisYesterday: Boolean, negStreak: Int, actionLine: String?): Card {
        val (myS, _) = dayPillar(birth)
        val (tS, tB) = dayPillar(today)
        val me = ELEM_OF_STEM[myS]
        val tod = ELEM_OF_STEM[tS]
        val g = group(me, tod)
        val seed = jdn(today) * 7 + myS
        val weekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
        val opt = c.getJSONObject("options")
        val gr = c.getJSONObject("groups").getJSONObject(g)
        val br = c.getJSONArray("branches").getJSONObject(tB)
        val dayName = "${STEMS[tS]}${BRANCHES[tB]}일"
        val animalDay = "${br.getString("animal")}(${BRANCHES[tB]})의 날"
        val dir = br.getString("dir")
        val person = gr.getJSONArray("people").pick(seed)
        val phrase = gr.getJSONArray("phrases").pick(seed)
        val place = if (weekend) "오늘 방향은 ${dir}쪽 — ${br.getString("place")}" else "점심은 ${dir}쪽 어때요?"
        val reason = "오늘은 ${elem(tod).getString("element_word")}의 날, ${br.getString("animal")}의 날이라 ${dir}쪽 · 재미로 보는 풀이예요"
        val theme = gr.getString("theme")

        val rest = c.getJSONObject("rest_card")
        fun restCard(rule: String) = Card(rule, null, null, rest.getString("색"), rgbOf(rest.getJSONArray("무드등")),
            rest.getString("한줄"), rest.getString("작은행동"), rest.getString("도움"), actionLine,
            dayName, animalDay, dir, "편한 옷", "-", "-", "오늘은 가까운 곳만", reason, "-", "쉬어 가기", br.getString("place"))

        // ① 어제 위기 신호 → 사주 처방 없음
        if (crisisYesterday) return restCard("①위기")
        // ② 같은 부정 감정 연속 N일
        if (negStreak >= opt.getInt("neg_streak_days")) return restCard("②연속")

        val emo = c.getJSONObject("emotions")
        val fill: String
        val act: String
        val rule: String
        if (tag != null && emo.has(tag) && tagDaysAgo <= opt.getInt("tag_expire_days") && !emo.getJSONObject(tag).isNull("elem")) {
            // ③ 어제 감정 → 그 감정 오행을 생해 주는 오행
            fill = emo.getJSONObject(tag).optString("fill", "").ifEmpty { GEN_BY.getValue(emo.getJSONObject(tag).getString("elem")) }
            act = emo.getJSONObject(tag).getJSONArray("actions").pick(seed)
            rule = "③감정"
        } else {
            fill = tod
            act = gr.getJSONArray("actions").pick(seed)
            rule = "④기본"
        }
        val e = elem(fill)
        return Card(rule, fill, e.getString("element_word"), e.getString("color_name"), rgbOf(e.getJSONArray("rgb")),
            e.getJSONArray("lines").pick(seed), act, null, actionLine, dayName, animalDay, dir,
            e.getString("clothes"), person, phrase, place, reason, e.getString("good_time"), theme, br.getString("place"))
    }

    /** 퇴근 한 단어 → 공감 + 내일 아침 예고 */
    fun checkinReply(tag: String): Pair<String, String?> {
        val em = c.getJSONObject("emotions").getJSONObject(tag)
        if (em.isNull("elem")) return Pair(em.getString("empathy"), null)
        val fill = em.optString("fill", "").ifEmpty { GEN_BY.getValue(em.getString("elem")) }
        val w = elem(fill).getString("color_name")
        return Pair(em.getString("empathy"), "내일 아침은 ${w}으로 준비해 둘게요.")
    }

    fun restTags(): Set<String> {
        val a = c.getJSONObject("options").optJSONArray("rest_streak_tags") ?: return setOf("불안해요", "우울해요")
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    /** 감정이 다음 날 채울 오행 (없으면 null) */
    fun fillOf(tag: String): String? {
        val em = c.getJSONObject("emotions").optJSONObject(tag) ?: return null
        if (em.isNull("elem")) return null
        return em.optString("fill", "").ifEmpty { GEN_BY.getValue(em.getString("elem")) }
    }

    fun forbiddenHit(text: String): String? {
        val f = c.getJSONArray("forbidden_words")
        for (i in 0 until f.length()) if (text.contains(f.getString(i))) return f.getString(i)
        return null
    }
}
