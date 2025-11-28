package com.example.suicareader

import android.nfc.Tag
import android.nfc.tech.NfcF
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Arrays

data class SuicaData(
    val balance: Int,
    val history: List<SuicaTransaction>
)

data class SuicaTransaction(
    val date: String,
    val type: String,
    val amount: Int, // 交易金额 (正数或负数)
    val balanceAfter: Int // 交易后余额
)

object SuicaNfcReader {
    private const val TAG = "SuicaNfcReader"

    fun readTag(tag: Tag): SuicaData? {
        val nfcF = NfcF.get(tag) ?: return null

        try {
            nfcF.connect()
            val idm = tag.id

            // 读取最近 12 条记录 (多读一点以便计算差额，虽然有些卡只能读10条)
            val records = mutableListOf<ByteArray>()
            for (i in 0 until 12) {
                val cmd = buildReadCommand(idm, i)
                val response = nfcF.transceive(cmd)
                // 检查状态码 0x00 0x00
                if (response.size > 12 && response[10] == 0x00.toByte() && response[11] == 0x00.toByte()) {
                    val blockData = Arrays.copyOfRange(response, 13, 13 + 16)
                    records.add(blockData)
                } else {
                    break // 读不到数据了就停止
                }
            }
            nfcF.close()

            if (records.isEmpty()) return null

            // 1. 获取最新余额 (第0条记录的 Byte 10, 11)
            val latestBlock = records[0]
            val currentBalance = toInt(latestBlock[10], latestBlock[11]) // 注意顺序: 10低位, 11高位

            // 2. 解析每一条交易并计算差额
            val transactions = mutableListOf<SuicaTransaction>()

            // 我们只能计算到 records.size - 1，因为最后一条没有"前一条"来对比
            for (i in 0 until records.size - 1) {
                val currentBlock = records[i]
                val prevBlock = records[i + 1]

                val balanceCurr = toInt(currentBlock[10], currentBlock[11])
                val balancePrev = toInt(prevBlock[10], prevBlock[11])

                // 计算差额：本次余额 - 上次余额
                val diff = balanceCurr - balancePrev

                transactions.add(parseTransaction(currentBlock, diff, balanceCurr))
            }

            // (可选) 把最后一条也加进去，但因为没法计算差额，金额设为0或标记未知
            if (records.isNotEmpty()) {
                val lastBlock = records.last()
                val lastBalance = toInt(lastBlock[10], lastBlock[11])
                transactions.add(parseTransaction(lastBlock, 0, lastBalance))
            }

            return SuicaData(currentBalance, transactions)

        } catch (e: Exception) {
            Log.e(TAG, "Error reading NFC", e)
            return null
        }
    }

    private fun buildReadCommand(idm: ByteArray, blockIndex: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.write(0)
        stream.write(0x06) // Read w/o Encryption
        stream.write(idm)
        stream.write(1)
        stream.write(0x0f) // Service Code Low 0x090f -> 0f
        stream.write(0x09) // Service Code High -> 09
        stream.write(1)
        stream.write(0x80)
        stream.write(blockIndex)
        val bytes = stream.toByteArray()
        bytes[0] = bytes.size.toByte()
        return bytes
    }

    private fun parseTransaction(block: ByteArray, amount: Int, balance: Int): SuicaTransaction {
        // 解析日期 Byte 4, 5
        val dateInt = toInt(block[5], block[4])
        val month = (dateInt ushr 5) and 0x0F
        val day = dateInt and 0x1F
        val dateStr = "%02d/%02d".format(month, day)

        // 解析类型 Byte 1
        val procType = block[1].toInt()
        val typeStr = when (procType) {
            70, 73, 74, 75, 198, 203 -> "购物"
            2 -> "充值" // 简单的充值判断
            else -> "交通"
        }

        return SuicaTransaction(dateStr, typeStr, amount, balance)
    }

    private fun toInt(b1: Byte, b2: Byte): Int {
        return (b1.toInt() and 0xFF) or ((b2.toInt() and 0xFF) shl 8)
    }
}