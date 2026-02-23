package org.example.kermag.tPASystem

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

@Suppress("unused")
class TPASystem : JavaPlugin(), Listener {

    private data class TpaRequest(
        val requesterId: UUID,
        val requesterName: String,
        val targetId: UUID,
        val targetName: String,
        var expiryTask: BukkitTask? = null
    )

    private val pendingByRequester = mutableMapOf<UUID, TpaRequest>()
    private val pendingByTarget = mutableMapOf<UUID, MutableMap<UUID, TpaRequest>>()
    private val messages = mutableMapOf<String, String>()
    private var autoRejectTimeSeconds = DEFAULT_AUTO_REJECT_TIME_SECONDS
    private var teleportWaitTimeSeconds = DEFAULT_TELEPORT_WAIT_TIME_SECONDS

    override fun onEnable() {
        initializeConfig()
        loadConfigSettings()
        server.pluginManager.registerEvents(this, this)
        logger.info("TPASystem enabled")
    }

    override fun onDisable() {
        clearAllRequests()
        logger.info("TPASystem disabled")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val subcommand = command.name.lowercase()
        if (subcommand !in setOf("tpa", "tpaccept", "tpdeny", "tpareload")) {
            return false
        }

        if (subcommand == "tpareload") {
            handleReload(sender)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(message("command-player-only"))
            return true
        }

        when (subcommand) {
            "tpa" -> handleTpa(sender, args)
            "tpaccept" -> handleAccept(sender, args)
            "tpdeny" -> handleDeny(sender, args)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size != 1) {
            return mutableListOf()
        }

        val input = args[0].lowercase()
        return when (command.name.lowercase()) {
            "tpa" -> Bukkit.getOnlinePlayers()
                .asSequence()
                .filter { sender !is Player || it.uniqueId != sender.uniqueId }
                .map { it.name }
                .filter { it.lowercase().startsWith(input) }
                .sorted()
                .toMutableList()

            "tpaccept", "tpdeny" -> {
                if (sender !is Player) {
                    mutableListOf()
                } else {
                    pendingByTarget[sender.uniqueId]
                        ?.values
                        ?.asSequence()
                        ?.map { it.requesterName }
                        ?.filter { it.lowercase().startsWith(input) }
                        ?.sorted()
                        ?.toMutableList()
                        ?: mutableListOf()
                }
            }

            else -> mutableListOf()
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val quitter = event.player
        val outgoing = pendingByRequester[quitter.uniqueId]
        if (outgoing != null && removeRequest(outgoing)) {
            Bukkit.getPlayer(outgoing.targetId)?.sendMessage(
                message(
                    "request-canceled-outgoing-disconnect",
                    mapOf("requester" to quitter.name)
                )
            )
        }

        val incoming = pendingByTarget[quitter.uniqueId]?.values?.toList().orEmpty()
        for (request in incoming) {
            if (removeRequest(request)) {
                Bukkit.getPlayer(request.requesterId)?.sendMessage(
                    message(
                        "request-canceled-incoming-disconnect",
                        mapOf("target" to request.targetName)
                    )
                )
            }
        }
    }

    private fun handleTpa(requester: Player, args: Array<out String>) {
        if (args.size != 1) {
            requester.sendMessage(message("usage-tpa"))
            return
        }

        val target = Bukkit.getPlayerExact(args[0])
        if (target == null || !target.isOnline) {
            requester.sendMessage(message("player-not-online", mapOf("player" to args[0])))
            return
        }

        if (requester.uniqueId == target.uniqueId) {
            requester.sendMessage(message("cannot-teleport-self"))
            return
        }

        val existing = pendingByRequester[requester.uniqueId]
        if (existing != null) {
            if (existing.targetId == target.uniqueId) {
                requester.sendMessage(message("already-requested", mapOf("target" to target.name)))
                return
            }

            if (removeRequest(existing)) {
                requester.sendMessage(
                    message(
                        "previous-request-replaced",
                        mapOf("target" to existing.targetName)
                    )
                )
                Bukkit.getPlayer(existing.targetId)?.sendMessage(
                    message(
                        "previous-request-canceled-notify",
                        mapOf("requester" to requester.name)
                    )
                )
            }
        }

        val request = TpaRequest(
            requesterId = requester.uniqueId,
            requesterName = requester.name,
            targetId = target.uniqueId,
            targetName = target.name
        )

        pendingByRequester[request.requesterId] = request
        pendingByTarget.getOrPut(request.targetId) { mutableMapOf() }[request.requesterId] = request

        request.expiryTask = server.scheduler.runTaskLater(this, Runnable {
            val current = pendingByRequester[request.requesterId]
            if (current != request) {
                return@Runnable
            }

            if (removeRequest(request)) {
                Bukkit.getPlayer(request.requesterId)?.sendMessage(
                    message(
                        "request-expired-requester",
                        mapOf("target" to request.targetName)
                    )
                )
                Bukkit.getPlayer(request.targetId)?.sendMessage(
                    message(
                        "request-expired-target",
                        mapOf("requester" to request.requesterName)
                    )
                )
            }
        }, autoRejectTimeSeconds * 20L)

        requester.sendMessage(
            message(
                "request-sent",
                mapOf(
                    "target" to target.name,
                    "seconds" to autoRejectTimeSeconds.toString()
                )
            )
        )
        target.sendMessage(message("request-received", mapOf("requester" to requester.name)))
        target.sendMessage(message("request-received-instruction", mapOf("requester" to requester.name)))
    }

    private fun handleAccept(target: Player, args: Array<out String>) {
        val request = findIncomingRequest(target, args.firstOrNull()) ?: return
        if (!removeRequest(request)) {
            target.sendMessage(message("request-no-longer-available"))
            return
        }

        val requester = Bukkit.getPlayer(request.requesterId)
        if (requester == null || !requester.isOnline) {
            target.sendMessage(message("requester-offline", mapOf("requester" to request.requesterName)))
            return
        }

        target.sendMessage(
            message(
                "request-accepted-target",
                mapOf(
                    "requester" to requester.name,
                    "seconds" to teleportWaitTimeSeconds.toString()
                )
            )
        )
        requester.sendMessage(
            message(
                "request-accepted-requester",
                mapOf(
                    "target" to target.name,
                    "seconds" to teleportWaitTimeSeconds.toString()
                )
            )
        )

        server.scheduler.runTaskLater(this, Runnable {
            val latestRequester = Bukkit.getPlayer(request.requesterId)
            val latestTarget = Bukkit.getPlayer(request.targetId)

            if (latestRequester == null || !latestRequester.isOnline || latestTarget == null || !latestTarget.isOnline) {
                latestRequester?.sendMessage(message("teleport-canceled-offline"))
                latestTarget?.sendMessage(message("teleport-canceled-offline"))
                return@Runnable
            }

            latestRequester.teleport(latestTarget.location)
            latestRequester.sendMessage(
                message(
                    "teleport-success-requester",
                    mapOf("target" to latestTarget.name)
                )
            )
            latestTarget.sendMessage(
                message(
                    "teleport-success-target",
                    mapOf("requester" to latestRequester.name)
                )
            )
        }, teleportWaitTimeSeconds * 20L)
    }

    private fun handleDeny(target: Player, args: Array<out String>) {
        val request = findIncomingRequest(target, args.firstOrNull()) ?: return
        if (!removeRequest(request)) {
            target.sendMessage(message("request-no-longer-available"))
            return
        }

        target.sendMessage(message("request-denied-target", mapOf("requester" to request.requesterName)))
        Bukkit.getPlayer(request.requesterId)?.sendMessage(
            message("request-denied-requester", mapOf("target" to target.name))
        )
    }

    private fun findIncomingRequest(target: Player, requesterNameArg: String?): TpaRequest? {
        val incoming = pendingByTarget[target.uniqueId]?.values?.toList().orEmpty()
        if (incoming.isEmpty()) {
            target.sendMessage(message("no-pending-requests"))
            return null
        }

        if (requesterNameArg == null) {
            if (incoming.size == 1) {
                return incoming.first()
            }

            val names = incoming.joinToString(", ") { it.requesterName }
            target.sendMessage(message("multiple-requests-found", mapOf("requesters" to names)))
            return null
        }

        val request = incoming.firstOrNull { it.requesterName.equals(requesterNameArg, ignoreCase = true) }
        if (request == null) {
            target.sendMessage(message("no-request-from-player", mapOf("requester" to requesterNameArg)))
            return null
        }

        return request
    }

    private fun removeRequest(request: TpaRequest): Boolean {
        val removedByRequester = pendingByRequester.remove(request.requesterId, request)
        val targetRequests = pendingByTarget[request.targetId]
        val removedByTarget = targetRequests?.remove(request.requesterId, request) == true
        if (targetRequests != null && targetRequests.isEmpty()) {
            pendingByTarget.remove(request.targetId)
        }

        if (!removedByRequester && !removedByTarget) {
            return false
        }

        request.expiryTask?.cancel()
        return true
    }

    private fun clearAllRequests() {
        pendingByRequester.values.forEach { it.expiryTask?.cancel() }
        pendingByRequester.clear()
        pendingByTarget.clear()
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("tpasystem.reload")) {
            sender.sendMessage(message("reload-no-permission"))
            return
        }

        reloadConfig()
        config.options().copyDefaults(true)
        saveConfig()
        loadConfigSettings()
        sender.sendMessage(
            message(
                "reload-success",
                mapOf(
                    "auto_reject_seconds" to autoRejectTimeSeconds.toString(),
                    "teleport_wait_seconds" to teleportWaitTimeSeconds.toString()
                )
            )
        )
    }

