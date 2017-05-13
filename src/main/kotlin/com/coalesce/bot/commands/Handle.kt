package com.coalesce.bot.commands

import com.coalesce.bot.Main
import com.coalesce.bot.commandPrefix
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.reflections.Reflections
import org.reflections.scanners.ResourcesScanner
import org.reflections.scanners.SubTypesScanner
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import org.reflections.util.FilterBuilder
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Predicate

class Listener internal constructor() : ListenerAdapter(), Embeddables {
    val registry = CommandRegistry()
    val checks = mutableSetOf<Predicate<CommandContext>>()
    private val cooldowns = mutableMapOf<String, Long>() // <command identifier, until in millis>
    private val userCooldowns = mutableMapOf<Long, MutableMap<String, Long>>() // <user id, map<command identifier, until in millis>>

    init {
        synchronized(registry) {
            println("Registering commands.")
            registry.register()
            checks.add(Predicate {
                val cooldown: Double = if (it is SubCommandContext) {
                    if (it.currentSubCommand.cooldown) {
                        if (it.currentSubCommand.globalCooldown == 0.0) {
                            it.rootCommand.recursiveGlobalCooldown
                        } else {
                            it.currentSubCommand.globalCooldown
                        }
                    } else {
                        0.0
                    }
                } else {
                    it.rootCommand.globalCooldown
                }

                val identifier = if (it is SubCommandContext) "${it.rootCommand.name} ${it.currentSubCommand.name}" else it.rootCommand.name

                var setGlobal: Boolean = false
                if (cooldown != 0.0) {
                    val current = cooldowns[identifier]
                    if (current != null) {
                        if (current > System.currentTimeMillis()) {
                            // TODO: Add time left to the message.
                            it(embed().field("Receiver", it.author, true).field("Error", "The command is currently on a global cooldown.", true))
                            return@Predicate false
                        }
                    }
                    setGlobal = true
                }

                // Global cooldown passed.
                val annoUser: Double = if (it is SubCommandContext) {
                    if (it.currentSubCommand.cooldown) {
                        if (it.currentSubCommand.userCooldown == 0.0) {
                            it.rootCommand.recursiveUserCooldown
                        } else {
                            it.currentSubCommand.userCooldown
                        }
                    } else {
                        0.0
                    }
                } else {
                    it.rootCommand.userCooldown
                }

                if (annoUser != 0.0) {
                    val user = userCooldowns[it.author.idLong] ?: mutableMapOf() // All users should have one as long as it isnt empty.
                    val userCooldown = user[identifier]
                    if (userCooldown != null) {
                        if (userCooldown > System.currentTimeMillis()) {
                            // TODO: Add time left to the message.
                            it(embed().field("Receiver", it.author, true).field("Error", "The command is currently on a user cooldown.", true))
                            return@Predicate false
                        }
                    }
                    user[identifier] = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(annoUser.toLong())
                    userCooldowns[it.author.idLong] = user
                }
                if (setGlobal) {
                    cooldowns[identifier] = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cooldown.toLong())
                }

                return@Predicate true
            })
        }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.message.rawContent.startsWith(commandPrefix)) {
            return
        }
        val command = event.message.rawContent.substring(commandPrefix.length)
        try {
            val (input, method, third) = registry[command, event]
            val (context, clazz) = third
            if (method == null || context == null || clazz == null) {
                event.channel.sendMessage(MessageBuilder().append(event.author.asMention).append(": Couldn't find the command \"$input\".").build()).queue()
                return
            }

            event.message.delete().queue()
            if (checks.any { !it.test(context) }) {
                return
            }

            method.invoke(clazz, context)
        } catch (ex: Exception) {
            ex.printStackTrace()
            event.channel.sendMessage("* An error occurred. Ask a developer to look at the error.").queue()
        }
    }
}

class CommandRegistry internal constructor() {
    val commands = mutableMapOf<String, CommandEntry>()

    internal fun register() {
        val classes = Reflections(ConfigurationBuilder()
                .setScanners(SubTypesScanner(false), ResourcesScanner())
                .setUrls(ClasspathHelper.forJavaClassPath())
                .filterInputsBy(FilterBuilder().include(FilterBuilder.prefix("com.coalesce.bot.commands.executors")))).getSubTypesOf(Object::class.java).filter { !it.name.contains('$') }
        for (clazz in classes) {
            process(clazz)
        }
    }

    private fun process(clazz: Class<*>) {
        val commandEntry = CommandEntry(clazz)
        commands[commandEntry.rootAnnotation.name.replace(" ", "").toLowerCase()] = commandEntry
        if (commandEntry.rootAnnotation.aliases.isNotEmpty()) {
            commandEntry.rootAnnotation.aliases.map { it.toLowerCase().replace(" ", "") }.forEach {
                commands[it] = commandEntry
            }
        }
        commandEntry.subcommands.map { it.key }.forEach {
            commands[commandEntry.rootAnnotation.name.replace(" ", "").toLowerCase() + " $it".toLowerCase()] = commandEntry
        }
    }

    operator fun get(
            command: String,
            event: MessageReceivedEvent
    ): Triple<String, Method?, Pair<CommandContext?, Any?>> {
        val split = command.split(" ")
        val jda = event.jda
        val args: Array<String>
        if (split.size > 1) {
            val subcommand = commands[split[0] + ' ' + split[1]]
            if (subcommand != null) {
                if (split.size > 2) {
                    args = Arrays.copyOfRange(split.toTypedArray(), 2, split.size)
                } else {
                    args = arrayOf()
                }
                return Triple(split[0] + " " + split[1], subcommand.subcommands[split[1]]!!.first, SubCommandContext(jda, jda.selfUser, event.message, event, event.author, event.channel, subcommand.rootAnnotation, subcommand.subcommands, args, subcommand.subcommands[split[1]]!!.second) to subcommand.instance)
            }
        }
        val method = commands[split[0].toLowerCase()] ?: return Triple(split[0], null, null to null)
        if (split.size > 1) {
            args = Arrays.copyOfRange(split.toTypedArray(), 1, split.size)
        } else {
            args = arrayOf()
        }
        return Triple(split[0], method.rootMethod, RootCommandContext(jda, jda.selfUser, event.message, event, event.author, event.channel, method.rootAnnotation, method.subcommands, args) to method.instance)
    }
}

class CommandEntry(@Suppress("CanBeParameter") val clazz: Class<*>) {
    lateinit var rootMethod: Method
    lateinit var rootAnnotation: RootCommand
    @Suppress("DEPRECATION")
    val instance: Any = Main.instance.injector.getInstance(clazz)
    val subcommands = mutableMapOf<String, Pair<Method, SubCommand>>()

    init {
        var setRoot = false
        for (method in clazz.declaredMethods) {
            val annotation = method.getAnnotation(RootCommand::class.java) ?: continue
            rootAnnotation = annotation
            rootMethod = method
            setRoot = true
            break
        }
        if (!setRoot) {
            throw RuntimeException("Couldn't find root command in ${clazz.name}")
        }
        for (method in clazz.declaredMethods) {
            val annotation = method.getAnnotation(SubCommand::class.java) ?: continue
            subcommands[annotation.name.replace(" ", "")] = method to annotation
            annotation.aliases.map { it.replace(" ", "") }.forEach { subcommands[it] = method to annotation }
        }
    }
}