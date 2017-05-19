package com.coalesce.bot.commands.executors

import com.coalesce.bot.canDelete
import com.coalesce.bot.commands.CommandType
import com.coalesce.bot.commands.RootCommand
import com.coalesce.bot.commands.RootCommandContext
import com.coalesce.bot.gson
import com.coalesce.bot.reputation.ReputationValue
import com.coalesce.bot.reputationFile
import com.coalesce.bot.respectsLeaderboardsFile
import com.coalesce.bot.utilities.ifwithDo
import com.coalesce.bot.utilities.limit
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.reflect.TypeToken
import com.google.inject.Inject
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Member
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.TimeUnit

class Respects {
    @RootCommand(
            name = "Respects",
            aliases = arrayOf("f", "nahusdream"),
            description = "Over-engineered meme command (Press F to pay respects)",
            permission = "commands.respects",
            type = CommandType.FUN,
            globalCooldown = 6.0 * 3600.0
    )
    fun execute(context: RootCommandContext) {
        context(context.author, "Respects have been paid!") { ifwithDo(canDelete, context.message.guild) { delete().queueAfter(60, TimeUnit.SECONDS) } }

        val file = respectsLeaderboardsFile
        synchronized(file) {
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }

            val type = object: TypeToken<LinkedTreeMap<String, Any?>>() {}
            val map = gson.fromJson<LinkedTreeMap<String, Any?>>(file.readText(), type.type)

            val id = context.author.id
            map[id] = (map[id] as? Double ?: 0.0) + 1.0
            if (file.exists()) {
                file.delete()
            }
            file.createNewFile()
            file.writeText(gson.toJson(map))
        }
    }
}

class RespectsLeaderboard @Inject constructor(val jda: JDA) {
    @RootCommand(
            name = "leaderboard",
            aliases = arrayOf("fboard", "lboard", "board", "respectsboard", "rboard", "ftop"),
            description = "Displays the leaders of Respects.",
            permission = "commands.leaderboard",
            type = CommandType.FUN,
            globalCooldown = 30.0
    )
    fun execute(context: RootCommandContext) {
        val file = respectsLeaderboardsFile
        synchronized(file) {
            if (!file.exists()) {
                context("Sadly nobody has paid respects yet.")
                return
            }

            val type = object: TypeToken<LinkedTreeMap<String, Any?>>() {}
            val map = gson.fromJson<LinkedTreeMap<String, Any?>>(file.readText(), type.type)

            var respects = mutableListOf<Member>()
            map.forEach { key, value ->
                val member = context.message.guild.getMember(jda.getUserById(key))
                if (member != null &&
                        value is Double && // For safety with json, in case the host manages to edit it into something else
                        value > 0) { // invalid/punished values shouldnt be accepted.
                    respects.add(member)
                }
            }
            respects = respects.subList(0, Math.min(respects.size, 10))
            Collections.sort(respects, { second, first -> (map[first.user.id] as Double).toInt() - (map[second.user.id] as Double).toInt() })
            if (respects.size > 10) {
                val back = mutableListOf<Member>()
                back.addAll(respects.subList(0, 10))
                respects.clear()
                respects.addAll(back)
            }

            val builder = EmbedBuilder()
            val positionStr = StringBuilder()
            val nameStr = StringBuilder()
            val respectsPaidStr = StringBuilder()

            respects.forEachIndexed { index, it ->
                positionStr.append("#${index + 1}\n")
                nameStr.append("${(it.effectiveName).limit(16)}\n")
                respectsPaidStr.append("${(map[it.user.id] as Double).toInt()}\n")
            }
            val member = context.message.member
            if(respects.contains(member) && respects.indexOf(member) > 10) {
                positionStr.append("...\n${respects.indexOf(member)}")
                nameStr.append("...\n${(member.effectiveName).limit(16)}")
                respectsPaidStr.append("...\n${(map[member.user.id] as Double).toInt()}")
            }
            builder.addField("Position", positionStr.toString(), true)
                    .addField("Name", nameStr.toString(), true)
                    .addField("Respects", respectsPaidStr.toString(), true)
            context(builder)
        }
    }
}