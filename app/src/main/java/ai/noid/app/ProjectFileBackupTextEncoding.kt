package ai.noid.app

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Strict UTF-8 decoding for backups: replacement characters would silently corrupt restored bytes. */
internal object ProjectFileBackupTextEncoding {
    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw IOException("النسخة الاحتياطية ليست نصاً صالحاً بترميز UTF-8.", failure)
    }
}
