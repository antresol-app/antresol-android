package app.antresol.tv.ui.channels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import java.time.Instant

/** Made-up channels with a programme on air and one next, and made-up stills for them (tests of the TV channel list). */

class Show(val title: String, val subtitle: String, val genres: List<String>, val year: Int, val rating: String, val overview: String)

val shows = listOf(
    Show("Billions", "S3 E1 · Tie Goes to the Runner", listOf("drama"), 2018, "16+", "A federal prosecutor and a hedge fund billionaire clash as a new season opens with a fresh investigation and old grudges."),
    Show("Harbor Lights", "S2 E4 · Low Tide", listOf("comedy", "drama"), 2016, "12+", "The crew of a small harbour café spends the morning of the town regatta trying to keep the secret that could sink the day."),
    Show("Planet Earth II", "S1 E3 · Grasslands", listOf("documentary"), 2016, "6+", "Across the open plains the herds move on, and every predator watches the horizon for the first sign of a straggler."),
    Show("Orbital", "S1 E7 · Dead Reckoning", listOf("sci-fi", "thriller"), 2021, "16+", "With the station's navigation lost, the crew has six hours to fix their position before the next debris field."),
    Show("Night Desk", "S4 E2 · Last Edition", listOf("crime"), 2019, "16+", "A reporter on the night desk follows a tip that connects three unrelated cases in the same city block."),
    Show("Iron Courier", "S1 E5 · The Long Way", listOf("action"), 2022, "16+", "A courier with one rule, never open the package, is offered a job whose delivery address changes every hour."),
    Show("Paper Moons", "S3 E9 · Folded", listOf("animation", "family"), 2020, "6+", "The paper-folding twins make their biggest model yet, a lantern that has to survive the whole festival night."),
    Show("Cold Case Files", "S2 E1 · Thaw", listOf("mystery"), 2017, "16+", "An old tape resurfaces in a lake house sale, and a retired detective recognises the voice on it."),
    Show("Wild Routes", "S5 E6 · Coast to Coast", listOf("adventure", "documentary"), 2018, "12+", "Two guides cross the northern coastline on foot, counting on the tides to be kind for exactly four days."),
    Show("The Long Table", "S2 E3 · Seconds", listOf("comedy"), 2015, "12+", "A family dinner stretches into the night as every guest discovers that they brought the same surprise."),
    Show("Silver Screen", "Casablanca", listOf("classics", "drama"), 1942, "12+", "In wartime Morocco a café owner is drawn back into a romance he thought he had left behind."),
    Show("Little Explorers", "S1 E12 · The Cave", listOf("family", "kids"), 2023, "0+", "The explorers' club follows a trail of glowing stones into a cave that turns out to be a map."),
)

val channelNames = listOf("Drama", "Comedy", "Documentary", "Sci-Fi", "Thriller", "Action", "Crime", "Animation", "Mystery", "Adventure", "Classics", "Family")

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

private fun program(channel: Int, show: Show, startMs: Long, endMs: Long, id: String) = ChannelProgram(
    id = id,
    itemId = channel.toLong(),
    kind = "episode",
    title = show.title,
    subtitle = show.subtitle,
    overview = show.overview,
    year = show.year,
    rating = show.rating,
    genres = show.genres,
    start = iso(startMs),
    end = iso(endMs),
    backdrop = "/api/images/still-$channel",
)

/** [count] made-up channels (channel 2 a favourite), each part way through its programme with another one next. */
fun fakeChannels(count: Int, nowMs: Long): List<Channel> = (1..count).map { n ->
    val show = shows[(n - 1) % shows.size]
    val after = shows[n % shows.size]
    val ran = (12 + (n * 7) % 30) * 60_000L
    val left = (25 + (n * 11) % 35) * 60_000L
    Channel(
        id = n.toLong(),
        number = n,
        name = channelNames[(n - 1) % channelNames.size],
        favorite = n == 2,
        now = program(n, show, nowMs - ran, nowMs + left, "p$n"),
        next = program(n + 100, after, nowMs + left, nowMs + left + 45 * 60_000L, "q$n"),
    )
}

private val palettes = listOf(
    intArrayOf(0xFF0E1B33.toInt(), 0xFF2F4F7F.toInt(), 0xFFF2A65A.toInt()),
    intArrayOf(0xFF1D2B1F.toInt(), 0xFF3F6B4A.toInt(), 0xFFE8D78A.toInt()),
    intArrayOf(0xFF2B1236.toInt(), 0xFF6B2F6F.toInt(), 0xFFFF7E67.toInt()),
    intArrayOf(0xFF0B2A2E.toInt(), 0xFF1F6E73.toInt(), 0xFF9BE7D8.toInt()),
    intArrayOf(0xFF3A1414.toInt(), 0xFF8A3B2E.toInt(), 0xFFFFC27A.toInt()),
    intArrayOf(0xFF161B2E.toInt(), 0xFF4B4F9E.toInt(), 0xFFB8C0FF.toInt()),
)

private val stills = HashMap<Int, Bitmap>()

/** A made-up cinematic frame: a sky gradient, a glow and two dark ridges. */
fun fakeStill(n: Int): Bitmap = stills.getOrPut(n) {
    val w = 1280
    val h = 720
    val colors = palettes[n % palettes.size]
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    val cx = w * (0.25f + 0.5f * ((n * 37) % 100) / 100f)
    paint.shader = RadialGradient(cx, h * 0.42f, h * 0.55f, intArrayOf((colors[2] and 0x00FFFFFF) or 0xC0000000.toInt(), colors[2] and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    for ((i, shade) in listOf(0x55000000, 0xAA000000.toInt()).withIndex()) {
        val ridge = Path()
        val base = h * (0.62f + 0.14f * i)
        ridge.moveTo(0f, h.toFloat())
        ridge.lineTo(0f, base)
        var x = 0f
        while (x < w) {
            x += 90f
            ridge.lineTo(x, base - 50f * (((n + i) * 31 + (x / 90).toInt() * 17) % 10) / 10f)
        }
        ridge.lineTo(w.toFloat(), h.toFloat())
        ridge.close()
        paint.color = shade
        canvas.drawPath(ridge, paint)
    }
    bitmap
}
