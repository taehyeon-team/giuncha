package ai.taehyeon.giuncha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** 오행별 빛 번짐 팔레트 (디자인 시안 B안) */
data class Palette(val base: Int, val c1: Int, val c2: Int, val c3: Int, val acc: Int, val orb: IntArray)

object Pal {
    private fun c(s: String) = Color.parseColor(s)
    val water = Palette(c("#0b1530"), c("#3d7bd9"), c("#56c2d6"), c("#2a3f8f"), c("#9fdcff"), intArrayOf(c("#bfe9ff"), c("#2a7fb0"), c("#0b2b4c")))
    val fire = Palette(c("#1c0c10"), c("#ff7a45"), c("#ffb36b"), c("#a8324a"), c("#ffc88a"), intArrayOf(c("#ffe2b0"), c("#ff8a3d"), c("#7a2a0e")))
    val wood = Palette(c("#08180f"), c("#3fae6a"), c("#b6e36b"), c("#1f5e4a"), c("#cdf2a6"), intArrayOf(c("#e6ffd0"), c("#5fa85a"), c("#163a22")))
    val metal = Palette(c("#14161c"), c("#9aa6c4"), c("#e6ebf5"), c("#5a5f86"), c("#eef2f8"), intArrayOf(c("#ffffff"), c("#b9c0cc"), c("#4a4f5a")))
    val earth = Palette(c("#1a120c"), c("#d08a4e"), c("#f2cf94"), c("#8a4e3a"), c("#ffe2b3"), intArrayOf(c("#fff0d0"), c("#c9a26b"), c("#5a3c1c")))
    val night = Palette(c("#06081a"), c("#4a3f9a"), c("#c46b8a"), c("#1d2c6b"), c("#c9c3ff"), intArrayOf(c("#e8e4ff"), c("#7a6fd0"), c("#1d2c6b")))

    fun of(elem: String?): Palette = when (elem) {
        "수" -> water; "화" -> fire; "목" -> wood; "금" -> metal; "토" -> earth; else -> metal
    }
}

/** 배경: 세 군데 빛이 번지는 그라데이션. 정차 중엔 아주 천천히 흐르고, 주행 중엔 멈춤 */
class GlowView(ctx: Context) : View(ctx) {
    var palette: Palette = Pal.water; set(v) { field = v; invalidate() }
    var intensity = 0.95f; set(v) { field = v; invalidate() }
    var animateDrift = false   // 소프트웨어 그래픽 에뮬레이터 부하 → 기본 정지, 실차 GPU에서 켤 것
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()

    private fun withA(col: Int, a: Float) = Color.argb((255 * a).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))

    private fun blob(cv: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        p.shader = RadialGradient(cx, cy, r, intArrayOf(withA(col, intensity), withA(col, intensity * .45f), withA(col, 0f)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawColor(palette.base)
        val t = if (animateDrift) (System.nanoTime() - start) / 1e9 else 0.0
        val d = w * .035f
        blob(cv, w * .75f + (d * sin(t / 7)).toFloat(), h * .30f + (d * cos(t / 9)).toFloat(), w * .55f, palette.c1)
        blob(cv, w * .92f + (d * cos(t / 8)).toFloat(), h * .85f + (d * sin(t / 6)).toFloat(), w * .45f, palette.c2)
        blob(cv, w * .15f + (d * sin(t / 10)).toFloat(), h * .95f + (d * cos(t / 7)).toFloat(), w * .55f, palette.c3)
        if (animateDrift) postInvalidateDelayed(80)
    }
}

/** 구슬: 하루의 색 (7일 화면·내일 예고) */
class OrbView(ctx: Context, private val cols: IntArray, private val glow: Boolean = false) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val r = width / 2f
        if (glow) {
            p.shader = RadialGradient(r, r, r, intArrayOf(Color.argb(110, Color.red(cols[1]), Color.green(cols[1]), Color.blue(cols[1])), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            cv.drawCircle(r, r, r, p)
        }
        val rr = if (glow) r * .55f else r
        p.shader = RadialGradient(r - rr * .24f, r - rr * .36f, rr * 1.4f, cols, floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(r, r, rr, p)
    }
}

/** 데스티니 나침반: 목적지 방향(실선) vs 오늘 기운 방향(점선) */
class CompassView(ctx: Context, private val destDir: String, private val todayDir: String, private val acc: Int, private val font: Typeface) : View(ctx) {
    private val dirs = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val c = width / 2f; val R = width * .35f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(64, 255, 255, 255)
        cv.drawCircle(c, c, R, p); p.color = Color.argb(30, 255, 255, 255); cv.drawCircle(c, c, R * .66f, p)
        p.style = Paint.Style.FILL; p.typeface = font; p.textSize = 28f; p.textAlign = Paint.Align.CENTER
        dirs.forEachIndexed { i, d ->
            val a = Math.toRadians(i * 45.0 - 90)
            p.color = if (d == destDir || d == todayDir) Color.WHITE else Color.argb(140, 255, 255, 255)
            cv.drawText(d, c + (cos(a) * R * 1.2).toFloat(), c + (sin(a) * R * 1.2).toFloat() + 10, p)
        }
        fun mark(d: String, col: Int, dashed: Boolean) {
            val a = Math.toRadians(dirs.indexOf(d) * 45.0 - 90)
            val x = c + (cos(a) * R * .9).toFloat(); val y = c + (sin(a) * R * .9).toFloat()
            p.color = col; p.strokeWidth = 3f; p.style = Paint.Style.STROKE
            p.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) else null
            cv.drawLine(c, c, x, y, p); p.pathEffect = null; p.style = Paint.Style.FILL
            cv.drawCircle(x, y, 16f, p)
        }
        mark(todayDir, Color.WHITE, true)
        mark(destDir, acc, false)
        p.color = Color.WHITE; cv.drawCircle(c, c, 9f, p)
    }
}
