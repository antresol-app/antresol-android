package app.antresol.mobile

import android.app.Application
import android.os.Build
import app.antresol.core.api.ServerLanguage
import app.antresol.core.model.DeviceInfo
import app.antresol.core.session.SessionManager
import app.antresol.ui.data.DataStoreSessionStore
import app.antresol.ui.image.AntresolImageLoader
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

/** Process-wide objects: the session (API client) and Coil's image loader with the Bearer header. */
class AntresolMobileApp : Application(), ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var session: SessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(
            store = DataStoreSessionStore(this),
            deviceInfo = { id ->
                DeviceInfo(
                    id = id,
                    name = deviceName(),
                    model = Build.MODEL,
                    version = BuildConfig.VERSION_NAME,
                )
            },
            language = ::serverLanguage,
            scope = appScope,
        )
    }

    override fun newImageLoader(): ImageLoader = AntresolImageLoader.create(
        context = this,
        baseUrl = { session.client?.baseUrl },
        token = { session.client?.token },
        language = ::serverLanguage,
    )

    private fun serverLanguage(): String = ServerLanguage.from(Locale.getDefault().toLanguageTag())

    companion object {
        /** The phone's marketing-ish name, "Google Pixel 8" or "Samsung SM-S918B", shown in the server's device list. */
        fun deviceName(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
            val model = Build.MODEL.orEmpty()
            return when {
                model.isBlank() -> manufacturer.ifBlank { "Android" }
                manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }
        }
    }
}
