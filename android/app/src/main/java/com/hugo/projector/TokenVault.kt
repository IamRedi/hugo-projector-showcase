package com.hugo.projector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are encrypted at rest using a key held by Android Keystore. */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("hugo_v2_tokens", Context.MODE_PRIVATE)
    private val alias = "hugo_projector_v2_tokens"
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    @Synchronized fun clients(): JSONArray {
        val encoded = prefs.getString("blob", null) ?: return JSONArray()
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { JSONArray() }
    }

    @Synchronized fun add(id: String, name: String, token: String) {
        val all = clients()
        all.put(JSONObject().put("id", id).put("name", name).put("token", token))
        write(all)
    }

    @Synchronized fun valid(token: String): Boolean {
        if (token.length != 64) return false
        val all = clients()
        for (i in 0 until all.length()) {
            val stored = all.getJSONObject(i).optString("token")
            if (java.security.MessageDigest.isEqual(stored.toByteArray(), token.toByteArray())) return true
        }
        return false
    }

    @Synchronized fun revoke(id: String): Boolean {
        val all = clients()
        val next = JSONArray()
        var removed = false
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            if (item.optString("id") == id) removed = true else next.put(item)
        }
        if (removed) write(next)
        return removed
    }

    @Synchronized fun tokenFor(id: String): String? {
        val all = clients()
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            if (item.optString("id") == id) return item.optString("token")
        }
        return null
    }

    private fun write(array: JSONArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("blob", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }
}
