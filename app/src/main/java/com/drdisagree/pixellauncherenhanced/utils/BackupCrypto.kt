package com.drdisagree.pixellauncherenhanced.utils

import com.drdisagree.pixellauncherenhanced.R
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {

    val SETTINGS_MAGIC = "PLESET1".toByteArray()
    val HOME_LAYOUT_MAGIC = "PLELAY1".toByteArray()
    val DRAWER_LAYOUT_MAGIC = "PLEDRW1".toByteArray()

    fun wrongTypeMessage(data: ByteArray, expected: ByteArray): Int? {
        return when {
            expected !== SETTINGS_MAGIC && hasMagic(SETTINGS_MAGIC, data) ->
                R.string.backup_wrong_type_settings

            expected !== HOME_LAYOUT_MAGIC && hasMagic(HOME_LAYOUT_MAGIC, data) ->
                R.string.backup_wrong_type_home_layout

            expected !== DRAWER_LAYOUT_MAGIC && hasMagic(DRAWER_LAYOUT_MAGIC, data) ->
                R.string.backup_wrong_type_drawer_layout

            else -> null
        }
    }

    fun encrypt(magic: ByteArray, plain: ByteArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(salt), GCMParameterSpec(TAG_BITS, iv))
        }

        return magic + salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(magic: ByteArray, data: ByteArray): ByteArray? {
        val headerSize = magic.size + SALT_SIZE + IV_SIZE
        if (data.size <= headerSize || !hasMagic(magic, data)) return null

        return runCatching {
            val salt = data.copyOfRange(magic.size, magic.size + SALT_SIZE)
            val iv = data.copyOfRange(magic.size + SALT_SIZE, headerSize)
            Cipher.getInstance(CIPHER).run {
                init(Cipher.DECRYPT_MODE, deriveKey(salt), GCMParameterSpec(TAG_BITS, iv))
                doFinal(data, headerSize, data.size - headerSize)
            }
        }.getOrNull()
    }

    fun hasMagic(magic: ByteArray, data: ByteArray): Boolean {
        return data.size >= magic.size && data.copyOfRange(0, magic.size).contentEquals(magic)
    }

    private fun deriveKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(PASSPHRASE.toCharArray(), salt, KEY_ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private const val PASSPHRASE = "com.drdisagree.pixellauncherenhanced.backup"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val KEY_ITERATIONS = 10000
}
