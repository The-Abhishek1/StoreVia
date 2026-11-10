package com.xcloak.storevia.data.imports

object ImportFileText {
    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(byteArrayOf(0x50, 0x4b)) -> XlsxCsvReader.read(bytes)
        bytes.startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte())) ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        bytes.startsWith(byteArrayOf(0xfe.toByte(), 0xff.toByte())) ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        else -> bytes.toString(Charsets.UTF_8)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
