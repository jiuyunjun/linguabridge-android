package dev.linguabridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.linguabridge.data.api.ProxySettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only proxy configuration persists. Device token is AES-GCM encrypted by an Android Keystore key. */
class ProxySettingsStore(context: Context, namespace: String = "proxy") {
    init { require(namespace.matches(Regex("[A-Za-z0-9_]+"))) }
    private val preferences = context.getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE)
    private val alias = "linguabridge.$namespace.token.v1"
    private val aad = "${context.packageName}:$namespace".toByteArray(Charsets.UTF_8)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(settings: ProxySettings) {
        settings.validate(allowDebugLoopback = true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(settings.deviceToken.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("baseUrl", settings.baseUrl)
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun load(): ProxySettings? {
        val url = preferences.getString("baseUrl", null) ?: return null
        val encrypted = preferences.getString("token", null) ?: return null
        val iv = preferences.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))); cipher.updateAAD(aad)
        return ProxySettings(url, String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            .also { it.validate(allowDebugLoopback = true) }
    }
    fun clear() {
        check(preferences.edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
}
