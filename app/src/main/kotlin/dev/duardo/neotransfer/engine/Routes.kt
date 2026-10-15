package dev.duardo.neotransfer.engine

/**
 * Screens the app can be opened on from outside (launcher shortcut, Quick Settings tile,
 * notifications): the launch intent carries the route in [EXTRA]. The UI maps routes to screens.
 */
object Routes {
    const val EXTRA = "dev.duardo.neotransfer.route"
    const val SCANNER = "scanner"

    fun operation(id: String) = "operation/$id"
    fun movement(id: String) = "movement/$id"
}
