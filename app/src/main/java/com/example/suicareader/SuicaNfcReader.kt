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
    val date: String, // 格式化后的日期 MM/dd
    val type: String, // 交易类型 (进站/出站/购物)
    val amount: Int   // 变动金额 (需要根据前后余额计算，这里简化只存余额)
)

object SuicaNfcReader {
    private const val TAG = "SuicaNfcReader"

    fun readTag(tag: Tag): SuicaData? {
        val nfcF = NfcF.get(tag) ?: return null

        try {
            nfcF.connect()

            // FeliCa Polling 并不是必须的，因为 Android 已经做过了，我们直接发 Read 命令
            // System Code: 0x0003 (Suica/Pasmo)
            // Service Code: 0x090f (History/Balance, Read Only)

            val idm = tag.id // 获取 IDm

            // 读取最近的 10 条记录
            val records = mutableListOf<ByteArray>()
            for (i in 0 until 10) {
                val cmd = buildReadCommand(idm, i)
                val response = nfcF.transceive(cmd)
                // 检查响应状态 (Byte 10, 11 是 Status Flag 1, 2。 0x00, 0x00 表示成功)
                if (response.size > 12 && response[10] == 0x00.toByte() && response[11] == 0x00.toByte()) {
                    // Block 数据从第 13 字节开始 (Length byte + Response Code + IDm + Status Flags + Block Count)
                    val blockData = Arrays.copyOfRange(response, 13, 13 + 16)
                    records.add(blockData)
                }
            }

            nfcF.close()

            if (records.isEmpty()) return null

            // 解析余额 (最新的记录是第 0 条)
            // 余额在 Block 的 Byte 11 (LSB) 和 Byte 10 (MSB) -> Little Endian
            val latestBlock = records[0]
            val balance = toInt(latestBlock[10], latestBlock[11])

            // 解析历史记录 (简化版)
            val transactions = records.map { block ->
                parseTransaction(block)
            }

            return SuicaData(balance, transactions)

        } catch (e: Exception) {
            Log.e(TAG, "Error reading NFC", e)
            return null
        }
    }

    // 构建 FeliCa Read Without Encryption 命令
    private fun buildReadCommand(idm: ByteArray, blockIndex: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.write(0) // 占位符，最后填长度
        stream.write(0x06) // Command Code: Read Without Encryption
        stream.write(idm)  // IDm (8 bytes)
        stream.write(1)    // Number of Services
        stream.write(0x0f) // Service Code List (Low) 0x090f -> Little Endian 0f 09
        stream.write(0x09) // Service Code List (High)
        stream.write(1)    // Number of Blocks
        stream.write(0x80) // Block List Element (2 bytes access mode) - Element 1
        stream.write(blockIndex) // Block Number

        val bytes = stream.toByteArray()
        bytes[0] = bytes.size.toByte() // 填入长度
        return bytes
    }

    private fun parseTransaction(block: ByteArray): SuicaTransaction {
        // Suica 原始数据解析非常复杂，涉及控制台类型、处理类型等
        // 这里做一个简化的解析演示

        // 日期: Byte 4 (High 7 bits: Year, Low 1 bit: Month high), Byte 5 (Month low, Day)
        val dateInt = toInt(block[5], block[4])
        val year = (dateInt ushr 9) // 年份是相对 2000 年的偏移
        val month = (dateInt ushr 5) and 0x0F
        val day = dateInt and 0x1F

        val dateStr = "%02d/%02d".format(month, day)

        // 简单判断类型 (仅作演示，完整表很大)
        val procType = block[1].toInt()
        val typeStr = when(procType) {
            70, 73, 74, 75, 198, 203 -> "购物" // 简化
            else -> "交通"
        }

        return SuicaTransaction(dateStr, typeStr, 0)
    }

    private fun toInt(b1: Byte, b2: Byte): Int {
        return (b1.toInt() and 0xFF) or ((b2.toInt() and 0xFF) shl 8)
    }
}