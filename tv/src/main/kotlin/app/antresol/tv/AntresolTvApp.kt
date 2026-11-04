package app.antresol.tv

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
class AntresolTvApp : Application(), ImageLoaderFactory {
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
                    name = getString(R.string.device_name, Build.MODEL),
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
}
