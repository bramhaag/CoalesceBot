package com.coalesce.bot.binary

import com.coalesce.bot.reputation.ReputationTransaction
import com.coalesce.bot.reputation.ReputationValue
import java.io.File

class ReputationSerializer(file: File): BinarySerializer<MutableMap<String, ReputationValue>>(file) {
    override fun serializeIn(): MutableMap<String, ReputationValue> {
        val map = mutableMapOf<String, ReputationValue>()

        var long: Long
        while (true) {
            long = inputStream.readLong()
            if (long == -1L) break

            val total = inputStream.readDouble()
            val transactions = mutableListOf<ReputationTransaction>()
            val milestones = mutableListOf<String>()

            for (i in 1 .. inputStream.readInt()) {
                transactions.add(ReputationTransaction(inputStream.readUTF(), inputStream.readDouble()))
            }
            for (i in 1 .. inputStream.readInt()) {
                milestones.add(inputStream.readUTF())
            }

            map[total.toString()] = ReputationValue(total, transactions, milestones)
        }

        return map
    }

    override fun serializeOut(data: MutableMap<String, ReputationValue>) {
        data.forEach { k, v ->
            outputStream.writeLong(k.toLong())
            outputStream.writeDouble(v.total)

            outputStream.writeInt(v.transactions.size)
            v.transactions.forEach {
                outputStream.writeDouble(it.amount)
                outputStream.writeUTF(it.message)
            }

            outputStream.writeInt(v.milestones.size)
            v.milestones.forEach {
                outputStream.writeUTF(it)
            }
        }
        outputStream.writeLong(-1L)
    }

}