    private fun loadConfigSettings() {
        autoRejectTimeSeconds = readTimeSetting("auto-reject-time-seconds", DEFAULT_AUTO_REJECT_TIME_SECONDS)
        teleportWaitTimeSeconds = readTimeSetting("teleport-wait-time-seconds", DEFAULT_TELEPORT_WAIT_TIME_SECONDS)
        loadMessages()
    }

    private fun readTimeSetting(path: String, defaultValue: Long): Long {
        val value = config.getLong(path, defaultValue)
        if (value >= 1L) {
            return value
        }

        logger.warning("Invalid config value for '$path': $value. Using default: $defaultValue")
        return defaultValue
    }

    private fun initializeConfig() {
        saveDefaultConfig()
        config.options().copyDefaults(true)
        saveConfig()
    }

    private fun loadMessages() {
        messages.clear()
        for ((key, defaultValue) in DEFAULT_MESSAGES) {
            messages[key] = config.getString("messages.$key") ?: defaultValue
        }
    }

    private fun message(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val template = messages[key] ?: DEFAULT_MESSAGES[key] ?: key
        val replaced = placeholders.entries.fold(template) { current, (placeholder, value) ->
            current.replace("{$placeholder}", value)
        }
        return translateColorCodes(replaced)
    }

    private fun translateColorCodes(input: String): String {
        val validCodes = "0123456789abcdefklmnorABCDEFKLMNOR"
        val chars = input.toCharArray()
        for (index in 0 until chars.size - 1) {
            if (chars[index] == '&' && validCodes.indexOf(chars[index + 1]) >= 0) {
                chars[index] = '\u00A7'
            }
        }
        return String(chars)
    }

