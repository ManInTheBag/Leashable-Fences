package net.maninthebag.leashableFences

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Bat
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.util.UUID

class ItemClickListener(private val plugin: JavaPlugin) : Listener {

    private inner class PendingLeash(val fence: Location, val hitch: LeashHitch, val bat: Bat, val task: BukkitTask)

    private inner class Connection(
        val id: UUID,
        val fenceA: Location,
        val fenceB: Location,
        var hitchAId: UUID,
        var hitchBId: UUID,
        var batId: UUID,
        var hitchA: LeashHitch? = null,
        var hitchB: LeashHitch? = null,
        var bat: Bat? = null
    )

    private val pending = mutableMapOf<Player, PendingLeash>()
    private val connections = mutableListOf<Connection>()
    private val unleashedLegacyBats = mutableMapOf<UUID, Int>()

    private val connectionKey = NamespacedKey(plugin, "connection_id")
    private val pendingKey = NamespacedKey(plugin, "pending")
    private val refundKey = NamespacedKey(plugin, "refund")

    fun start() {
        loadConnections()
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { reconcile() }, 20L, 100L)
    }

    fun shutdown() {
        saveConnections()
    }

    @EventHandler
    fun onPlayerClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        if (player.isSneaking) return

        val block = event.clickedBlock ?: return
        if (!block.type.name.endsWith("_FENCE")) return

        val current = pending[player]

        if (current == null && player.inventory.itemInMainHand.type != Material.LEAD) return

        event.isCancelled = true
        handleFenceClick(player, block.location)
    }

    private fun handleFenceClick(player: Player, fence: Location) {
        val current = pending[player]

        if (current == null) {
            startPending(player, fence)
            return
        }

        if (fence == current.fence) {
            cancelPending(player)
            return
        }

        if (!current.hitch.isValid) {
            cancelPending(player)
            player.sendMessage("§cThe fence you started from is gone.")
            return
        }

        if (player.inventory.itemInMainHand.type != Material.LEAD) {
            player.sendMessage("§eHold a lead to connect the fences.")
            return
        }

        completePending(player, current, fence)
    }

    @EventHandler
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val entity = event.rightClicked

        val current = pending[player]
        if (current != null && (entity.uniqueId == current.hitch.uniqueId || entity.uniqueId == current.bat.uniqueId)) {
            event.isCancelled = true
            cancelPending(player)
            return
        }

        val connection = connections.find {
            it.batId == entity.uniqueId || it.hitchAId == entity.uniqueId || it.hitchBId == entity.uniqueId
        } ?: return

        event.isCancelled = true

        if (player.inventory.itemInMainHand.type == Material.LEAD) {
            // The knot/bat hitbox blocks clicks to the fence behind it (e.g. the
            // lower fence in a stacked column); redirect to the fence the player
            // is actually aiming at.
            val target = player.rayTraceBlocks(5.0)?.hitBlock
            if (target != null && target.type.name.endsWith("_FENCE")) {
                handleFenceClick(player, target.location)
            } else {
                player.sendMessage("§eClick the fence block below the leash knot to attach another lead.")
            }
            return
        }

        removeConnection(connection, refund = player.gameMode != GameMode.CREATIVE, dropAt = entity.location)
    }

    @EventHandler
    fun onUnleash(event: EntityUnleashEvent) {
        val bat = event.entity as? Bat ?: return

        if (pending.values.any { it.bat.uniqueId == bat.uniqueId }) {
            event.isCancelled = true
            return
        }

        val connection = connections.find { it.batId == bat.uniqueId } ?: return

        if (event.reason == EntityUnleashEvent.UnleashReason.DISTANCE) {
            event.isCancelled = true
            return
        }

        // A knot was destroyed (fence broken or punched); vanilla already dropped a lead.
        removeConnection(connection, refund = false)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cancelPending(event.player)
    }

    private fun startPending(player: Player, fence: Location) {
        val center = center(fence)
        val hitch = spawnHitch(center)
        markPending(hitch)
        val bat = spawnBat(center, hitch)
        markPending(bat)

        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!bat.isValid || !hitch.isValid || !player.isOnline) {
                cancelPending(player)
                return@Runnable
            }
            bat.teleport(player.location)
        }, 0L, 1L)

        pending[player] = PendingLeash(fence, hitch, bat, task)
    }

    private fun completePending(player: Player, current: PendingLeash, target: Location) {
        val from = center(current.fence)
        val to = center(target)

        if (from.world != to.world) {
            player.sendMessage("§cYou cannot leash fences across worlds.")
            return
        }

        if (from.distance(to) > MAX_LEAD_LENGTH) {
            player.sendMessage("§cThese fences are too far apart (max $MAX_LEAD_LENGTH blocks).")
            return
        }

        current.task.cancel()
        removeSilently(current.bat)

        // The pending knot becomes the first endpoint of the connection.
        val reusedHitch = current.hitch
        reusedHitch.persistentDataContainer.remove(pendingKey)
        pending.remove(player)

        createConnection(current.fence, target, reusedHitch)

        if (player.gameMode != GameMode.CREATIVE) {
            val item = player.inventory.itemInMainHand
            if (item.amount > 1) {
                item.amount -= 1
            } else {
                player.inventory.setItemInMainHand(null)
            }
        }
    }

    private fun createConnection(fenceA: Location, fenceB: Location, reusedHitch: LeashHitch? = null): Connection {
        val id = UUID.randomUUID()

        val hitchA = reusedHitch ?: spawnHitch(center(fenceA))
        markConnection(hitchA, id)

        val hitchB = spawnHitch(center(fenceB))
        markConnection(hitchB, id)

        val bat = spawnBat(center(fenceB), hitchA)
        markConnection(bat, id)

        val connection = Connection(id, fenceA, fenceB, hitchA.uniqueId, hitchB.uniqueId, bat.uniqueId, hitchA, hitchB, bat)
        connections.add(connection)
        saveConnections()
        return connection
    }

    private fun cancelPending(player: Player) {
        val current = pending.remove(player) ?: return
        current.task.cancel()
        removeSilently(current.bat)
        removeSilently(current.hitch)
    }

    private fun removeConnection(connection: Connection, refund: Boolean, dropAt: Location? = null) {
        connections.remove(connection)
        removeSilently(connection.bat)
        removeSilently(connection.hitchA)
        removeSilently(connection.hitchB)

        val world = connection.fenceA.world
        if (world != null) {
            cleanupOrphanKnots(connection.fenceA, world)
            cleanupOrphanKnots(connection.fenceB, world)
        }
        saveConnections()

        if (refund) {
            val loc = dropAt ?: center(connection.fenceB)
            val refundWorld = loc.world ?: return
            val item = refundWorld.dropItemNaturally(loc, ItemStack(Material.LEAD, 1))
            item.persistentDataContainer.set(refundKey, PersistentDataType.BYTE, 1)
        }
    }

    // Vanilla recreates leash knots when a leashed entity is loaded, which can
    // leave duplicate unmarked knots on our fences. Remove knots that do not
    // belong to a tracked connection and have nothing leashed to them.
    private fun cleanupOrphanKnots(fence: Location, world: World) {
        val blockCenter = center(fence)
        for (knot in world.getNearbyEntities(blockCenter, 1.0, 1.0, 1.0).filterIsInstance<LeashHitch>()) {
            if (knot.persistentDataContainer.has(pendingKey, PersistentDataType.BYTE)) continue
            val mark = knot.persistentDataContainer.get(connectionKey, PersistentDataType.STRING)
            if (mark != null && connections.any { it.id.toString() == mark }) continue

            val hasForeignLeash = world
                .getNearbyEntities(blockCenter, MAX_LEAD_LENGTH + 1, MAX_LEAD_LENGTH + 1, MAX_LEAD_LENGTH + 1)
                .filterIsInstance<LivingEntity>()
                .any { isLeashedTo(it, knot) }
            if (!hasForeignLeash) removeSilently(knot)
        }
    }

    private fun isLeashedTo(entity: LivingEntity, knot: LeashHitch): Boolean {
        return try {
            entity.isLeashed && entity.leashHolder?.uniqueId == knot.uniqueId
        } catch (e: Exception) {
            false
        }
    }

    private fun removeSilently(entity: Entity?) {
        if (entity == null || !entity.isValid) return
        val loc = entity.location
        val world = loc.world ?: return
        entity.remove()
        // Vanilla drops a lead when a leash knot is destroyed; suppress it so
        // removal refunds exactly one lead.
        world.getNearbyEntities(loc, SWEEP_RADIUS, SWEEP_RADIUS, SWEEP_RADIUS)
            .filterIsInstance<Item>()
            .filter { it.itemStack.type == Material.LEAD }
            .filter { !it.persistentDataContainer.has(refundKey, PersistentDataType.BYTE) }
            .forEach { it.remove() }
    }

    private fun reconcile() {
        var changed = false

        // Remove orphaned marked entities (leftovers from removed connections or
        // interrupted placements).
        val trackedIds = connections.map { it.id }.toSet()
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClasses(LeashHitch::class.java, Bat::class.java)) {
                if (entity.persistentDataContainer.has(pendingKey, PersistentDataType.BYTE)) {
                    val active = pending.values.any {
                        it.hitch.uniqueId == entity.uniqueId || it.bat.uniqueId == entity.uniqueId
                    }
                    if (!active) removeSilently(entity)
                    continue
                }
                val id = entity.persistentDataContainer.get(connectionKey, PersistentDataType.STRING) ?: continue
                if (UUID.fromString(id) !in trackedIds) removeSilently(entity)
            }
        }

        // Adopt connections created by older versions of the plugin: an
        // invisible, invulnerable, AI-less bat leashed to a fence knot.
        for (world in Bukkit.getWorlds()) {
            for (bat in world.getEntitiesByClass(Bat::class.java)) {
                if (adoptLegacyBat(bat, world)) changed = true
            }
        }

        val iterator = connections.iterator()
        while (iterator.hasNext()) {
            val connection = iterator.next()
            val world = connection.fenceA.world
            if (world == null) {
                iterator.remove()
                changed = true
                continue
            }
            if (!isChunkLoaded(connection.fenceA) || !isChunkLoaded(connection.fenceB)) continue

            if (!isFence(connection.fenceA) || !isFence(connection.fenceB)) {
                // A fence was removed; vanilla already dropped a lead when its knot broke.
                iterator.remove()
                removeSilently(connection.hitchA)
                removeSilently(connection.hitchB)
                removeSilently(connection.bat)
                removeMarkedEntities(connection, world)
                changed = true
                continue
            }

            if (resolveConnection(connection, world)) changed = true
        }

        if (changed) saveConnections()
    }

    // Re-links or respawns missing entities so connections survive restarts
    // even if vanilla discarded some of the entities.
    private fun resolveConnection(connection: Connection, world: World): Boolean {
        var changed = false

        val hitchA = resolveHitch(connection, connection.hitchA, connection.hitchAId, connection.fenceA, world)
        if (hitchA.uniqueId != connection.hitchAId) {
            connection.hitchAId = hitchA.uniqueId
            changed = true
        }
        connection.hitchA = hitchA

        val hitchB = resolveHitch(connection, connection.hitchB, connection.hitchBId, connection.fenceB, world)
        if (hitchB.uniqueId != connection.hitchBId) {
            connection.hitchBId = hitchB.uniqueId
            changed = true
        }
        connection.hitchB = hitchB

        var bat = connection.bat?.takeIf { it.isValid }
        if (bat == null) {
            bat = world.getEntity(connection.batId) as? Bat
                ?: world.getNearbyEntities(center(connection.fenceB), 1.0, 1.0, 1.0)
                    .filterIsInstance<Bat>()
                    .firstOrNull {
                        it.persistentDataContainer.get(connectionKey, PersistentDataType.STRING) == connection.id.toString()
                    }
                ?: spawnBat(center(connection.fenceB), hitchA).also { markConnection(it, connection.id) }

            if (bat.uniqueId != connection.batId) {
                connection.batId = bat.uniqueId
                changed = true
            }
            connection.bat = bat
        }

        val holder = try {
            if (bat.isLeashed) bat.leashHolder else null
        } catch (e: Exception) {
            null
        }
        if (holder?.uniqueId != hitchA.uniqueId) {
            bat.leashHolder = hitchA
        }

        val anchor = center(connection.fenceB)
        if (bat.location.distance(anchor) > 1.0) {
            bat.teleport(anchor)
        }

        cleanupOrphanKnots(connection.fenceA, world)
        cleanupOrphanKnots(connection.fenceB, world)

        return changed
    }

    private fun resolveHitch(connection: Connection, current: LeashHitch?, id: UUID, fence: Location, world: World): LeashHitch {
        if (current != null && current.isValid) return current

        (world.getEntity(id) as? LeashHitch)?.takeIf { it.isValid }?.let { return it }

        world.getNearbyEntities(center(fence), 1.0, 1.0, 1.0)
            .filterIsInstance<LeashHitch>()
            .firstOrNull {
                it.persistentDataContainer.get(connectionKey, PersistentDataType.STRING) == connection.id.toString()
            }
            ?.let { return it }

        return spawnHitch(center(fence)).also { markConnection(it, connection.id) }
    }

    private fun adoptLegacyBat(bat: Bat, world: World): Boolean {
        if (bat.persistentDataContainer.has(connectionKey, PersistentDataType.STRING)) return false
        if (bat.persistentDataContainer.has(pendingKey, PersistentDataType.BYTE)) return false
        if (!bat.isInvisible || !bat.isInvulnerable || !bat.isSilent || bat.hasAI()) return false

        val leashed = try {
            bat.isLeashed
        } catch (e: Exception) {
            false
        }

        if (!leashed) {
            // Give vanilla time to restore the leash after a chunk load before
            // treating the bat as a leftover.
            val seen = (unleashedLegacyBats[bat.uniqueId] ?: 0) + 1
            if (seen >= 2) {
                unleashedLegacyBats.remove(bat.uniqueId)
                removeSilently(bat)
            } else {
                unleashedLegacyBats[bat.uniqueId] = seen
            }
            return false
        }
        unleashedLegacyBats.remove(bat.uniqueId)

        val holder = try {
            bat.leashHolder as? LeashHitch
        } catch (e: Exception) {
            null
        } ?: return false

        val fenceA = holder.location.block.location
        val fenceB = bat.location.block.location
        if (!isFence(fenceA) || !isFence(fenceB)) return false

        val id = UUID.randomUUID()
        markConnection(holder, id)

        val hitchB = world.getNearbyEntities(center(fenceB), 1.0, 1.0, 1.0)
            .filterIsInstance<LeashHitch>()
            .firstOrNull {
                it.uniqueId != holder.uniqueId &&
                    !it.persistentDataContainer.has(connectionKey, PersistentDataType.STRING)
            }
            ?: spawnHitch(center(fenceB))
        markConnection(hitchB, id)
        markConnection(bat, id)

        connections.add(Connection(id, fenceA, fenceB, holder.uniqueId, hitchB.uniqueId, bat.uniqueId, holder, hitchB, bat))
        return true
    }

    private fun removeMarkedEntities(connection: Connection, world: World) {
        for (fence in listOf(connection.fenceA, connection.fenceB)) {
            world.getNearbyEntities(center(fence), 1.0, 1.0, 1.0)
                .filter {
                    it.persistentDataContainer.get(connectionKey, PersistentDataType.STRING) == connection.id.toString()
                }
                .forEach { removeSilently(it) }
        }
    }

    private fun saveConnections() {
        val entries = mutableListOf<Map<String, Any>>()
        for (connection in connections) {
            val worldName = connection.fenceA.world?.name ?: continue
            entries.add(
                mapOf(
                    "id" to connection.id.toString(),
                    "world" to worldName,
                    "ax" to connection.fenceA.blockX,
                    "ay" to connection.fenceA.blockY,
                    "az" to connection.fenceA.blockZ,
                    "bx" to connection.fenceB.blockX,
                    "by" to connection.fenceB.blockY,
                    "bz" to connection.fenceB.blockZ,
                    "hitchA" to connection.hitchAId.toString(),
                    "hitchB" to connection.hitchBId.toString(),
                    "bat" to connection.batId.toString()
                )
            )
        }

        val yaml = YamlConfiguration()
        yaml.set("connections", entries)
        try {
            yaml.save(File(plugin.dataFolder, "connections.yml"))
        } catch (e: IOException) {
            plugin.logger.warning("Could not save connections: ${e.message}")
        }
    }

    private fun loadConnections() {
        val file = File(plugin.dataFolder, "connections.yml")
        if (!file.exists()) return

        val yaml = YamlConfiguration.loadConfiguration(file)
        for (entry in yaml.getMapList("connections")) {
            try {
                val world = Bukkit.getWorld(entry["world"] as String)
                if (world == null) {
                    plugin.logger.warning("Skipping leash connection in unloaded world '${entry["world"]}'")
                    continue
                }
                val fenceA = Location(
                    world,
                    (entry["ax"] as Number).toDouble(),
                    (entry["ay"] as Number).toDouble(),
                    (entry["az"] as Number).toDouble()
                )
                val fenceB = Location(
                    world,
                    (entry["bx"] as Number).toDouble(),
                    (entry["by"] as Number).toDouble(),
                    (entry["bz"] as Number).toDouble()
                )
                connections.add(
                    Connection(
                        UUID.fromString(entry["id"] as String),
                        fenceA,
                        fenceB,
                        UUID.fromString(entry["hitchA"] as String),
                        UUID.fromString(entry["hitchB"] as String),
                        UUID.fromString(entry["bat"] as String)
                    )
                )
            } catch (e: Exception) {
                plugin.logger.warning("Skipping invalid leash connection entry: ${e.message}")
            }
        }
    }

    private fun center(loc: Location): Location {
        return loc.clone().add(0.5, 0.0, 0.5)
    }

    private fun isFence(loc: Location): Boolean {
        return loc.block.type.name.endsWith("_FENCE")
    }

    private fun isChunkLoaded(loc: Location): Boolean {
        val world = loc.world ?: return false
        return world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)
    }

    private fun spawnHitch(loc: Location): LeashHitch {
        return loc.world.spawn(loc, LeashHitch::class.java)
    }

    private fun spawnBat(loc: Location, holder: LeashHitch): Bat {
        return loc.world.spawn(loc, Bat::class.java) {
            it.isInvisible = true
            it.isSilent = true
            it.isInvulnerable = true
            it.setAI(false)
            it.isPersistent = true
            it.removeWhenFarAway = false
            it.leashHolder = holder
        }
    }

    private fun markPending(entity: Entity) {
        entity.persistentDataContainer.set(pendingKey, PersistentDataType.BYTE, 1)
    }

    private fun markConnection(entity: Entity, id: UUID) {
        entity.persistentDataContainer.set(connectionKey, PersistentDataType.STRING, id.toString())
    }

    companion object {
        private const val MAX_LEAD_LENGTH = 10.0
        private const val SWEEP_RADIUS = 1.2
    }
}
