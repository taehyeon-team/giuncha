package ai.taehyeon.giuncha

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** 기기 안에만 저장 (서버 전송 없음). 감정 기록은 날짜 → 한 단어 */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("giuncha", Context.MODE_PRIVATE)

    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()

    var birth: LocalDate?
        get() = p.getString("birth", null)?.let { LocalDate.parse(it) }
        set(v) = p.edit().putString("birth", v?.toString()).apply()

    /** 시연용 날짜 이동 (다음 날 건너뛰기) */
    var dayOffset: Long
        get() = p.getLong("offset", 0)
        set(v) = p.edit().putLong("offset", v).apply()

    /** 기운차의 하루는 새벽 4시에 바뀐다. 자정 넘어 귀가해도 그날 저녁으로 치고, 한 단어도 그날에 남는다 */
    fun today(): LocalDate = java.time.LocalDateTime.now().minusHours(4).toLocalDate().plusDays(dayOffset)

    private fun hist(): JSONObject = JSONObject(p.getString("hist", "{}") ?: "{}")

    fun tagOn(d: LocalDate): String? = hist().optString(d.toString(), "").ifEmpty { null }

    /** 잘못 누른 한 단어를 지운다 (다시 고를 수 있게) */
    fun clearTag(d: LocalDate) { val h = hist(); h.remove(d.toString()); p.edit().putString("hist", h.toString()).apply() }

    fun setTag(d: LocalDate, tag: String) {
        val h = hist(); h.put(d.toString(), tag); p.edit().putString("hist", h.toString()).apply()
    }

    fun demoFilled(d: LocalDate): Boolean = JSONObject(p.getString("demo", "{}") ?: "{}").has(d.toString())

    fun markDemo(d: LocalDate) {
        val h = JSONObject(p.getString("demo", "{}") ?: "{}"); h.put(d.toString(), true)
        p.edit().putString("demo", h.toString()).apply()
    }

    /** 가장 최근 감정(오늘 제외, 7일 이내) → (태그, 며칠 전) */
    fun latestTag(today: LocalDate, maxDays: Int = 7): Pair<String, Int>? {
        for (i in 1..maxDays) tagOn(today.minusDays(i.toLong()))?.let { return Pair(it, i) }
        return null
    }

    /** 어제까지 같은 부정 감정이 연속된 날 수 */
    /** 어제까지 불안·우울(쉼 카드 대상 감정)이 이어진 날 수 — 11/12: 짜증·생각 많음은 세지 않음 */
    fun negStreak(today: LocalDate, restTags: Set<String> = setOf("불안해요", "우울해요")): Int {
        // 한 단어를 남긴 날만 이어서 센다. 차를 안 탄 날·건너뛴 날은 끊지 않고 넘어간다(지난 14일 안에서)
        var n = 0
        for (i in 1..14) {
            val tag = tagOn(today.minusDays(i.toLong())) ?: continue
            if (tag in restTags) n++ else break
        }
        return n
    }

    /** 화면 모드: friend(캐릭터, 기본) / calm(차분) / kitsch(키치) */
    var theme: String
        get() = p.getString("theme", "friend") ?: "friend"
        set(v) = p.edit().putString("theme", v).apply()

    /** 말투: 순한맛 / 매운맛 */
    var tone: String
        get() = p.getString("tone", "순한맛") ?: "순한맛"
        set(v) = p.edit().putString("tone", v).apply()

    fun recordCount(): Int = hist().length()

    /** 함께 탄 날: 앱을 연 날을 하루 한 번 센다(한 단어를 남겼는지와 상관없이). 친구가 자라는 기준 */
    fun markRide(d: LocalDate) {
        val s = (p.getStringSet("rides", emptySet()) ?: emptySet())
        if (d.toString() in s) return
        p.edit().putStringSet("rides", s.toMutableSet().apply { add(d.toString()) }).apply()
    }
    fun rideCount(): Int = (p.getStringSet("rides", emptySet()) ?: emptySet()).size

    /** 친구가 자란 모습을 보여 준 단계(자라는 순간을 한 번만 크게 보여 주려고) */
    var seenStage: Int
        get() = p.getInt("seenStage", 0)
        set(v) = p.edit().putInt("seenStage", v).apply()

    /** 이름을 지어 준 직후 첫 아침 카드에서 친구가 인사하도록 */
    var justMet: Boolean   // 이름을 지어 준 그날만 첫인사를 보여 준다(달리지 않고 하루가 지나도 남지 않게)
        get() = p.getString("justMetDay", "") == today().toString()
        set(v) = p.edit().putString("justMetDay", if (v) today().toString() else "").apply()

    /** 오늘 한 단어를 '넘어가기'로 고른 날 (그날은 다시 묻지 않는다) */
    var skipDay: String
        get() = p.getString("skip", "") ?: ""
        set(v) = p.edit().putString("skip", v).apply()

    /** 복: 하루 한 번씩만 쌓인다 (운행 마침 +10, 퇴근 한 단어 +5). 횟수·속도로 겨루지 않음 */
    var bok: Int
        get() = p.getInt("bok", 0)
        set(v) = p.edit().putInt("bok", v).apply()

    fun awardOnce(key: String, amount: Int): Boolean {
        val set = (p.getStringSet("awards", emptySet()) ?: emptySet()).toMutableSet()
        if (!set.add(key)) return false
        p.edit().putStringSet("awards", set).putInt("bok", bok + amount).apply(); return true
    }

    /** 함께 타는 친구: ""이면 내 띠 친구, 아니면 수호 친구 이름 */
    var friendKind: String
        get() = p.getString("kind", "") ?: ""
        set(v) = p.edit().putString("kind", v).apply()

    /** 고른 모습: -1 자동(자란 만큼), 0 처음 · 1 조금 자람 · 2 다 자람 */
    var friendForm: Int
        get() = p.getInt("form", -1)
        set(v) = p.edit().putInt("form", v).apply()

    val owned: Set<String> get() = p.getStringSet("owned", emptySet()) ?: emptySet()
    fun own(k: String) { val s = owned.toMutableSet(); s.add(k); p.edit().putStringSet("owned", s).apply() }

    /** 마음 대화를 내일 아침에 이어 갈지는 운전자가 정한다. 주제 한 단어만 이 차 안에 남김 */
    fun setTalk(topic: String?, day: LocalDate) { p.edit().putString("talkTopic", topic).putString("talkDay", day.toString()).apply() }
    fun talkFollow(today: LocalDate): String? =
        p.getString("talkTopic", null)?.takeIf { p.getString("talkDay", "") == today.minusDays(1).toString() }

    /** 내 띠 친구 이름 (처음 설정에서 지음) */
    var friendName: String
        get() = p.getString("friend", "콩이") ?: "콩이"
        set(v) = p.edit().putString("friend", v).apply()

    /** 옆자리 조합 도감 (조합 키만 저장, 사람 정보 없음) */
    fun addDex(key: String): Int {
        val set = (p.getStringSet("dex", emptySet()) ?: emptySet()).toMutableSet(); set.add(key)
        p.edit().putStringSet("dex", set).apply(); return set.size
    }
    fun dexCount(): Int = (p.getStringSet("dex", emptySet()) ?: emptySet()).size

    /** 설정의 '기록 지우고 처음부터': 이 차 안에 남긴 것을 모두 지운다(화면 모드 포함) */
    fun wipeAll() { p.edit().clear().apply() }

    /** 시연 줄의 '처음부터': 화면 모드와 말투는 남기고 지운다 */
    fun reset() {
        val keepTheme = theme; val keepTone = tone
        p.edit().clear().apply(); theme = keepTheme; tone = keepTone
    }
}
