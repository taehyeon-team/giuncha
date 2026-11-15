package ai.taehyeon.giuncha

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {
    private val tag = "Giuncha"
    private lateinit var engine: Engine
    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var glow: GlowView
    private lateinit var stage: FrameLayout
    private lateinit var caption: TextView
    private lateinit var badge: TextView
    private lateinit var bar: View
    private val ui = Handler(Looper.getMainLooper())

    // 폰트 (Pretendard, SIL OFL)
    private lateinit var fLight: Typeface
    private lateinit var fReg: Typeface
    private lateinit var fMed: Typeface
    private lateinit var fBold: Typeface

    private enum class Scene { SETUP, IDENTITY, MORNING, DRIVING, DESTINY, EVENING_ASK, EVENING_REPLY, WEEK, GATE, COMPANION, PICK, COMBO, MODE, EVENING_HELLO, ROAD, MYFRIEND, TALK_ASK, TALK_REPLY }
    private var scene = Scene.SETUP
    private var gear: VehicleGear = VehicleGear.GEAR_UNKNOWN
    private var eveningOverride: Boolean? = null
    private var detailOpen = false
    private var lastReply: Pair<String, String?>? = null
    private var recMode = false
    // 동승 감지: 차량 SDK 좌석 착석 신호(운전석 제외)를 따르고, 시연 단추를 누르면 그 값을 대신 쓴다
    private val seatOcc = mutableMapOf<String, Boolean>()
    private val seatListeners = mutableMapOf<SeatValue, SeatOccupancyListener>()
    private var passengerDemo: Boolean? = null
    private lateinit var passBtn: Button
    /** 시연 줄은 에뮬레이터(또는 demo=true로 켰을 때)에서만 보인다. 실제 차에서는 숨긴다 */
    private var demoMode = false
    private fun isEmulator() = Build.HARDWARE.contains("ranchu") || Build.MODEL.contains("emulator", true) || Build.DEVICE.contains("avd")
    // 에뮬레이터는 기본값으로 모든 자리가 '앉음'으로 나온다. 그래서 시연은 '혼자'로 시작하고, 단추로 '자동(좌석 신호)'을 고를 수 있다
    private fun passengerDefault(): Boolean? = if (demoMode && isEmulator()) false else null
    private var seatsAsked = -1               // -1 아직 모름 · 0 이 차는 좌석 신호 없음 · n 살피는 자리 수
    private var byHand: Boolean? = null       // 운전자가 손으로 알려 준 값(true 같이 탔어요 / false 혼자예요). 그날만 쓴다
    private var byHandDay: LocalDate? = null
    private var hold = false                  // 누가 앉은 적이 있으면, 운전자가 '혼자예요'를 누르거나 빈 차로 다시 출발할 때까지 가린 채로 둔다
    private fun byHandNow(): Boolean? = if (byHandDay == store.today() && System.currentTimeMillis() - byHandAt < 3 * 3600_000L) byHand else null
    private fun seatsUnknown() = seatsAsked < 0 || seatOcc.size < seatsAsked
    /** 같이 탄 사람이 있는가. 가리는 것은 자동(모르면 가린다), 다시 여는 것은 운전자가 누를 때만 */
    private val passenger: Boolean get() = passengerDemo ?: byHandNow() ?: ((hold && holdDay == store.today()) || seatsUnknown() || seatOcc.values.any { it })
    private fun setByHand(v: Boolean) {
        if (passengerDemo != null) passengerDemo = v            // 시연 중에는 시연 값으로
        else { byHand = v; byHandDay = store.today(); byHandAt = System.currentTimeMillis(); if (!v) hold = false }
    }
    private var showHelp = false
    private var partner = -1   // 옆자리 띠 (0=쥐 … 11=돼지), 오늘만 쓰고 저장 안 함
    private val webs = mutableMapOf<String, android.webkit.WebView>()
    private val webReady = mutableSetOf<String>()
    private val pendingJson = mutableMapOf<String, String>()
    private var drove = false                 // 방금 달리다가 주차했는지
    private var arrivedOn: LocalDate? = null  // 저녁에 달리다 도착한 날 (그날만 한 단어를 물음)
    private var helloBye = false              // 조용한 저녁 화면: 인사(false) / 마무리(true)
    private val tripEvents = linkedMapOf<String, Int>()   // 이번 운행에서 지나온 주의 구간 (이름 → 횟수)
    private val seenRoadIds = mutableSetOf<String>()
    private val lastTrip = linkedMapOf<String, Int>()     // 방금 마친 운행의 기록 ('오늘 지나온 길' 화면에 쓰는 사본)
    private var roadLine: String? = null                   // 달리는 중 한 번 바뀌는 한 줄 (소리 없음)
    private var tripBok = 0
    private var tripNote = 0                               // '오늘 지나온 길' 없이 도착했을 때 다음 화면에 한 번 붙이는 복 알림
    private var wordBok = 0
    private var talkTopic: String? = null
    private var started = false
    private var pendingBuy: String? = null
    private var lastDest = ""
    private var destroyed = false
    private var holdDay: LocalDate? = null
    private var byHandAt = 0L
    private var wipeAsk = false                            // 설정에서 '기록 지우기'를 한 번 누른 상태(한 번 더 물어봄)
    private val friendOnly = setOf(Scene.ROAD, Scene.MYFRIEND, Scene.TALK_ASK, Scene.TALK_REPLY)
    // 내비 SDK 주의 지점 종류 → (화면에 쓸 이름, 달릴 때 한 줄)
    private val ROAD_TEXT = linkedMapOf("SCHOOL_ZONE" to Pair("어린이 보호구역", "앞이 어린이 보호구역이에요."), "SPEED_BUMP" to Pair("방지턱", "앞에 방지턱이 있어요."),
        "ACCIDENT_PRONE" to Pair("사고 잦은 곳", "사고가 잦은 곳이에요."), "SLIPPERY" to Pair("미끄러운 길", "길이 미끄러워요."),
        "WILDLIFE" to Pair("동물이 다니는 길", "동물이 지나갈 수 있어요."), "FOG" to Pair("안개 잦은 곳", "안개가 잦은 곳이에요."),
        "NARROW_ROAD" to Pair("좁은 길", "길이 좁아져요."), "RAILROAD" to Pair("철길 건널목", "철길 건널목이에요."),
        "DOWNHILL" to Pair("긴 내리막", "긴 내리막이에요."), "FALLING_ROCK" to Pair("낙석 주의 구간", "돌이 떨어질 수 있는 곳이에요."))
    // 마음 대화(미리 써 둔 문장판): 주제 → (답 한 줄, 작은 행동)
    private val TALK = mapOf("일" to Pair("일이 마음을 꽉 잡고 있었네요. 오늘 몫은 여기까지, 남은 건 내일 생각해도 돼요.", "일 생각은 현관 앞에 내려놓고 들어가기"),
        "사람" to Pair("사람 때문에 마음이 쓰였군요. 그런 날은 유난히 더 지치죠.", "그 사람 생각은 오늘 밤 잠깐 내려놓기"),
        "몸" to Pair("몸이 먼저 지쳤던 거네요. 오늘은 마음보다 몸부터 챙겨요.", "오늘은 30분 일찍 눕기"),
        "모르겠어요" to Pair("이유를 몰라도 괜찮아요. 꼭 이유를 찾지 않아도 돼요.", "따뜻한 물 한 잔 천천히 마시기"))
    private val TALK_FOLLOW = mapOf("일" to "어제는 일 때문에 마음이 걸렸다고 했죠. 오늘은 할 수 있는 만큼만 해도 괜찮아요.",
        "사람" to "어제는 사람 때문에 마음이 쓰였죠. 오늘은 내 마음부터 챙기면서 천천히 가요.",
        "몸" to "어제는 몸이 지쳤다고 했죠. 오늘은 무리하지 말고 천천히 가요.",
        "모르겠어요" to "어제는 이유를 모르겠다고 했죠. 몰라도 괜찮아요. 오늘은 오늘대로 천천히 가요.")
    private val SPICY_REPLY = mapOf("좋았어요" to "오, 잘 풀린 날이네. 그 기세 내일도 가져가.", "뿌듯해요" to "해냈네. 오늘은 네가 주인공.",
        "괜찮아요" to "무난하면 이긴 거야. 내일 봐.", "생각이 많아요" to "머리 풀가동한 날이네. 오늘 생각은 여기서 퇴근.", "짜증나요" to "속 터질 만했네. 오늘은 여기서 끊자.")
    private val SPICY = mapOf("목" to "쭉쭉 뻗는 날. 고민은 짧게, 시작은 가볍게.", "화" to "오늘은 한마디만 툭 건네 봐. 그거면 돼.",
        "토" to "새로 벌이지 마. 하던 거 끝내는 날.", "금" to "끝낼 거 하나 정해. 나머진 내일.", "수" to "급할 거 없어. 흐르듯 가면 돼.")
    private val AEL = listOf("수", "토", "목", "목", "토", "화", "화", "토", "금", "금", "토", "수")   // 녹화 모드: 시연 버튼 숨기고 장면 자막 표시

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var curLat = 37.5663; private var curLon = 126.9779
    private val recentNames = mutableSetOf<String>()
    private var destiny: Triple<String, Double, Double>? = null

    // ───────────────────────── lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fLight = resources.getFont(R.font.pretendard_extralight)
        fReg = resources.getFont(R.font.pretendard_regular)
        fMed = resources.getFont(R.font.pretendard_medium)
        fBold = resources.getFont(R.font.pretendard_bold)
        engine = Engine(this); store = Store(this)
        recMode = intent?.getBooleanExtra("rec", false) ?: false
        root = FrameLayout(this)
        glow = GlowView(this)
        stage = FrameLayout(this)
        root.addView(glow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(stage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        caption = TextView(this).apply {
            typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(150, 0, 0, 0)) }
            setPadding(dp(30), dp(14), dp(30), dp(14))
        }
        root.addView(caption, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
        badge = TextView(this).apply {
            typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Color.WHITE)
            // 밝은 화면(캐릭터·키치 모드)에서도 읽히게 어두운 바탕을 깐다
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(120, 0, 0, 0)) }
            text = "에뮬레이터 시연 · 시연용 날짜 건너뛰기 포함"; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        root.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
        demoMode = isEmulator() || (intent?.getBooleanExtra("demo", false) ?: false)
        passengerDemo = passengerDefault()
        bar = demoBar()
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        applyRecMode()
        scene = if (store.birth == null) Scene.SETUP else parkedScene()
        render()
        initVehicle(); initNavi()
    }

    override fun onStop() {
        super.onStop()
        showHelp = false; detailOpen = false; wipeAsk = false; ui.removeCallbacks(autoHide)
        if (scene == Scene.WEEK) scene = Scene.GATE
        if (scene == Scene.EVENING_REPLY || scene == Scene.TALK_ASK || scene == Scene.TALK_REPLY) { helloBye = true; scene = Scene.EVENING_HELLO }
    }

    override fun onStart() {
        super.onStart()
        if (!started) { started = true; return }   // 처음 켤 때는 onCreate 가 이미 화면을 정했다
        refreshSeats()
        if (store.birth != null && scene != Scene.SETUP && scene != Scene.IDENTITY && scene != Scene.DRIVING && scene != Scene.DESTINY) { if (!isDriving(gear)) scene = parkedScene(); render() }
    }

    /** 열어 둔 7일 기록은 손을 대지 않으면 90초 뒤 다시 닫힌다 */
    private val autoHide = Runnable { if (scene == Scene.WEEK) { scene = Scene.GATE; render() } }
    private val foldHelp = Runnable { if (showHelp) { showHelp = false; if (scene == Scene.MORNING) render() } }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("rec")) { recMode = intent.getBooleanExtra("rec", false); applyRecMode(); render() }
    }

    private fun applyRecMode() {
        bar.visibility = if (demoMode) View.VISIBLE else View.GONE
        bar.alpha = if (recMode) 0f else 1f          // 녹화 모드: 투명하지만 자동 시연 탭은 그대로 받음
        caption.visibility = if (recMode) View.VISIBLE else View.GONE
        badge.visibility = if (recMode) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null)
        try { vehicle?.getGear()?.unregisterGearSelection(gearListener) } catch (_: Throwable) {}
        seatListeners.forEach { (s, l) -> try { vehicle?.getSeat()?.unregisterSeatOccupancy(SeatArea(s), l) } catch (_: Throwable) {} }
        try { navi?.removeListener(naviListener); navi?.release() } catch (_: Throwable) {}
        try { vehicle?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ───────────────────────── 차량 신호: 기어 P/D (Vehicle SDK Gear)
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) { ui.post { onGear(g ?: VehicleGear.GEAR_UNKNOWN, "차량") } }
        override fun onFailed(e: Exception) { Log.w(tag, "gear listener failed", e) }
    }

    private fun initVehicle() {
        try {
            val v = Vehicle(this); v.initialize(); vehicle = v
            initSeats(v)   // 좌석부터 먼저 물어본다(안 되면 아래에서 한 번 더)
            ui.postDelayed({ if (seatsAsked < 0) { seatsAsked = 0; onPassengerChanged() } }, 5000)   // 끝내 답이 없으면 좌석 신호가 없는 차로 본다
            ui.postDelayed({
                try {
                    v.getGear().getCurrentGearSelectionState({ g -> ui.post { onGear(g ?: VehicleGear.GEAR_UNKNOWN, "차량") } },
                        { e -> Log.w(tag, "gear state failed", e) })
                    v.getGear().registerGearSelection(gearListener)
                } catch (e: Throwable) { Log.w(tag, "gear init", e) }
                initSeats(v)
            }, 1500)
        } catch (e: Throwable) { Log.w(tag, "vehicle init", e) }
    }

    // ───────────────────────── 차량 신호: 좌석 착석 (Vehicle SDK Seat) → 동승 자동 감지
    private fun initSeats(v: Vehicle) {
        try {
            v.getSeat().checkSeatOccupancyCapabilities({ areas: List<SeatArea> ->
                // 운전석(좌핸들 기준 1열 왼쪽)은 빼고, 이 차가 착석 신호를 주는 자리만 듣는다
                val seats = areas.flatMap { it.values.toList() }.distinct().filter { it != SeatValue.ROW_1_LEFT }
                if (demoMode) Log.i(tag, "seat occupancy seats=$seats")
                ui.post { seatsAsked = seats.size; seats.forEach { s -> watchSeat(v, s) }; onPassengerChanged() }
                ui.postDelayed({ if (!destroyed) { seats.forEach { s -> if (!seatOcc.containsKey(s.toString())) seatOcc[s.toString()] = false }; onPassengerChanged() } }, 5000)
            }, { e -> Log.w(tag, "seat capability failed", e); ui.post { if (seatsAsked < 0) { seatsAsked = 0; onPassengerChanged() } } })
        } catch (e: Throwable) { Log.w(tag, "seat init", e) }
    }

    /** 화면에 다시 나타날 때 자리마다 한 번 더 물어본다 */
    private fun refreshSeats() {
        val v = vehicle ?: return
        seatListeners.keys.forEach { s ->
            try { v.getSeat().getCurrentSeatOccupancy(SeatArea(s), { o -> ui.post { onSeat(s.toString(), o == true) } }, { _ -> }) } catch (_: Throwable) {}
        }
    }

    private fun watchSeat(v: Vehicle, s: SeatValue) {
        if (seatListeners.containsKey(s)) return
        val area = SeatArea(s)
        val l = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) { ui.post { onSeat(s.toString(), occupied == true) } }
            override fun onFailed(e: Exception) { Log.w(tag, "seat listener failed $s", e) }
        }
        seatListeners[s] = l
        try {
            v.getSeat().getCurrentSeatOccupancy(area, { o -> ui.post { onSeat(s.toString(), o == true) } },
                { e -> Log.w(tag, "seat state failed $s", e); ui.post { if (!seatOcc.containsKey(s.toString())) onSeat(s.toString(), false) } })
            v.getSeat().registerSeatOccupancy(area, l)
        } catch (e: Throwable) { Log.w(tag, "seat watch $s", e) }
    }

    private fun onSeat(key: String, occupied: Boolean) {
        val before = passenger
        val was = seatOcc[key]
        seatOcc[key] = occupied
        val seatedBefore = seatedNow()
        if (occupied && was != true) { hold = true; holdDay = store.today(); if (byHand == false) byHand = null }   // 새로 누가 앉았으면 '혼자예요'는 무르고 다시 가린다
        if (demoMode) Log.i(tag, "seat $key occupied=$occupied passenger=$passenger")
        if (passenger != before) onPassengerChanged() else if (seatedNow() != seatedBefore) render() else updateStatus()
    }

    /** 누가 타면 마음 기록·한 단어 화면을 바로 가린다. 다시 여는 것은 운전자가 '혼자예요'를 눌렀을 때(또는 시연 단추)뿐이다 */
    private fun onPassengerChanged() {
        showHelp = false
        if (store.birth == null || scene == Scene.SETUP || scene == Scene.IDENTITY) { updateStatus(); return }
        if (passenger) {
            when (scene) {
                Scene.WEEK -> scene = Scene.GATE
                Scene.EVENING_ASK, Scene.EVENING_REPLY, Scene.TALK_ASK, Scene.TALK_REPLY -> scene = Scene.COMPANION
                Scene.EVENING_HELLO -> if (!helloBye) scene = parkedScene()
                else -> {}
            }
        } else if ((scene == Scene.COMPANION || scene == Scene.PICK || scene == Scene.COMBO || (scene == Scene.EVENING_HELLO && !helloBye)) && !isDriving(gear)) {
            scene = parkedScene()
        }
        render()
    }

    /** 운전자가 '혼자예요'를 눌렀다: 가림을 풀고, 저녁이면 한 단어를 남길 수 있게 한다 */
    private fun soloNow() {
        setByHand(false)
        showHelp = false
        if (!isDriving(gear) && scene != Scene.GATE && scene != Scene.MODE && scene != Scene.MYFRIEND) scene = parkedScene()
        render()
    }

    private fun isParked(g: VehicleGear) = g == VehicleGear.GEAR_PARK
    private fun isDriving(g: VehicleGear) = g != VehicleGear.GEAR_PARK && g != VehicleGear.GEAR_UNKNOWN

    private fun onGear(g: VehicleGear, src: String) {
        if (demoMode) Log.i(tag, "gear=$g ($src)")
        if (src == "차량" && g == gear) return   // 같은 기어 신호가 다시 오면 보던 화면을 그대로 둔다
        gear = g
        if (store.birth == null || scene == Scene.SETUP || scene == Scene.IDENTITY) { render(); return }
        if (isDriving(g)) {
            if (!drove) {
                tripEvents.clear(); seenRoadIds.clear(); roadLine = null
                if (!seatsUnknown() && seatOcc.values.none { it }) hold = false   // 빈 차로 다시 출발하면 다음 주차부터는 평소대로
            }
            tripNote = 0; showHelp = false; detailOpen = false; store.justMet = false
            drove = true; scene = Scene.DRIVING; render(); return
        }
        if (isParked(g)) {
            val hadTrip = drove
            if (drove && isEvening()) arrivedOn = store.today()
            drove = false; roadLine = null; ui.removeCallbacks(clearRoad)
            // 캐릭터 모드: 주의 구간을 지나온 운행이면 주차 뒤에 한 번 모아서 보여 준다
            // 복은 운행을 마치면 하루 한 번 쌓인다(주의 구간이 없어도). 주의 구간을 지나온 날만 '오늘 지나온 길'을 따로 보여 준다
            if (hadTrip) tripBok = if (store.awardOnce("trip:${store.today()}", 10)) 10 else 0
            lastTrip.clear(); if (hadTrip) lastTrip.putAll(tripEvents)
            tripEvents.clear(); seenRoadIds.clear()
            if (hadTrip && friend() && lastTrip.isNotEmpty()) { tripNote = 0; scene = Scene.ROAD }
            else { tripNote = if (hadTrip) tripBok else 0; scene = parkedScene() }
            render()
        }
    }

    /** 주차했을 때 보여 줄 화면. 저녁에 오늘 한 단어를 이미 골랐으면 다시 묻지 않는다. */
    private fun parkedScene(): Scene {
        if (!isEvening()) return Scene.MORNING
        if (passenger) {
            if (passengerDemo == null && byHandNow() == null && !hold && seatsUnknown()) { helloBye = false; return Scene.EVENING_HELLO }   // 좌석 답을 기다리는 몇 초
            return Scene.COMPANION
        }
        // 오늘 고른 단어와 답글은 고른 직후 한 번만 보여 준다. 그 뒤로는 조용한 마무리 인사.
        if (store.tagOn(store.today()) != null || store.skipDay == store.today().toString()) { helloBye = true; return Scene.EVENING_HELLO }
        // 한 단어는 집에 도착해 주차했을 때만 묻는다. 저녁에 막 탔을 때는 조용히 인사만.
        if (arrivedOn == store.today()) return Scene.EVENING_ASK
        helloBye = false; return Scene.EVENING_HELLO
    }

    // 저녁 = 오후 5시부터 새벽 4시 전까지 (늦게 귀가하는 사람도 그날 저녁으로)
    private fun isEvening(): Boolean = eveningOverride ?: LocalTime.now().hour.let { it >= 17 || it < 4 }

    /** 오늘의 카드 인사말: 시간에 맞춘다 (시연 단추로 아침·저녁을 정했으면 그걸 따름) */
    private fun greeting(): String {
        val h = LocalTime.now().hour
        return when {
            eveningOverride == false -> "좋은 아침이에요"
            eveningOverride == true || h >= 17 || h < 4 -> "좋은 저녁이에요"
            h < 11 -> "좋은 아침이에요"
            else -> "좋은 오후예요"
        }
    }

    private val clearRoad = Runnable { roadLine = null; if (scene == Scene.DRIVING) render() }

    /** 달리는 중 주의 구간·단속 카메라 알림: 표정과 한 줄만 한 번 바꾸고, 주차 뒤에 모아 보여 준다 */
    private fun onRoad(id: String, label: String, line: String) {
        if (!isDriving(gear) || store.birth == null) return
        if (id.isNotEmpty() && !seenRoadIds.add(id)) return
        tripEvents[label] = (tripEvents[label] ?: 0) + 1
        roadLine = line; if (scene == Scene.DRIVING) render()
        ui.removeCallbacks(clearRoad); ui.postDelayed(clearRoad, 8000)
    }

    private fun stageMax(): Int = store.rideCount().let { if (it >= 60) 2 else if (it >= 7) 1 else 0 }
    private fun stage(): Int = store.friendForm.let { if (it in 0..stageMax()) it else stageMax() }

    // ───────────────────────── 내비 목적지 → 오늘의 데스티니 (NaviHelper)
    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationChanged() { try { navi?.getDestinationInfo() } catch (e: Throwable) { Log.w(tag, "dest", e) } }
        override fun onDestinationInfo(info: DestinationInfo) {
            ui.post { onDestination(info.name ?: "목적지", info.latitude, info.longitude, real = true) }
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { curLat = info.latitude; curLon = info.longitude }
        override fun onCautionPointInfo(list: List<ai.pleos.playground.navi.data.CautionPoint>) {
            list.forEach { p ->
                val type = p.type.toString()
                ROAD_TEXT.entries.firstOrNull { type.contains(it.key) }?.let { e -> ui.post { onRoad("c${p.id}", e.value.first, e.value.second) } }
            }
        }
        override fun onCameraAlert(list: List<ai.pleos.playground.navi.data.CameraAlert>) {
            list.forEach { c -> ui.post { onRoad("k${c.id}", "단속 카메라", "앞에 단속 카메라가 있어요.") } }
        }
        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) { list.forEach { recentNames.add(it.toString()) } }
    }

    private fun initNavi() {
        try {
            val n = NaviHelper(this); navi = n
            n.initialize({
                n.addListener(naviListener)
                try { n.getCurrentLocationInfo(); n.getRecentDestinationInfo() } catch (_: Throwable) {}
            }, { e -> Log.w(tag, "navi init failed", e) })
        } catch (e: Throwable) { Log.w(tag, "navi init", e) }
    }

    private fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun onDestination(name: String, lat: Double, lon: Double, real: Boolean) {
        if (store.birth == null) return
        val key = "${store.today()}|$name"
        if (real && key == lastDest) return
        lastDest = key
        val firstTime = recentNames.none { it.contains(name) }
        // 원칙: 주말 + 처음 가는 곳에만 반응 (집·회사·단골은 조용히). 시연 버튼은 조건 생략
        if (real && !(weekend(store.today()) && firstTime)) { if (demoMode) Log.i(tag, "destiny skip"); return }
        destiny = Triple(name, lat, lon)
        if (!isDriving(gear)) { scene = Scene.DESTINY; render() }
    }

    private fun bearingDir(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val b = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[((b + 22.5) / 45).toInt() % 8]
    }

    // ───────────────────────── 화면 공통
    private fun card() = store.today().let { t ->
        val lt = if (passenger) null else store.latestTag(t)   // 같이 탔으면 어제 고른 단어를 색·작은 행동에 반영하지 않는다
        // 같이 탄 사람이 있으면 쉼 카드(며칠 힘들었다는 표시)는 띄우지 않고 평소 카드를 보여 준다
        engine.morning(store.birth!!, t, lt?.first, lt?.second ?: 99, false, if (passenger) 0 else store.negStreak(t, engine.restTags()), null)
    }

    private fun String.takeChars(n: Int) = if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))
    // 달릴 때는 지시처럼 들리지 않는 고정 문장만 쓴다(말투 선택과 상관없이)
    private val DRIVE_LINE = mapOf("목" to "새로 시작하기 좋은 하루예요.", "화" to "표정 하나가 분위기를 바꿔요.", "토" to "단단하게 하루를 받쳐 줄 거예요.",
        "금" to "담백하게, 하나씩 정리해요.", "수" to "마음이 맑은 하루예요.")
    private fun driveLine(): String { val cd = card(); return roadLine ?: if (cd.help != null) "마음 편하게 다녀오세요." else (DRIVE_LINE[cd.element] ?: "오늘도 안전하게 다녀오세요.") }
    private fun kitsch() = store.theme == "kitsch"
    /** 가장 최근 한 단어가 불안·우울이면 매운맛 말투를 쓰지 않는다 */
    private fun gentleDay(): Boolean = store.latestTag(store.today())?.first in engine.restTags()
    private fun spicyNow() = kitsch() && store.tone == "매운맛" && !gentleDay()
    private fun friend() = store.theme == "friend"

    private fun render() {
        if (destroyed) return
        hideKeyboard()
        stage.removeAllViews()
        if (store.birth == null && scene != Scene.SETUP && scene != Scene.MODE) scene = Scene.SETUP
        if (store.birth != null) store.markRide(store.today())
        ui.removeCallbacks(autoHide); if (scene == Scene.WEEK) ui.postDelayed(autoHide, 90000)
        // 달리는 중에는 처음 설정을 보여 주지 않는다(입력은 주차 뒤에)
        if (isDriving(gear) && (scene == Scene.SETUP || scene == Scene.IDENTITY)) {
            glow.palette = Pal.night
            col({ t("주차한 뒤에 이어서 할게요", 72f, fBold, center = true); t("달리는 동안은 조용히 있을게요", 28f, fLight, alpha = .7f, top = 24, center = true) }, center = true)
            caption.text = captionFor(); updateStatus(); return
        }
        ui.removeCallbacks(foldHelp); if (showHelp) ui.postDelayed(foldHelp, 30000)
        if (htmlScene()) { renderWeb(); caption.text = captionFor(); updateStatus(); return }
        glow.intensity = .95f; glow.animateDrift = false
        when (scene) {
            Scene.SETUP -> setupView()
            Scene.IDENTITY -> identityView()
            Scene.MORNING -> if (detailOpen) detailView() else morningView()
            Scene.DRIVING -> drivingView()
            Scene.DESTINY -> destinyView()
            Scene.EVENING_ASK -> eveningAskView()
            Scene.EVENING_REPLY -> eveningReplyView()
            Scene.WEEK -> weekView()
            Scene.GATE -> gateView()
            Scene.COMPANION -> companionView()
            Scene.EVENING_HELLO -> helloView()
            else -> {}
        }
        glow.invalidate()
        caption.text = captionFor()
        updateStatus()
    }

    private fun captionFor(): String = when (scene) {
        Scene.SETUP -> "처음 한 번 — 이름과 생년월일만 (가입·로그인 없음)"
        Scene.IDENTITY -> if (friend()) "① 처음 만남 — 정체성 카드와 내 띠 친구, 이름을 지어 주면 그때부터 내 친구" else "① 정체성 카드 — 태어난 날의 기운으로 '나'를 소개해요"
        Scene.MORNING -> when {
            detailOpen -> "② 원할 때만 한 겹 더 — 옷 · 갈 곳 · 사람 · 좋은 시간"
            !passenger && card().rule == "③감정" -> "⑧ 다음 날 — 어제 고른 한 단어가 오늘 아침의 색이 돼요"
            friend() -> "② 오늘의 카드(기어 P) — 친구가 오늘의 한 줄을 건네요 · 색 · 작은 행동 하나"
            else -> "② 오늘의 카드(기어 P) — 오늘의 색 · 한 줄 · 작은 행동 하나"
        }
        Scene.DESTINY -> "③ 내비 목적지 → 오늘의 데스티니 (주말 · 처음 가는 곳만, 점수 없음)"
        Scene.DRIVING -> if (roadLine != null) "④ 주의 구간 — 내비 알림이 오면 표정과 한 줄이 한 번 바뀌어요 (소리 없음)" else "④ 출발(기어 D) — 멈춘 그림과 큰 글자 한 줄, 말하지 않아요"
        Scene.ROAD -> "⑤ 도착(기어 P) — 오늘 지나온 길을 모아서 한 번, 복은 하루 한 번"
        Scene.EVENING_ASK -> "⑥ 퇴근 도착(기어 P) — \"오늘 어땠어요?\" 한 단어"
        Scene.EVENING_REPLY -> "⑥ 공감 한 줄 + 내일 아침 예고"
        Scene.TALK_ASK, Scene.TALK_REPLY -> "⑦ 조금 더 이야기 — 미리 써 둔 문장, 주차 중에만, 이어 갈지는 운전자가 정해요"
        Scene.WEEK -> "⑨ 7일 뒤 — 쌓인 마음의 흐름, 그리고 자란 친구"
        Scene.GATE -> "기록은 한 번 더 물어보고 열어요 — 앱을 켜는 것만으로는 안 보여요"
        Scene.COMPANION -> "⑩ 같이 탄 날 — 좌석 신호로 알아채고 마음 화면을 가려요"
        Scene.PICK -> "⑩ 옆자리 조합 — 동승자는 띠만 골라요 (생일·저장 없음)"
        Scene.COMBO -> "⑩ 같이 탄 사람과 오늘의 조합 — 좋은 이름과 작은 미션만"
        Scene.MYFRIEND -> "⑪ 내 친구 — 모습 고르기 · 복으로 만나는 수호 친구"
        Scene.MODE -> "설정 — 화면 모드 세 가지(캐릭터 · 차분 · 키치), 계산은 같고 보는 맛만 달라요"
        Scene.EVENING_HELLO -> if (helloBye) "오늘은 여기까지 — 조용히 인사만" else "저녁에 막 탔을 때 — 한 단어는 주차하면 물어요"
    }

    private fun col(block: LinearLayout.() -> Unit, center: Boolean = false, bottom: Boolean = false): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = when { center -> Gravity.CENTER; bottom -> Gravity.BOTTOM or Gravity.START; else -> Gravity.CENTER_VERTICAL or Gravity.START }
            setPadding(dp(130), dp(110), dp(130), dp(150))
        }
        c.block()
        stage.addView(c, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return c
    }

    private fun LinearLayout.t(s: CharSequence, sp: Float, face: Typeface, alpha: Float = 1f, top: Int = 0, color: Int = Color.WHITE, center: Boolean = false): TextView {
        val v = TextView(this@MainActivity).apply {
            text = s; typeface = face; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); this.alpha = alpha
            letterSpacing = if (sp >= 60) -0.03f else 0f; setLineSpacing(0f, 1.15f)
            if (center) gravity = Gravity.CENTER
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        }
        addView(v, LinearLayout.LayoutParams(if (center) MATCH_PARENT else WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) })
        return v
    }

    /** 얇은 첫 줄 + 굵은 둘째 줄 */
    private fun twoWeight(light: String, bold: String): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(light, TypefaceSpan(fLight), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(bold, TypefaceSpan(fBold), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun glassBg(r: Int = 28, a: Int = 20) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(Color.argb(a, 255, 255, 255)); setStroke(dp(1), Color.argb(41, 255, 255, 255))
    }

    private fun chipView(s: String, dot: Int? = null, sp: Float = 26f): TextView = TextView(this).apply {
        text = if (dot != null) "●  $s" else s; typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(Color.WHITE)
        if (dot != null) {
            val sb = SpannableStringBuilder(text); sb.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0); text = sb
        }
        background = glassBg(999, 30); setPadding(dp(26), dp(12), dp(26), dp(12))
    }

    private fun LinearLayout.chip(s: String, dot: Int? = null, top: Int = 0): TextView {
        val v = chipView(s, dot); addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) }); return v
    }

    private fun LinearLayout.pill(s: String, bg: Int, fg: Int, top: Int = 40, onClick: () -> Unit) {
        val b = Button(this@MainActivity).apply {
            text = s; isAllCaps = false; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(fg)
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(bg) }
            stateListAnimator = null; setPadding(dp(52), dp(20), dp(52), dp(20)); setOnClickListener { onClick() }
        }
        addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun glassCard(label: String, value: CharSequence, dot: Int? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glassBg(); setPadding(dp(40), dp(28), dp(40), dp(28))
        t(label, 22f, fReg, alpha = .65f)
        val v = if (dot != null) SpannableStringBuilder("●  ").also { it.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0); it.append(value) } else value
        t(v, 36f, fBold, top = 8)
    }

    private fun dateLabel(d: LocalDate, animalDay: String) =
        "${d.monthValue}월 ${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)} · ${animalDay.replace(Regex("\\(.\\)"), "")}"

    /** "오늘은 물의 기운. 서두르지 않아도 괜찮아요." → ("물의 기운,", "서두르지 않아도 괜찮아요.") */
    private fun splitLine(line: String): Pair<String, String> {
        val i = line.indexOf(". ")
        if (i < 0) return Pair("", line)
        return Pair(line.substring(0, i).removePrefix("오늘은 ") + ",\n", line.substring(i + 2))
    }

    // ───────────────────────── 장면 1 — 처음 한 번
    private fun setupView() {
        glow.palette = Pal.night
        col({
            t("기운차", 120f, fBold)
            t("타면 탈수록 나를 알아가는 차", 38f, fLight, alpha = .85f, top = 8)
            t("부를 이름 (별명도 괜찮아요)", 24f, fReg, alpha = .6f, top = 60)
            val et = EditText(this@MainActivity).apply {
                setText(setupName()); typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); setTextColor(Color.WHITE)
                setSingleLine(); background = glassBg(20); setPadding(dp(28), dp(16), dp(28), dp(16))
            }
            addView(et, LinearLayout.LayoutParams(dp(520), WRAP_CONTENT).apply { topMargin = dp(10) })
            t("생년월일 (양력)", 24f, fReg, alpha = .6f, top = 30)
            val dpk = DatePicker(this@MainActivity).apply {
                calendarViewShown = false; spinnersShown = true
                val b = store.birth ?: LocalDate.of(1990, 5, 17)
                updateDate(b.year, b.monthValue - 1, b.dayOfMonth)
            }
            addView(dpk, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            t("생년월일은 띠와 오늘의 색을 고르는 데만 써요. 태어난 시각은 몰라도 괜찮아요.", 24f, fLight, alpha = .7f, top = 10)
            t("이름 · 생년월일 · 고른 한 단어는 이 차 안에만 남고, 설정에서 언제든 지울 수 있어요.", 24f, fLight, alpha = .7f, top = 4)
            pill("나를 알아보기", Pal.water.acc, Color.parseColor("#08121f"), top = 30) {
                if (et.text.toString().isBlank()) { et.requestFocus(); return@pill }
                store.name = et.text.toString().trim().takeChars(8)
                store.birth = LocalDate.of(dpk.year, dpk.month + 1, dpk.dayOfMonth)
                scene = Scene.IDENTITY; render()
            }
        })
    }

    /** 처음 설정의 이름 칸: 저장된 이름, 없으면 시연 때만 예시 이름 */
    private fun setupName(): String = store.name.ifEmpty { if (demoMode) "태현" else "" }

    // 장면 1 — 정체성 카드
    private fun identityView() {
        val id = engine.identity(store.birth!!)
        val pal = Pal.of(id.element); glow.palette = pal
        col({
            t("태어난 날로 보면, ${store.name}님은", 32f, fReg, alpha = .75f)
            t(twoWeight(id.title.removeSuffix(" 같은 사람") + " ", "같은 사람"), 140f, fBold, top = 20)
            t(id.desc, 40f, fLight, alpha = .9f, top = 30)
            val chips = LinearLayout(this@MainActivity)
            chips.addView(chipView("${id.animal}띠"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(16) })
            chips.addView(chipView("태어난 날의 기운 · ${id.elementWord}"))
            addView(chips, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(50) })
            t("재미로 보는 풀이예요. 맞지 않아도, 따르지 않아도 괜찮아요.", 26f, fLight, alpha = .7f, top = 26)
            t("차에 탈 때마다 오늘의 색과 한 줄을 드릴게요.", 28f, fLight, alpha = .7f, top = 50)
            pill("시작하기", pal.acc, Color.parseColor("#08121f"), top = 30) {
                scene = Scene.MORNING; render()   // 처음 만난 날은 저녁이어도 오늘의 카드부터
            }
        })
    }

    // 장면 2 — 아침 정차: 오늘의 카드 (30초)
    private fun morningView() {
        val cd = card(); val pal = Pal.of(cd.element); glow.palette = pal
        val t = store.today()
        col({
            chip(dateLabel(t, cd.animalDay), pal.acc)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            if (!passenger && cd.rule == "③감정") {
                val lt = store.latestTag(t)
                chip("${if (lt?.second == 1) "어제" else "지난번에"} 고른 마음을 담아 오늘 색을 골랐어요").also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(30) }
            }
            store.latestTag(t)?.takeIf { !passenger && it.second == 1 && it.first in Engine.POSITIVE }?.let {
                chip("어제 좋았던 마음을 담아 오늘 색을 골랐어요").also { v -> (v.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(30) }
            }
            t("${greeting()}, ${store.name}님", 32f, fLight, alpha = .9f)
            val (a0, b) = splitLine(cd.line); val a = if (a0.isEmpty()) a0 else "${cd.colorName}의 날,\n"
            t(twoWeight(a, b), 100f, fBold, top = 16)
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(glassCard("작은 행동 하나", cd.action), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(22) })
            if (cd.help != null) {
                addView(TextView(this@MainActivity).apply {
                    text = if (showHelp) cd.help else "힘들 땐 전화할 곳  →"; typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(Color.WHITE); alpha = .85f
                    setPadding(0, dp(24), 0, 0); setOnClickListener { showHelp = !showHelp; render() }
                })
            }
            row.addView(glassCard("오늘의 색", cd.colorName, pal.acc), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(30) })
            if (cd.help == null) row.addView(TextView(this@MainActivity).apply {
                text = "더 알고 싶어요  →"; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(Color.WHITE); alpha = .9f
                setPadding(dp(16), dp(30), dp(16), dp(30)); setOnClickListener { detailOpen = true; render() }
            })
            if (!passenger) row.addView(TextView(this@MainActivity).apply {
                text = "내 기록"; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(Color.WHITE); alpha = .92f
                background = glassBg(999, 40); setPadding(dp(28), dp(18), dp(28), dp(18)); setOnClickListener { scene = Scene.GATE; render() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(20) })
            row.addView(TextView(this@MainActivity).apply {
                text = soloLabel(); typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(Color.WHITE); alpha = .92f
                background = glassBg(999, 40); setPadding(dp(28), dp(18), dp(28), dp(18)); soloClick(this)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
            row.addView(TextView(this@MainActivity).apply {
                text = "설정"; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(Color.WHITE); alpha = .92f
                background = glassBg(999, 40); setPadding(dp(28), dp(18), dp(28), dp(18)); setOnClickListener { wipeAsk = false; scene = Scene.MODE; render() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
            addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(50) })
        }, bottom = true)
    }

    /** 자리에 무게가 잡혀 있는데(짐·빈 카시트) 혼자라고 알릴 때는 길게 눌러야 한다 */
    private fun seatedNow() = passengerDemo == null && seatOcc.values.any { it }
    private fun soloLabel() = if (!passenger) "동승자 있어요" else if (seatedNow()) "지금은 혼자예요 (길게 누르기)" else "혼자예요"
    private fun soloClick(v: View) {
        if (!passenger) v.setOnClickListener { setByHand(true); onPassengerChanged() }
        else if (seatedNow()) v.setOnLongClickListener { soloNow(); true }
        else v.setOnClickListener { soloNow() }
    }

    // 장면 2 — 원할 때만 한 겹 더
    private fun detailView() {
        val cd = card(); val pal = Pal.of(cd.element); glow.palette = pal
        col({
            chip(dateLabel(store.today(), cd.animalDay), pal.acc)
            t(twoWeight("${cd.elementWord ?: "오늘"}의 기운, ", "조금 더 알아볼까요"), 60f, fBold, top = 50)
            val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = glassBg(32); setPadding(dp(50), dp(16), dp(50), dp(20)) }
            fun row(k: String, v: String) {
                val r = LinearLayout(this@MainActivity).apply { setPadding(0, dp(22), 0, dp(22)) }
                r.addView(TextView(this@MainActivity).apply { text = k; typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(Color.WHITE); alpha = .6f }, LinearLayout.LayoutParams(dp(170), WRAP_CONTENT))
                r.addView(TextView(this@MainActivity).apply { text = v; typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); setTextColor(Color.WHITE) })
                box.addView(r)
                box.addView(View(this@MainActivity).apply { setBackgroundColor(Color.argb(36, 255, 255, 255)) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
            }
            row("옷", cd.clothes); row("갈 곳", cd.place); row("사람", "${cd.person}에게 ${cd.phrase}"); row("좋은 시간", cd.goodTime.replace(" [설계값]", ""))
            box.addView(TextView(this@MainActivity).apply { text = "이렇게 골랐어요 — ${cd.reason}"; typeface = fLight; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Color.WHITE); alpha = .6f; setPadding(0, dp(20), 0, 0) })
            addView(box, LinearLayout.LayoutParams(dp(1150), WRAP_CONTENT).apply { topMargin = dp(40) })
            pill("접기", Color.argb(40, 255, 255, 255), Color.WHITE, top = 36) { detailOpen = false; render() }
        })
    }

    // 장면 4 — 출발: 접힘 (큰 글자 한 줄, 터치 없음, 말 없음)
    private fun drivingView() {
        val cd = card(); val pal = Pal.of(cd.element)
        glow.palette = Pal.night; glow.intensity = .35f; glow.animateDrift = false
        col({
            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = 4f; setColor(Color.parseColor("#9aa6c4")) } }, LinearLayout.LayoutParams(dp(120), dp(5)))
            val line = driveLine()
            t(line, 84f, fBold, top = 56, center = true)
            if (roadLine != null) t("조심해서 지나가요", 28f, fReg, alpha = .8f, top = 40, center = true)
        }, center = true)
    }

    // 장면 3 — 오늘의 데스티니
    private fun destinyView() {
        val (name, lat, lon) = destiny ?: return morningView()
        val cd = card(); val pal = Pal.of(cd.element); glow.palette = pal
        val dDir = bearingDir(curLat, curLon, lat, lon)
        val rowL = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(130), dp(110), dp(100), dp(150)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.t("오늘의 데스티니", 30f, fReg, alpha = .75f)
        left.t(name, 104f, fBold, top = 16)
        if (dDir == cd.direction) {
            left.t("오늘 기운이 가리키는 ${cd.direction}쪽 길이에요.", 40f, fReg, top = 36)
            left.t("마음 가는 대로, 천천히 다녀오세요.", 30f, fLight, alpha = .85f, top = 20)
        } else {
            left.t("${dDir}쪽으로 가는 길이네요.", 40f, fReg, top = 36)
            left.t("오늘 기운은 ${cd.direction}쪽 — 돌아오는 길에 ${cd.weekendPlace} 한 곳 들러 봐도 좋아요.", 30f, fLight, alpha = .85f, top = 20)
        }
        left.pill("좋아요", Color.argb(40, 255, 255, 255), Color.WHITE, top = 50) { scene = parkedScene(); render() }
        rowL.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(CompassView(this, dDir, cd.direction, pal.acc, fReg), LinearLayout.LayoutParams(dp(520), dp(520)))
        right.t("● 목적지 방향   ┄ 오늘 기운 방향", 22f, fReg, alpha = .6f)
        rowL.addView(right, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        stage.addView(rowL, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // 장면 5 — 퇴근 정차: "오늘 어땠어요?"
    private val emoDot = mapOf("좋았어요" to "#ffd166", "뿌듯해요" to "#5fb38a", "괜찮아요" to "#9fdcff", "생각이 많아요" to "#c9a35a", "짜증나요" to "#d56a55", "불안해요" to "#5a7fd6", "우울해요" to "#8a8fa8")

    private fun eveningAskView() {
        glow.palette = Pal.night
        col({
            t("수고 많으셨어요, ${store.name}님", 30f, fReg, alpha = .75f)
            t("오늘 어땠어요?", 120f, fBold, top = 16)
            t("한 단어만 골라 주세요. 내일 아침의 색이 돼요.", 30f, fLight, alpha = .75f, top = 14)
            val rows = listOf(Engine.EMOTIONS.take(4), Engine.EMOTIONS.drop(4))
            rows.forEachIndexed { ri, words ->
                val r = LinearLayout(this@MainActivity)
                words.forEach { e ->
                    val b = TextView(this@MainActivity).apply {
                        val sb = SpannableStringBuilder("●  $e"); sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(emoDot.getValue(e))), 0, 1, 0)
                        text = sb; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f); setTextColor(Color.WHITE)
                        background = glassBg(28, 26); setPadding(dp(44), dp(28), dp(44), dp(28)); isClickable = true
                        setOnClickListener {
                            if (isDriving(gear)) return@setOnClickListener   // 주행 중엔 받지 않음
                            store.setTag(store.today(), e)
                            wordBok = if (store.awardOnce("word:${store.today()}", 5)) 5 else 0
                            lastReply = engine.checkinReply(e)
                            scene = Scene.EVENING_REPLY; render()
                        }
                    }
                    r.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(20) })
                }
                addView(r, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(if (ri == 0) 60 else 20) })
            }
            val askRow = LinearLayout(this@MainActivity)
            askRow.addView(Button(this@MainActivity).apply {
                text = "오늘은 넘어갈게요"; isAllCaps = false; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(40, 255, 255, 255)) }; stateListAnimator = null; setPadding(dp(40), dp(16), dp(40), dp(16))
                setOnClickListener { store.skipDay = store.today().toString(); helloBye = true; scene = Scene.EVENING_HELLO; render() }
            })
            askRow.addView(TextView(this@MainActivity).apply {
                text = soloLabel(); typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Color.WHITE); alpha = .85f
                background = glassBg(999, 26); setPadding(dp(30), dp(16), dp(30), dp(16)); soloClick(this)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(16) })
            addView(askRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
            t("기록은 이 차 안에만, 다시 볼 땐 한 번 더 물어봐요", 22f, fLight, alpha = .5f, top = 30)
        })
    }

    // 장면 5 — 공감 + 내일 예고
    private fun eveningReplyView() {
        glow.palette = Pal.night
        val tagToday = store.tagOn(store.today())
        val tomorrow = store.today().plusDays(1)
        val next = engine.morning(store.birth!!, tomorrow, tagToday, 1, false, store.negStreak(tomorrow, engine.restTags()), null)
        val np = Pal.of(next.element)
        col({
            chip(if (tagToday != null) "$tagToday · 저장했어요" else "")
            t(lastReply?.first ?: "", 84f, fBold, top = 36)
            lastReply?.second?.let { t(it, 38f, fLight, alpha = .9f, top = 24) }
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(OrbView(this@MainActivity, np.orb, glow = true), LinearLayout.LayoutParams(dp(200), dp(200)))
            val lab = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            lab.t("내일 아침의 색", 22f, fReg, alpha = .6f); lab.t(next.colorName, 38f, fBold, top = 4)
            row.addView(lab, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
            addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(30) })
            val btns = LinearLayout(this@MainActivity)
            btns.addView(Button(this@MainActivity).apply {
                text = "내일 아침에 만나요"; isAllCaps = false; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(40, 255, 255, 255)) }; stateListAnimator = null; setPadding(dp(44), dp(18), dp(44), dp(18))
                setOnClickListener { helloBye = true; scene = Scene.EVENING_HELLO; render() }
            })
            btns.addView(Button(this@MainActivity).apply {
                text = "다시 고를게요"; isAllCaps = false; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Color.argb(190, 255, 255, 255))
                background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(20, 255, 255, 255)) }; stateListAnimator = null; setPadding(dp(36), dp(18), dp(36), dp(18))
                setOnClickListener { redoWord() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(16) })
            addView(btns, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(30) })
        })
    }

    /** 잘못 누른 한 단어를 지우고 다시 묻는다 */
    private fun redoWord() {
        if (isDriving(gear) || passenger) return
        store.clearTag(store.today()); lastReply = null; arrivedOn = store.today(); scene = Scene.EVENING_ASK; render()
    }

    // 장면 7 — 7일 뒤: 쌓임
    private fun weekView() {
        glow.palette = Pal.night; glow.intensity = .5f
        val t = store.today()
        val days = (7 downTo 1).map { t.minusDays(it.toLong()) }
        val anyDemo = days.any { store.demoFilled(it) }
        col({
            t("${store.name}님의 지난 7일", 30f, fReg, alpha = .75f)
            t(twoWeight("타면 탈수록, ", "나를 알아가요"), 92f, fBold, top = 14)
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            days.forEach { d ->
                val lt = store.latestTag(d)
                val c = engine.morning(store.birth!!, d, lt?.first, lt?.second ?: 99, false, store.negStreak(d, engine.restTags()), null)
                val cell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                cell.addView(OrbView(this@MainActivity, Pal.of(c.element).orb), LinearLayout.LayoutParams(dp(110), dp(110)))
                cell.t(d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), 24f, fReg, alpha = .7f, top = 14, center = true)
                cell.t(store.tagOn(d) ?: "-", 22f, fMed, top = 4, center = true)
                row.addView(cell, LinearLayout.LayoutParams(dp(200), WRAP_CONTENT))
            }
            addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(56) })
            val tags = days.mapNotNull { store.tagOn(it) }
            val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = glassBg(28); setPadding(dp(50), dp(34), dp(50), dp(34)) }
            if (tags.isNotEmpty()) {
                val neg = tags.filter { it != "괜찮아요" }
                val top = (neg.ifEmpty { tags }).groupingBy { it }.eachCount().maxBy { it.value }
                val wd = days.filter { store.tagOn(it) == top.key }.map { it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN) }.distinct()
                val lead = if (neg.isNotEmpty() && tags.count { it == "괜찮아요" } >= top.value) "\"괜찮아요\" 다음으로 자주 고른 마음은" else "가장 자주 고른 마음은"
                box.t("$lead \"${top.key}\" — ${top.value}번, 주로 ${wd.joinToString("·")}", 38f, fBold)
                val fillWord = engine.fillOf(top.key)?.let { engine.elem(it).getString("element_word") }
                val ok = tags.count { it == "괜찮아요" }
                box.t((if (fillWord != null) "그 다음 날 아침엔 ${fillWord}의 기운으로 채워 드렸어요. " else "") + "\"괜찮아요\"는 ${ok}번이었어요.", 28f, fLight, alpha = .85f, top = 12)
            } else box.t("아직 기록이 없어요. 퇴근길에 한 단어만 남겨 주세요.", 34f, fReg)
            addView(box, LinearLayout.LayoutParams(dp(1400), WRAP_CONTENT).apply { topMargin = dp(60) })
            if (anyDemo) t("시연용 예시 기록 포함", 20f, fLight, alpha = .45f, top = 20)
            pill("닫기", Color.argb(40, 255, 255, 255), Color.WHITE, top = 24) { scene = parkedScene(); render() }
        })
    }

    // 기록 관문 — 앱을 켜는 것만으로는 기록이 안 보이게, 한 번 더 선택
    private fun gateView() {
        glow.palette = Pal.night
        col({
            t("내 기록", 30f, fReg, alpha = .75f)
            if (passenger) {
                t(twoWeight("기록은 ", "혼자일 때만 열려요"), 84f, fBold, top = 14)
                t("옆에 누가 타고 있어요.", 32f, fLight, alpha = .85f, top = 20)
                pill("돌아가기", Color.argb(40, 255, 255, 255), Color.WHITE, top = 50) { scene = parkedScene(); render() }
            } else {
                t(twoWeight("지난 마음 기록을 ", "열어 볼까요?"), 84f, fBold, top = 14)
                t("화면에 그동안 고른 마음 단어가 보여요.", 32f, fLight, alpha = .85f, top = 20)
                val row = LinearLayout(this@MainActivity)
                row.addView(Button(this@MainActivity).apply {
                    text = "혼자예요, 열기"; isAllCaps = false; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(Color.parseColor("#08121f"))
                    background = GradientDrawable().apply { cornerRadius = 999f; setColor(Pal.night.acc) }; stateListAnimator = null; setPadding(dp(52), dp(20), dp(52), dp(20))
                    setOnClickListener { scene = Scene.WEEK; render() }
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "다음에"; isAllCaps = false; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(40, 255, 255, 255)) }; stateListAnimator = null; setPadding(dp(52), dp(20), dp(52), dp(20))
                    setOnClickListener { scene = parkedScene(); render() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(20) })
                addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(50) })
            }
        })
    }

    // 동승 중 퇴근 — 체크인 대신 (옆자리 조합은 화면 테마 확정 후 연결)
    private fun companionView() {
        glow.palette = Pal.fire
        col({
            chip("같이 타고 있어요")
            t(twoWeight("오늘은 ", "같이 가는 날"), 110f, fBold, top = 24)
            t("오늘은 옆자리와 즐겁게 가세요.", 34f, fLight, alpha = .9f, top = 24)
            pill("옆자리 조합 보기", Pal.fire.acc, Color.parseColor("#2a1206"), top = 50) { scene = Scene.PICK; render() }
            addView(TextView(this@MainActivity).apply {
                text = soloLabel(); typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Color.WHITE); alpha = .75f
                background = glassBg(999, 20); setPadding(dp(30), dp(14), dp(30), dp(14)); soloClick(this)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        })
    }

    private fun helloText(): Pair<String, String> =
        if (helloBye) Pair("오늘도 수고 많으셨어요", if (store.talkFollow(store.today().plusDays(1)) != null) "조심히 들어가세요. 내일 아침에 한마디 이어 갈게요." else "조심히 들어가세요. 내일 아침에 만나요.")
        else Pair("수고 많으셨어요, ${store.name}님", "주차하면 한 단어만 물어볼게요. 지금 남기고 싶으면 아래를 눌러 주세요.")

    private fun helloView() {
        glow.palette = Pal.night
        val (a, b) = helloText()
        col({
            t(a, 92f, fBold); t(b, 36f, fLight, alpha = .85f, top = 24)
            // 앱을 주차한 뒤에 켠 사람도 한 단어를 남길 수 있게
            if (canAskNow()) pill("지금 한 단어 남기기", Pal.night.acc, Color.parseColor("#08121f"), top = 44) { askNow() }
            val hr = LinearLayout(this@MainActivity)
            hr.addView(TextView(this@MainActivity).apply {
                text = soloLabel(); typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Color.WHITE); alpha = .85f
                background = glassBg(999, 26); setPadding(dp(30), dp(16), dp(30), dp(16)); soloClick(this)
            })
            hr.addView(TextView(this@MainActivity).apply {
                text = "설정"; typeface = fMed; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Color.WHITE); alpha = .85f
                background = glassBg(999, 26); setPadding(dp(30), dp(16), dp(30), dp(16)); setOnClickListener { wipeAsk = false; scene = Scene.MODE; render() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
            addView(hr, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        })
    }

    /** 혼자이고 오늘 한 단어를 아직 안 남겼으면, 인사 화면에서 바로 남길 수 있다 */
    private fun canAskNow() = !passenger && isEvening() && store.tagOn(store.today()) == null
    private fun askNow() { store.skipDay = ""; arrivedOn = store.today(); scene = parkedScene(); render() }

    /** 캐릭터 모드는 모든 화면, 키치 모드는 처음 설정·정체성 카드만 빼고 모두 웹 화면으로 그린다 */
    private fun htmlScene(): Boolean = when {
        scene == Scene.MODE || scene == Scene.PICK || scene == Scene.COMBO || scene == Scene.SETUP || scene in friendOnly -> true
        friend() -> true
        kitsch() -> scene != Scene.SETUP && scene != Scene.IDENTITY
        else -> false
    }

    private fun htmlFile(): String =
        if (friend() || scene == Scene.MODE || scene == Scene.SETUP || scene in friendOnly || (!kitsch() && (scene == Scene.PICK || scene == Scene.COMBO))) "friend/friend.html" else "kitsch/kitsch.html"

    private val orbHex = mapOf("목" to "#5fb38a", "화" to "#ef7a4d", "토" to "#d9a35e", "금" to "#cfd6e2", "수" to "#4d9dff")

    private fun webData(): org.json.JSONObject {
        val o = org.json.JSONObject()
        val t = store.today()
        o.put("count", store.rideCount()).put("passenger", passenger).put("theme", store.theme).put("dex", store.dexCount())
        val me = store.birth?.let { engine.identity(it) }
        o.put("name", store.name).put("fname", store.friendName).put("animal", me?.animal ?: "말").put("myEl", me?.element ?: "수")
            .put("g", stage()).put("gMax", stageMax()).put("ch", store.friendKind.ifEmpty { me?.animal ?: "말" }).put("bok", store.bok).put("tripNote", tripNote).put("soloLabel", soloLabel()).put("soloLong", passenger && seatedNow())
        when (scene) {
            Scene.SETUP -> o.put("scene", "setup").put("birth", (store.birth ?: LocalDate.of(1990, 5, 17)).toString()).put("name", setupName())
            Scene.IDENTITY -> o.put("scene", "friend").put("title", me!!.title).put("desc", me.desc).put("dayStem", me.dayStem).put("myWord", me.elementWord)
            Scene.EVENING_HELLO -> helloText().let { o.put("scene", "hello").put("msg1", it.first).put("msg2", it.second).put("bye", helloBye).put("canAsk", canAskNow()) }
            Scene.DRIVING -> {
                val cd = card()
                o.put("scene", "driving").put("element", cd.element ?: "금").put("alert", roadLine != null)
                    .put("line", driveLine())
            }
            Scene.DESTINY -> {
                val cd = card(); val d = destiny
                if (d == null) o.put("scene", "hello").put("msg1", "목적지가 아직 없어요").put("msg2", "내비에 목적지를 넣으면 알려 드릴게요.").put("bye", false)
                else {
                    val dDir = bearingDir(curLat, curLon, d.second, d.third)
                    o.put("scene", "destiny").put("place", d.first).put("dDir", dDir).put("direction", cd.direction)
                        .put("same", dDir == cd.direction).put("weekendPlace", cd.weekendPlace).put("element", cd.element ?: "금")
                }
            }
            Scene.WEEK -> {
                val days = (7 downTo 1).map { t.minusDays(it.toLong()) }
                val arr = org.json.JSONArray()
                days.forEach { d ->
                    val lt = store.latestTag(d)
                    val c = engine.morning(store.birth!!, d.plusDays(1), store.tagOn(d), 1, false, store.negStreak(d.plusDays(1), engine.restTags()), null)
                    arr.put(org.json.JSONObject().put("w", d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)).put("tag", store.tagOn(d) ?: "-")
                        .put("el", c.element ?: "금").put("hex", orbHex[c.element] ?: "#cfd6e2"))
                }
                val tags = days.mapNotNull { store.tagOn(it) }
                var s1 = "아직 기록이 없어요. 퇴근길에 한 단어만 남겨 주세요."; var s2 = ""
                if (tags.isNotEmpty()) {
                    val neg = tags.filter { it != "괜찮아요" }
                    val top = (neg.ifEmpty { tags }).groupingBy { it }.eachCount().maxBy { it.value }
                    val wd = days.filter { store.tagOn(it) == top.key }.map { it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN) }.distinct()
                    val ok = tags.count { it == "괜찮아요" }
                    val lead = if (neg.isNotEmpty() && ok >= top.value) "\"괜찮아요\" 다음으로 자주 고른 마음은" else "가장 자주 고른 마음은"
                    s1 = "$lead \"${top.key}\" — ${top.value}번, 주로 ${wd.joinToString("·")}"
                    val fillWord = engine.fillOf(top.key)?.let { engine.elem(it).getString("element_word") }
                    s2 = (if (fillWord != null) "그 다음 날 아침엔 ${fillWord}의 기운으로 채워 드렸어요. " else "") + "\"괜찮아요\"는 ${ok}번이었어요."
                    if (top.value <= 1) {
                        s1 = "이번 주는 ${tags.distinct().size}가지 마음이 지나갔어요"
                        val hard = days.firstOrNull { store.tagOn(it) in setOf("불안해요", "우울해요", "짜증나요") }
                        s2 = if (hard != null) "${hard.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}은 힘들었죠. 그래도 한 단어를 남겨 줘서 고마워요." else "매일 한 단어를 남겨 줘서 고마워요."
                    }
                }
                o.put("scene", "week").put("days", arr).put("s1", s1).put("s2", s2).put("demo", days.any { store.demoFilled(it) })
                    .put("grew", store.rideCount() >= 7)
            }
            Scene.ROAD -> {
                val arr = org.json.JSONArray()
                lastTrip.forEach { (k, v) -> arr.put(org.json.JSONObject().put("k", k).put("n", v)) }
                o.put("scene", "road").put("events", arr).put("tripBok", tripBok)
            }
            Scene.MYFRIEND -> o.put("scene", "myfriend").put("owned", org.json.JSONArray(store.owned.toList())).put("rides", store.rideCount()).put("pendingBuy", pendingBuy ?: "")
            Scene.TALK_ASK -> o.put("scene", "talkask").put("tag", store.tagOn(t) ?: "")
            Scene.TALK_REPLY -> {
                val tp = talkTopic?.takeIf { TALK.containsKey(it) } ?: "모르겠어요"
                o.put("scene", "talkreply").put("topic", tp).put("line", TALK.getValue(tp).first).put("action", TALK.getValue(tp).second)
            }
            Scene.MODE -> o.put("scene", "mode").put("wipeAsk", wipeAsk).put("hasBirth", store.birth != null)
            Scene.PICK -> o.put("scene", "pick")
            Scene.COMPANION -> o.put("scene", "companion")
            Scene.COMBO -> {
                val id = engine.identity(store.birth!!)
                o.put("scene", "combo").put("partner", partner)
                    .put("me", org.json.JSONObject().put("el", id.element).put("animal", id.animal))
            }
            Scene.GATE -> o.put("scene", "gate")
            Scene.EVENING_ASK -> o.put("scene", "checkin").put("emotions", org.json.JSONArray(Engine.EMOTIONS))
            Scene.EVENING_REPLY -> {
                val tag = store.tagOn(t) ?: ""
                val spicy = kitsch() && store.tone == "매운맛" && SPICY_REPLY.containsKey(tag)   // 불안·우울은 매운맛을 쓰지 않음
                val fill = engine.fillOf(tag)
                o.put("scene", "reply").put("tag", tag).put("empathy", if (spicy) SPICY_REPLY.getValue(tag) else (lastReply?.first ?: ""))
                    .put("next", (lastReply?.second ?: "").let { if (spicy) it.replace("준비해 둘게요.", "준비해 둘게.") else it })
                    .put("nextEl", fill ?: "").put("nextColor", fill?.let { engine.elem(it).getString("color_name") } ?: "")
                    .put("positive", tag in Engine.POSITIVE).put("wordBok", wordBok)
                    .put("canTalk", friend() && tag !in Engine.POSITIVE && tag != "괜찮아요")
            }
            else -> {
                val cd = card()
                val lt = store.latestTag(t)
                o.put("elementWord", cd.elementWord ?: "오늘").put("clothes", cd.clothes).put("place", cd.place)
                    .put("person", "${cd.person}에게 ${cd.phrase}").put("goodTime", cd.goodTime.replace(" [설계값]", "")).put("reason", cd.reason)
                    .put("ydayLong", when { passenger -> ""; cd.rule == "③감정" -> "${if (lt?.second == 1) "어제" else "지난번에"} 고른 마음을 담아 오늘 색을 골랐어요"; lt != null && lt.second == 1 && lt.first in Engine.POSITIVE -> "어제 좋았던 마음을 담아 오늘 색을 골랐어요"; else -> "" })
                o.put("wear", cd.clothes.substringBefore(",").substringBefore("·").trim().take(12))
                val grewNow = if (friend() && !passenger && !detailOpen && stageMax() > store.seenStage && store.friendKind.isEmpty()) stageMax() else 0
                if (grewNow > 0) store.seenStage = grewNow   // 자라는 순간은 한 번만 크게 보여 준다
                o.put("grewNow", grewNow).put("justMet", store.justMet)
                o.put("follow", if (passenger) "" else (store.talkFollow(t)?.let { TALK_FOLLOW[it] } ?: "")).put("greet", greeting())
                o.put("scene", if (detailOpen) "detail" else "morning").put("date", dateLabel(t, cd.animalDay)).put("element", cd.element ?: "금")
                    .put("line", cd.line).put("spicy", if (gentleDay()) cd.line else (SPICY[cd.element] ?: cd.line)).put("action", cd.action)
                    .put("direction", cd.direction).put("colorName", cd.colorName).put("tone", store.tone)
                    .put("rest", cd.rule.startsWith("①") || cd.rule.startsWith("②")).put("help", if (showHelp) cd.help else null)
                    .put("yday", when { passenger -> "오늘의 기운 배달 완료!"; cd.rule == "③감정" -> "${if (lt?.second == 1) "어제" else "지난번에"} 고른 마음 반영 완료!"; lt != null && lt.second == 1 && lt.first in Engine.POSITIVE -> "어제의 좋은 기운 그대로!"; else -> "오늘의 기운 배달 완료!" })
            }
        }
        return o
    }

    private fun renderWeb() {
        glow.palette = Pal.night
        val file = htmlFile()
        val w = webs[file] ?: android.webkit.WebView(this).also { v ->
            v.settings.javaScriptEnabled = true
            v.settings.textZoom = 100   // 기기 글꼴 크기와 상관없이 같은 배치(화면 전체를 창에 맞춰 키우고 줄임)
            v.settings.allowFileAccess = true
            v.setBackgroundColor(Color.parseColor(if (file.startsWith("friend")) "#f7f8fa" else "#fff4dc"))
            v.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun on(a: String) { ui.post { onWeb(a) } }
            }, "Android")
            v.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    webs.remove(file); webReady.remove(file); (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy()
                    ui.post { render() }; return true
                }
                override fun onPageFinished(view: android.webkit.WebView, url: String) {
                    webReady.add(file); pendingJson.remove(file)?.let { view.evaluateJavascript("render($it)", null) }
                }
            }
            v.loadUrl("file:///android_asset/$file"); webs[file] = v
        }
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        stage.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val json = webData().toString()
        if (file in webReady) w.evaluateJavascript("render($json)", null) else pendingJson[file] = json
    }

    private fun hideKeyboard() {   // 화면이 바뀌면 열려 있던 글자판을 닫는다
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            window?.decorView?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
            currentFocus?.clearFocus()
        } catch (_: Exception) { }
    }

    private fun onWeb(a: String) {
        tripNote = 0   // 도착 복 알림은 처음 한 화면에서만
        if (!a.startsWith("kind:")) pendingBuy = null
        when {
            a == "more" -> { detailOpen = true; render() }
            a == "fold" -> { detailOpen = false; render() }
            a == "pickgo" -> { scene = Scene.PICK; render() }
            a == "asknow" -> askNow()
            a == "grewok" -> { store.seenStage = stageMax(); render() }
            a == "withme" -> { setByHand(true); onPassengerChanged() }
            a == "solo" -> soloNow()
            a == "redo" -> redoWord()
            a == "settings" -> { wipeAsk = false; scene = Scene.MODE; render() }
            a == "wipeask" -> { wipeAsk = true; render() }
            a == "wipecancel" -> { wipeAsk = false; render() }
            a == "wipe" -> { store.wipeAll(); resetState(); render() }
            a == "myfriend" -> { scene = Scene.MYFRIEND; render() }
            a == "back" -> { scene = parkedScene(); render() }
            a == "talk" -> { if (!isDriving(gear)) { scene = Scene.TALK_ASK; render() } }
            a.startsWith("topic:") -> { talkTopic = a.removePrefix("topic:"); scene = Scene.TALK_REPLY; render() }
            a == "talkstop" -> { store.setTalk(null, store.today()); helloBye = true; scene = Scene.EVENING_HELLO; render() }
            a == "talkkeep" -> { store.setTalk(talkTopic, store.today()); helloBye = true; scene = Scene.EVENING_HELLO; render() }
            a == "talkclear" -> { store.setTalk(null, store.today()); render() }
            a.startsWith("form:") -> { store.friendForm = a.removePrefix("form:").toIntOrNull() ?: -1; store.friendKind = ""; render() }
            a.startsWith("kind:") -> {
                val k = a.removePrefix("kind:")
                if (k.isEmpty() || k in store.owned) { store.friendKind = k; pendingBuy = null }
                else if (store.bok >= 150) {
                    if (pendingBuy != k) pendingBuy = k
                    else { store.bok = store.bok - 150; store.own(k); store.friendKind = k; pendingBuy = null }
                }
                render()
            }
            a.startsWith("rename:") -> { store.friendName = a.removePrefix("rename:").trim().takeChars(8).ifEmpty { store.friendName }; render() }
            a == "ok" -> when (scene) {
                Scene.DESTINY -> { scene = parkedScene(); render() }
                Scene.ROAD -> { lastTrip.clear(); scene = parkedScene(); render() }
                Scene.EVENING_ASK -> { store.skipDay = store.today().toString(); helloBye = true; scene = Scene.EVENING_HELLO; render() }
                Scene.PICK, Scene.COMBO, Scene.EVENING_REPLY, Scene.COMPANION -> { helloBye = true; scene = Scene.EVENING_HELLO; render() }
                else -> {}
            }
            a.startsWith("setup:") && isDriving(gear) -> {}
            a.startsWith("fname:") && isDriving(gear) -> {}
            a.startsWith("setup:") -> {
                val parts = a.removePrefix("setup:").split("|")
                store.name = parts.getOrNull(0)?.trim().orEmpty().take(8).ifEmpty { setupName().ifEmpty { "운전자" } }
                store.birth = try { LocalDate.parse(parts.getOrNull(1) ?: "") } catch (_: Throwable) { LocalDate.of(1990, 5, 17) }
                scene = Scene.IDENTITY; render()
            }
            a.startsWith("fname:") -> {
                store.friendName = a.removePrefix("fname:").trim().takeChars(8).ifEmpty { "콩이" }; store.justMet = true
                scene = Scene.MORNING; render()   // 처음 만난 날은 저녁이어도 오늘의 카드부터
            }
            a == "gate" -> { scene = Scene.GATE; render() }
            a == "open" -> { if (!passenger) { scene = Scene.WEEK; render() } }
            a == "later" -> { scene = parkedScene(); render() }
            a == "tone" -> { store.tone = if (store.tone == "매운맛") "순한맛" else "매운맛"; render() }
            a == "help" -> { showHelp = !showHelp; render() }
            a.startsWith("theme:") -> { store.theme = a.removePrefix("theme:"); detailOpen = false; wipeAsk = false; showHelp = false; scene = if (store.birth == null) Scene.SETUP else Scene.MORNING; render() }
            a.startsWith("emo:") -> {
                if (isDriving(gear)) return
                val e = a.removePrefix("emo:"); store.setTag(store.today(), e)
                wordBok = if (store.awardOnce("word:${store.today()}", 5)) 5 else 0
                lastReply = engine.checkinReply(e); scene = Scene.EVENING_REPLY; render()
            }
            a.startsWith("pick:") -> {
                partner = a.removePrefix("pick:").toInt()
                val me = engine.identity(store.birth!!).element; val pe = AEL[partner]
                store.addDex(listOf(me, pe).sorted().joinToString("")); scene = Scene.COMBO; render()
            }
        }
    }

    private fun elemOfTag(tag: String): String? = when (tag) {
        "불안해요" -> "수"; "우울해요" -> "금"; "짜증나요" -> "목"; "생각이 많아요" -> "토"; else -> null
    }

    // ───────────────────────── 시연 바 (심사 영상용: 하루를 1분에)
    private lateinit var status: TextView

    private fun demoBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(dp(20), dp(8), dp(20), dp(10))
        }
        status = TextView(this).apply {
            typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Color.argb(200, 255, 255, 255))
            setOnLongClickListener { recMode = !recMode; applyRecMode(); render(); true }
        }
        wrap.addView(status)
        val row = LinearLayout(this)
        fun b(label: String, act: () -> Unit): Button {
            val btn = Button(this).apply {
                text = label; isAllCaps = false; typeface = fReg; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.argb(90, 255, 255, 255)) }
                stateListAnimator = null; minWidth = 0; minimumWidth = 0; setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { act() }
            }
            row.addView(btn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
            return btn
        }
        // 시연 줄: 하루가 흘러가는 순서대로 (아침 → 목적지 → 출발 → 주의 구간 → 도착 → 다음 날 → 7일 뒤)
        fun road(label: String, line: String) { if (store.birth == null) return; if (!isDriving(gear)) onGear(VehicleGear.GEAR_DRIVE, "시연"); onRoad("", label, line) }
        fun gap() = row.addView(View(this), LinearLayout.LayoutParams(dp(26), 1))
        b("아침 탑승 P") { eveningOverride = false; detailOpen = false; drove = false; tripEvents.clear(); lastTrip.clear(); onGear(VehicleGear.GEAR_PARK, "시연") }
        b("목적지(두물머리)") { gear = VehicleGear.GEAR_PARK; detailOpen = false; onDestination("양평 두물머리", 37.5345, 127.3185, real = false) }
        b("출발 D") { detailOpen = false; onGear(VehicleGear.GEAR_DRIVE, "시연") }
        b("보호구역") { road("어린이 보호구역", "앞이 어린이 보호구역이에요.") }
        b("카메라") { road("단속 카메라", "앞에 단속 카메라가 있어요.") }
        b("방지턱") { road("방지턱", "앞에 방지턱이 있어요.") }
        gap()
        b("퇴근 도착 P") { eveningOverride = true; detailOpen = false; drove = true; onGear(VehicleGear.GEAR_PARK, "시연") }   // 시연: 달려서 집에 도착한 것으로
        b("다음 날 →") { store.dayOffset = store.dayOffset + 1; lastReply = null; drove = false; eveningOverride = false; detailOpen = false; scene = Scene.MORNING; render() }
        gap()
        b("7일 뒤 →") { fillDemoWeek(); store.dayOffset = store.dayOffset + 6; detailOpen = false; scene = Scene.GATE; render() }
        // 동승: 혼자(시연) → 같이(시연) → 자동(차량 좌석 신호) 순서로 바뀐다
        gap()
        passBtn = b("동승 태우기 →") {
            val before = passenger
            passengerDemo = when (passengerDemo) { false -> true; true -> null; null -> false }
            if (passenger != before) onPassengerChanged() else updateStatus()
        }
        gap()
        b("내 친구") { if (store.birth != null) { scene = Scene.MYFRIEND; render() } }
        b("복 +50") { store.bok = store.bok + 50; render() }
        b("화면 모드") { wipeAsk = false; scene = Scene.MODE; render() }
        b("처음부터") { store.reset(); resetState(); render() }
        wrap.addView(HorizontalScrollView(this).apply { addView(row) })
        return wrap
    }

    /** 저장된 것을 지운 뒤, 앱이 기억하던 상태도 처음으로 돌린다 */
    private fun resetState() {
        passengerDemo = passengerDefault(); byHand = null; byHandDay = null; partner = -1; lastReply = null; showHelp = false; drove = false; arrivedOn = null
        tripEvents.clear(); lastTrip.clear(); seenRoadIds.clear(); roadLine = null; tripBok = 0; tripNote = 0; talkTopic = null; wordBok = 0
        helloBye = false; wipeAsk = false; eveningOverride = null; destiny = null; detailOpen = false; scene = Scene.SETUP
    }

    /** 7일 건너뛰기: 빈 날만 예시 감정으로 채우고 '시연용'으로 표시 */
    private fun fillDemoWeek() {
        val sample = listOf("생각이 많아요", "괜찮아요", "불안해요", "생각이 많아요", "괜찮아요", "짜증나요", "괜찮아요")
        val start = store.today().minusDays(1)
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            store.markRide(d)
            if (store.tagOn(d) == null) { store.setTag(d, sample[i]); store.markDemo(d) }
        }
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val g = when (gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "?" }
        val off = if (store.dayOffset != 0L) " (+${store.dayOffset}일)" else ""
        val seated = seatOcc.values.count { it }
        val who = (if (passenger) "동승" else "혼자") + (if (passengerDemo != null) "(시연)" else "(좌석 신호 ${seated}자리)")
        if (::passBtn.isInitialized) passBtn.text = when (passengerDemo) { false -> "동승 태우기 →"; true -> "동승: 좌석 신호로 →"; null -> "동승 내리기 →" }
        status.text = "시연 모드 · $who · 기어 $g · ${store.today()}$off · 장면 ${scene.name}  (길게 누르면 녹화 모드)"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
