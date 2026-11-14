package app.antresol.core.tvsetup

/**
 * The TV's own HTML pages for a phone camera without the Antresol app: the server address form,
 * its error states and the "code expired" page. Self-contained (inline CSS, no scripts, no
 * external requests), Antresol's dark colours, Russian or English by `Accept-Language`.
 */
object TvSetupPage {
    enum class Lang { En, Ru }

    /** The first language of an `Accept-Language` header that we have, else English. */
    fun language(acceptLanguage: String?): Lang {
        val tags = acceptLanguage.orEmpty().split(',')
            .map { part ->
                val tag = part.substringBefore(';').trim().lowercase()
                val q = part.substringAfter(";q=", "1").trim().toDoubleOrNull() ?: 0.0
                tag to q
            }
            .filter { it.first.isNotEmpty() && it.second > 0 }
            .sortedByDescending { it.second }
        for ((tag, _) in tags) {
            if (tag.startsWith("ru")) return Lang.Ru
            if (tag.startsWith("en")) return Lang.En
        }
        return Lang.En
    }

    private class Strings(
        val htmlLang: String,
        val title: String,
        val lead: String,
        val label: String,
        val submit: String,
        val expiredTitle: String,
        val expiredText: String,
        val errors: Map<TvSetupError, String>,
        val needsSetup: String,
    )

    private val en = Strings(
        htmlLang = "en",
        title = "Set up the TV",
        lead = "Enter the address of your Antresol server. The TV connects to it and shows a sign-in code; this phone then opens the server to approve it.",
        label = "Antresol server address",
        submit = "Continue",
        expiredTitle = "This code has expired",
        expiredText = "Open the setup on the TV again and scan the new code.",
        errors = mapOf(
            TvSetupError.BadRequest to "That doesn’t look like a server address.",
            TvSetupError.ExpiredNonce to "This code has expired. Open the setup on the TV again.",
            TvSetupError.ServerMismatch to "This address belongs to a different Antresol server.",
            TvSetupError.NotAntresol to "This address answers, but it isn’t an Antresol server.",
            TvSetupError.RateLimited to "Too many attempts. Wait a minute and try again.",
            TvSetupError.Unreachable to "The TV can’t reach this address. Check it and that the server is on the same network.",
        ),
        needsSetup = "The server isn’t set up yet. Open it in a browser to create the first account, then try again.",
    )

    private val ru = Strings(
        htmlLang = "ru",
        title = "Настройка телевизора",
        lead = "Введите адрес вашего сервера Antresol. Телевизор подключится к нему и покажет код входа, а этот телефон откроет сервер, чтобы его подтвердить.",
        label = "Адрес сервера Antresol",
        submit = "Продолжить",
        expiredTitle = "Код устарел",
        expiredText = "Код устарел — откройте настройку на телевизоре заново.",
        errors = mapOf(
            TvSetupError.BadRequest to "Это не похоже на адрес сервера.",
            TvSetupError.ExpiredNonce to "Код устарел — откройте настройку на телевизоре заново.",
            TvSetupError.ServerMismatch to "По этому адресу другой сервер Antresol.",
            TvSetupError.NotAntresol to "По этому адресу что-то отвечает, но это не сервер Antresol.",
            TvSetupError.RateLimited to "Слишком много попыток. Подождите минуту и попробуйте снова.",
            TvSetupError.Unreachable to "Телевизор не может связаться с этим адресом. Проверьте его и что сервер в той же сети.",
        ),
        needsSetup = "Сервер ещё не настроен. Откройте его в браузере, создайте первую учётную запись и попробуйте снова.",
    )

    private fun strings(lang: Lang) = if (lang == Lang.Ru) ru else en

    /** The address form. [error] (with [detail]) is shown above the field; [value] refills it. */
    fun form(lang: Lang, nonce: String, value: String = "", error: TvSetupError? = null, detail: TvSetupDetail? = null): String {
        val s = strings(lang)
        val message = when {
            detail == TvSetupDetail.ServerNeedsSetup -> s.needsSetup
            error != null -> s.errors.getValue(error)
            else -> null
        }
        return page(
            s,
            s.title,
            """
            <p class="lead">${escape(s.lead)}</p>
            <form method="post" action="/setup" autocomplete="off">
              <input type="hidden" name="n" value="${escape(nonce)}">
              <label for="server">${escape(s.label)}</label>
              <input id="server" name="server" type="text" inputmode="url" autocapitalize="off" autocorrect="off"
                spellcheck="false" placeholder="192.168.1.10:8420" value="${escape(value)}" required autofocus>
              ${if (message != null) """<p class="error" role="alert">${escape(message)}</p>""" else ""}
              <button type="submit">${escape(s.submit)}</button>
            </form>
            """.trimIndent(),
        )
    }

    /** `404`: wrong, used or expired nonce. */
    fun expired(lang: Lang): String {
        val s = strings(lang)
        return page(s, s.expiredTitle, """<p class="lead">${escape(s.expiredText)}</p>""")
    }

    /** A request the form cannot recover from (rate limit). */
    fun message(lang: Lang, error: TvSetupError): String {
        val s = strings(lang)
        return page(s, s.title, """<p class="error" role="alert">${escape(s.errors.getValue(error))}</p>""")
    }

    private fun page(s: Strings, title: String, body: String): String = """
        <!doctype html>
        <html lang="${s.htmlLang}">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="dark">
        <title>${escape(title)} · Antresol</title>
        <style>
        :root { color-scheme: dark; }
        * { box-sizing: border-box; }
        body { margin: 0; min-height: 100vh; background: radial-gradient(120% 80% at 50% 0%, #3a1c8c 0%, #0b0b10 55%, #000 100%);
          color: #f5f5f7; font: 17px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          display: flex; justify-content: center; padding: 48px 16px; }
        main { width: 100%; max-width: 420px; }
        .brand { display: flex; align-items: center; gap: 10px; font-weight: 600; font-size: 19px; margin-bottom: 28px; }
        .mark { width: 30px; height: 30px; border-radius: 8px; background: linear-gradient(160deg, #3a1c8c, #9b2fc9); }
        h1 { font-size: 28px; line-height: 1.15; margin: 0 0 10px; letter-spacing: -0.01em; }
        .lead { color: rgba(235,235,245,.7); margin: 0 0 24px; }
        label { display: block; font-size: 13px; text-transform: uppercase; letter-spacing: .06em; color: rgba(235,235,245,.6); margin: 0 0 8px 4px; }
        input[type=text] { width: 100%; font: inherit; color: #f5f5f7; background: rgba(28,28,32,.92); border: 1px solid rgba(255,255,255,.08);
          border-radius: 12px; padding: 14px 16px; outline: none; }
        input[type=text]:focus { border-color: #0a84ff; }
        .error { color: #ff453a; margin: 12px 4px 0; }
        button { width: 100%; margin-top: 20px; font: inherit; font-weight: 600; color: #000; background: #f5f5f7; border: 0;
          border-radius: 999px; padding: 14px 20px; cursor: pointer; }
        </style>
        </head>
        <body>
        <main>
        <div class="brand"><span class="mark"></span>Antresol</div>
        <h1>${escape(title)}</h1>
        $body
        </main>
        </body>
        </html>
    """.trimIndent()

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
