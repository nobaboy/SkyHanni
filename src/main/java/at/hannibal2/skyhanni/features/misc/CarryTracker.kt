package at.hannibal2.skyhanni.features.misc

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.commands.CommandRegistrationEvent
import at.hannibal2.skyhanni.data.jsonobjects.repo.CarryTrackerJson
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.LorenzChatEvent
import at.hannibal2.skyhanni.events.RepositoryReloadEvent
import at.hannibal2.skyhanni.events.entity.slayer.SlayerDeathEvent
import at.hannibal2.skyhanni.features.slayer.SlayerType
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.CollectionUtils.addString
import at.hannibal2.skyhanni.utils.HypixelCommands
import at.hannibal2.skyhanni.utils.KeyboardManager
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.NumberUtil.formatDouble
import at.hannibal2.skyhanni.utils.NumberUtil.formatDoubleOrUserError
import at.hannibal2.skyhanni.utils.NumberUtil.formatIntOrUserError
import at.hannibal2.skyhanni.utils.NumberUtil.shortFormat
import at.hannibal2.skyhanni.utils.RegexUtils.matchMatcher
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderables
import at.hannibal2.skyhanni.utils.StringUtils.cleanPlayerName
import at.hannibal2.skyhanni.utils.StringUtils.removeColor
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.time.Duration.Companion.seconds

/**
 * TODO more carry features
 * save on restart
 * support for Dungeon, Kuudra, crimson minibosses
 * average spawn time per slayer customer
 * change customer name color if offline, online, on your island
 * show time since last boss died next to slayer customer name
 * highlight slayer bosses for slayer customers
 * automatically mark customers with /shmarkplayers
 * show a line behind them
 */

@SkyHanniModule
object CarryTracker {
    private val config get() = SkyHanniMod.feature.misc

    private val customers = mutableListOf<Customer>()
    private val carryTypes = mutableMapOf<String, CarryType>()
    private var slayerNames = emptyMap<SlayerType, List<String>>()

    private var display = emptyList<Renderable>()

    private val patternGroup = RepoPattern.group("carry")

    /**
     * REGEX-TEST:
     * §6Trade completed with §r§b[MVP§r§c+§r§b] ClachersHD§r§f§r§6!
     */
    private val tradeCompletedPattern by patternGroup.pattern(
        "trade.completed",
        "§6Trade completed with (?<name>.*)§r§6!",
    )

    /**
     * REGEX-TEST:
     *  §r§a§l+ §r§6500k coins
     */
    private val rawNamePattern by patternGroup.pattern(
        "trade.coins.gained",
        " §r§a§l\\+ §r§6(?<coins>.*) coins",
    )

    @HandleEvent
    fun onSlayerDeath(event: SlayerDeathEvent) {
        val slayerType = event.slayerType
        val tier = event.tier
        val owner = event.owner
        val customer = customers.find { it.name.equals(owner, true) } ?: return
        val carry = customer.carries.find {
            it.type is SlayerCarryType && it.type.slayerType == slayerType && it.type.tier == tier
        } ?: return
        carry.done++
        if (carry.done == carry.requested) {
            ChatUtils.chat("Carry done for ${customer.name}!")
            LorenzUtils.sendTitle("§eCarry done!", 3.seconds)
        }
        update()
    }

    // TODO create trade event with player name, coins and items
    private var lastTradedPlayer: String? = null

    @SubscribeEvent
    fun onChat(event: LorenzChatEvent) {
        if (!LorenzUtils.inSkyBlock) return
        tradeCompletedPattern.matchMatcher(event.message) {
            lastTradedPlayer = group("name").cleanPlayerName()
        }

        rawNamePattern.matchMatcher(event.message) {
            val player = lastTradedPlayer ?: return
            val coinsGained = group("coins").formatDouble()
            getCustomer(player).alreadyPaid += coinsGained
            update()
        }
    }

