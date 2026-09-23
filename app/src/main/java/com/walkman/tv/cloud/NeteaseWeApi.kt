package com.walkman.tv.cloud

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** NetEase's double AES + RSA form envelope for account-scoped web API calls. */
internal object NeteaseWeApi {
    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val MODULUS =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152" +
        "b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557" +
        "c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047" +
        "b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private val random = SecureRandom()
    private val exponent = BigInteger("10001", 16)
    private val modulus = BigInteger(MODULUS, 16)

    fun encode(json: String): Pair<String, String> {
        val secret = buildString {
            repeat(16) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        val first = aes(json, NONCE)
        val params = aes(first, secret)
        val reversed = secret.toByteArray(Charsets.UTF_8).reversedArray()
        val encSecKey = BigInteger(1, reversed).modPow(exponent, modulus)
            .toString(16).padStart(256, '0')
        return params to encSecKey
    }

    private fun aes(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)))
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