    companion object {
        private const val DEFAULT_AUTO_REJECT_TIME_SECONDS = 60L
        private const val DEFAULT_TELEPORT_WAIT_TIME_SECONDS = 5L
        private val DEFAULT_MESSAGES = mapOf(
            "command-player-only" to "&cOnly players can use this command.",
            "usage-tpa" to "&eUsage: /tpa <player>",
            "player-not-online" to "&cPlayer '{player}' 는 온라인이 아닙니다.",
            "cannot-teleport-self" to "&c자신에게 TPA를 할 수 없습니다.",
            "already-requested" to "&e당신은 이미 {target} 에게 TPA를 보냈습니다.",
            "previous-request-replaced" to "&e이전의 {target} TPA 요청이 교체되었습니다.",
            "previous-request-canceled-notify" to "&e{requester}'s teleport request was canceled.",
            "request-expired-requester" to "&c{target}에 대한 TPA 요청이 만료되었습니다.",
            "request-expired-target" to "&c{requester} 의 TPA 요청이 만료되었습니다.",
            "request-sent" to "&a{target}에게 TPA 요청을 완료되었습니다. {seconds} 초 후에 만료됩니다.",
            "request-received" to "&e{requester} 가 당신에게 TPA를 요청합니다.",
            "request-received-instruction" to "&eUse /tpaccept {requester} or /tpdeny {requester}.",
            "request-no-longer-available" to "&cTPA 요청이 유효하지 않습니다.",
            "requester-offline" to "&c{requester} 는 오프라인입니다.",
            "request-accepted-target" to "&a{requester}의 요청을 수락했습니다. {seconds}초 후에 이동합니다.",
            "request-accepted-requester" to "&a{target}이(가) TPA를 수학했습니다. {seconds}초 후에 이동합니다.",
            "teleport-canceled-offline" to "&c대상이 오프라인이여서 캔슬되었습니다.",
            "teleport-success-requester" to "&a{target}로 이동됨",
            "teleport-success-target" to "&a{requester}가 대상에게 이동함",
            "request-denied-target" to "&e{requester}의 TPA를 거부함",
            "request-denied-requester" to "&c{target}이 TPA를 거부함",
            "no-pending-requests" to "&e보류중인 요청이 없음",
            "multiple-requests-found" to "&e다수의 요청이 있음. {requesters}을 지정하세요.",
            "no-request-from-player" to "&c '{requester}'으로부터의 보류중인 요청이 없습니다.",
            "request-canceled-outgoing-disconnect" to "&e{requester}의 TPA요청이 캔슬되었습니다.",
            "request-canceled-incoming-disconnect" to "&e{target}으로의 TPA요청이 캔슬되었습니다.",
            "reload-no-permission" to "&c권한 부족",
            "reload-success" to "&aTPA 리로드 완료 : 자동거절={auto_reject_seconds}초, 요청유효={teleport_wait_seconds}s"
        )
    }
}