    @SubscribeEvent
    fun onRepoReload(event: RepositoryReloadEvent) {
        val data = event.getConstant<CarryTrackerJson>("CarryTracker")
        slayerNames = data.slayerNames.mapKeys { SlayerType.valueOf(it.key) }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: GuiRenderEvent) {
        if (!LorenzUtils.inSkyBlock) return

        config.carryPosition.renderRenderables(display, posLabel = "Carry Tracker")
    }

    @HandleEvent
    fun onCommandRegister(event: CommandRegistrationEvent) {
        event.register("shcarry") {
            description = "Keep track of carries you do."
            callback { onCommand(it) }
        }
    }

    private fun onCommand(args: Array<String>) {
        if (args.size !in 2..3) {
            ChatUtils.userError(
                "Usage:\n" +
                    "§c/shcarry <customer name> <type> <amount requested>\n" +
                    "§c/shcarry <type> <price per>\n" +
                    "§c/shcarry remove <customer name>",
            )
            return
        }
        if (args.size == 3) {
            addCarry(args[0], args[1], args[2])
            return
        }
        if (args[0] == "remove") {
            val customerName = args[1]
            val customer = customers.find { it.name.equals(customerName, true) } ?: run {
                ChatUtils.userError("Customer not found: §b$customerName")
                return
            }
            customers.remove(customer)
            update()
            ChatUtils.chat("Removed customer: §b$customerName")
            return
        }
        setPrice(args[0], args[1])
    }

    private fun addCarry(customerName: String, rawType: String, amount: String) {
        val carryType = getCarryType(rawType) ?: return
        val amountRequested = amount.formatIntOrUserError() ?: return
        val newCarry = Carry(carryType, amountRequested)

        val customer = customers.find { it.name.equals(customerName, true) }
        if (customer == null) {
            if (amountRequested < 1) {
                ChatUtils.userError("Carry amount requested must be positive!")
                return
            }

            getCustomer(customerName).carries.add(newCarry)
            update()
            ChatUtils.chat("Started carry: §b$customerName §8x$amountRequested ${newCarry.type}")
            return
        }
        val carries = customer.carries
        for (carry in carries.toList()) {
            if (newCarry.type != carry.type) continue
            val newAmountRequested = carry.requested + amountRequested
            if (newAmountRequested < 1) {
                ChatUtils.userError("New carry amount requested must be positive!")
                return
            }
            carries.remove(carry)
            val updatedCarry = Carry(carryType, newAmountRequested)
            updatedCarry.done = carry.done
            carries.add(updatedCarry)
            update()
            ChatUtils.chat("Updated carry: §b$customerName §8x$newAmountRequested ${newCarry.type}")
            return
        }
    }

    private fun getCarryType(rawType: String): CarryType? = carryTypes.getOrPut(rawType) {
        createCarryType(rawType) ?: run {
            ChatUtils.userError("Unknown carry type: '$rawType'! Use e.g. rev5, sven4, eman3, blaze2..")
            return null
        }
    }

    private fun setPrice(rawType: String, rawPrice: String) {
        val carryType = getCarryType(rawType) ?: return

        val price = rawPrice.formatDoubleOrUserError() ?: return
        carryType.pricePer = price
        update()
        ChatUtils.chat("Set carry price for $carryType §eto §6${price.shortFormat()} coins.")
    }

    private fun getCustomer(customerName: String): Customer = customers.find {
        it.name.equals(customerName, ignoreCase = true)
    } ?: Customer(customerName).also {
        customers.add(it)
    }

