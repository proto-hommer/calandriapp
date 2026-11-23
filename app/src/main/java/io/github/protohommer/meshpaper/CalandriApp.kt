package io.github.protohommer.meshpaper

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * La app. Es dueña del [MeshViewModel] (la conexión con el nodo, los mensajes, los nodos) para
 * que viva mientras viva el proceso y no solo mientras está abierta la pantalla: así, con el
 * [MeshService] en primer plano, la conexión sigue con la pantalla apagada o la app cerrada
 * desde recientes.
 */
class CalandriApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val mesh: MeshViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MeshViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
