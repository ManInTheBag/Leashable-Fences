package net.maninthebag.leashableFences

import org.bukkit.plugin.java.JavaPlugin

class LeashableFences : JavaPlugin() {

    private val listener = ItemClickListener(this)

    override fun onEnable() {
        server.pluginManager.registerEvents(listener, this)
        listener.start()
    }

    override fun onDisable() {
        listener.shutdown()
    }
}
