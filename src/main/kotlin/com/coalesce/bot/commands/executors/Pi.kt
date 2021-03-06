package com.coalesce.bot.commands.executors

import com.coalesce.bot.commands.CommandType
import com.coalesce.bot.commands.RootCommand
import com.coalesce.bot.commands.RootCommandContext

class Pi {
    @RootCommand(
            name = "Pi",
            type = CommandType.INFORMATION,
            permission = "commands.pi",
            description = "Shows the value of Pi.",
            globalCooldown = 5.0
    )
    fun execute(context: RootCommandContext) {
        context(context.author, "${Math.PI}\nhttp://i.imgur.com/INtrkr2.png")
    }
}