    private fun createDisplay(
        carry: Carry,
        customer: Customer,
        carries: MutableList<Carry>,
    ): Renderable {
        val (type, requested, done) = carry
        val missing = requested - done

        val color = when {
            done > requested -> "§c"
            done == requested -> "§a"
            else -> "§e"
        }
        val cost = formatCost(type.pricePer?.let { it * requested })
        val text = "$color$done§8/$color$requested $cost"
        return Renderable.clickAndHover(
            Renderable.string("  $type $text"),
            tips = buildList<String> {
                add("§b${customer.name}' $type §cCarry")
                add("")
                add("§7Requested: §e$requested")
                add("§7Done: §e$done")
                add("§7Missing: §e$missing")
                add("")
                if (cost != "") {
                    add("§7Total cost: §e$cost")
                    add("§7Cost per carry: §e${formatCost(type.pricePer)}")
                } else {
                    add("§cNo price set for this carry!")
                    add("§7Set a price with §e/shcarry <type> <price>")
                }
                add("")
                add("§7Run §e/shcarry remove ${customer.name} §7to remove the whole customer!")
                add("§eClick to send current progress in the party chat!")
                add("§eControl-click to remove this carry!")
            },
            onClick = {
                if (KeyboardManager.isModifierKeyDown()) {
                    carries.remove(carry)
                    update()
                } else {
                    HypixelCommands.partyChat(
                        "${customer.name} ${type.toString().removeColor()} carry: $done/$requested",
                    )
                }
            },
        )
    }

    private fun update() {
        display = buildList {
            if (customers.none { it.carries.isNotEmpty() }) return@buildList
            addString("§c§lCarries")
            for (customer in customers) {
                val carries = customer.carries
                if (carries.isEmpty()) continue
                addCustomerName(customer)

                carries.forEach { add(createDisplay(it, customer, carries)) }
            }
        }
    }

    private fun MutableList<Renderable>.addCustomerName(customer: Customer) {
        val customerName = customer.name
        val totalCost = customer.carries.sumOf { it.getCost() ?: 0.0 }
        val totalCostFormat = formatCost(totalCost)
        if (totalCostFormat.isEmpty()) {
            addString("§b$customerName")
            return
        }

        val paidFormat = "§6${customer.alreadyPaid.shortFormat()}"
        val missingFormat = formatCost(totalCost - customer.alreadyPaid)
        add(
            Renderable.clickAndHover(
                Renderable.string("§b$customerName $paidFormat§8/$totalCostFormat"),
                tips = listOf(
                    "§7Carries for §b$customerName",
                    "",
                    "§7Total cost: $totalCostFormat",
                    "§7Already paid: $paidFormat",
                    "§7Still missing: $missingFormat",
                    "",
                    "§eClick to send missing coins in party chat!",
                ),
                onClick = {
                    HypixelCommands.partyChat(
                        "$customerName Carry: already paid: ${paidFormat.removeColor()}, still missing: ${missingFormat.removeColor()}",
                    )
                },
            ),
        )
    }

    private fun formatCost(totalCost: Double?): String = if (totalCost == 0.0 || totalCost == null) "" else "§6${totalCost.shortFormat()}"

    private fun createCarryType(input: String): CarryType? {
        if (input.length == 1) return null
        val rawName = input.dropLast(1).lowercase()
        val tier = input.last().digitToIntOrNull() ?: return null

        return getSlayerType(rawName)?.let {
            SlayerCarryType(it, tier)
        }
    }

    private fun getSlayerType(name: String): SlayerType? = slayerNames.entries.find { name in it.value }?.key

    data class Customer(
        val name: String,
        var alreadyPaid: Double = 0.0,
        val carries: MutableList<Carry> = mutableListOf(),
    )

    data class Carry(val type: CarryType, val requested: Int, var done: Int = 0) {
        fun getCost(): Double? = type.pricePer?.let {
            requested * it
        }?.takeIf { it != 0.0 }
    }

    abstract class CarryType(val name: String, val tier: Int) {
        var pricePer: Double? = null
        override fun toString(): String = "§d$name $tier"
    }

    class SlayerCarryType(val slayerType: SlayerType, tier: Int) : CarryType(slayerType.displayName, tier)
//     class DungeonCarryType(val floor: DungeonFloor, masterMode: Boolean) : CarryType(floor.name, tier)
}
