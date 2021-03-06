package com.coalesce.bot.commands.executors

import com.coalesce.bot.Main
import com.coalesce.bot.commands.*
import com.coalesce.bot.reputation.ReputationManager
import com.coalesce.bot.reputation.ReputationTransaction
import com.google.inject.Inject
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageChannel
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.message.react.MessageReactionAddEvent
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class Reputation @Inject constructor(val bot: Main, val reputation: ReputationManager) {
    @RootCommand(
            name = "reputation",
            type = CommandType.INFORMATION,
            permission = "commands.reputation",
            aliases = arrayOf("rep", "reput"),
            description = "View your reputation.",
            userCooldown = 10.0,
            globalCooldown = 0.0
    )
    fun execute(context: RootCommandContext) {
        val rep = reputation[context.message.author]

        val transactionsString = StringBuilder()
        if (rep.transactions.isEmpty()) transactionsString.append("None.")
        else rep.transactions.forEach {
            transactionsString.append("**${if (it.amount >= 0) "+" else ""}${it.amount}**: ${it.message}\n")
        }

        context.send(EmbedBuilder()
                .setTitle("You have ${rep.total.toInt()} reputation.", null)
                .addField("Recent", transactionsString.toString(), false))
    }

    @SubCommand(
            name = "thank",
            permission = "commands.reputation.thanks",
            aliases = arrayOf("thanks", "thankyou", "softdonate"),
            globalCooldown = 0.0,
            userCooldown = 360.0
    )
    fun thank(context: SubCommandContext) {
        if (context.message.mentionedUsers.isEmpty()) {
            context.send("* You need to mention someone you wish to thank.")
            return
        }

        doThank(context.message.guild, context.message.channel, context.message.author, context.message.mentionedUsers.first(), context.jda)
    }

    @SubCommand(
            name = "unrate",
            permission = "commands.reputation.unrate",
            aliases = arrayOf("dislike", "downrate", "unrate"),
            globalCooldown = 0.0,
            userCooldown = 720.0
    )
    fun unrate(context: SubCommandContext) {
        if (context.message.mentionedUsers.isEmpty()) {
            context.send("* You need to mention someone you wish to unrate.")
            return
        }

        doUnrate(context.message.guild, context.message.channel, context.message.author, context.message.mentionedUsers.first(), context.jda)
    }

    @JDAListener
    fun react(event: MessageReactionAddEvent, context: EventContext) {
        if (event.reaction.emote.name == "✌") {
            if (context.runChecks(event.user, event.channel!!, 360.0)) {
                event.channel.getMessageById(event.messageId).queue {
                    doThank(event.guild, event.channel!!, event.user, it.author, event.jda)
                }
            }
        } else if (event.reaction.emote.name == "👎") {
            if (context.runChecks(event.user, event.channel!!, 720.0)) {
                event.channel.getMessageById(event.messageId).queue {
                    doUnrate(event.guild, event.channel!!, event.user, it.author, event.jda)
                }
            }
        }
    }

    fun doThank(guild: Guild, channel: MessageChannel, from: User, to: User, jda: JDA) {
        if (from == to) {
            channel.sendMessage("* You can't thank yourself.").queue()
            return
        }
        if (to == jda.selfUser) {
            channel.sendMessage("* You are welcome.").queue()
            return
        }

        val originValue = reputation[from]
        val targetValue = reputation[to]

        val transactionAmount = Math.min((originValue.total.toDouble() / 80.0) + 20.0, 100.0)
        targetValue.transaction(ReputationTransaction("${guild.getMember(from).effectiveName} thanked you", transactionAmount),
                channel, guild.getMember(to))
        reputation[to] = targetValue
    }

    fun doUnrate(guild: Guild, channel: MessageChannel, from: User, to: User, jda: JDA) {
        if (from == to) {
            channel.sendMessage("* You can't unrate yourself.").queue()
            return
        }
        if (to == jda.selfUser) {
            channel.sendMessage("* You are welcome.").queue()
            return
        }

        val originValue = reputation[from]
        val targetValue = reputation[to]

        if (originValue.total < 100) {
            channel.sendMessage("* You need 100 reputation to unrate.").queue()
            return
        }

        targetValue.transaction(ReputationTransaction("${guild.getMember(from).effectiveName} down-rated you", -20.0),
                channel, guild.getMember(to))
        reputation[to] = targetValue
    }
}