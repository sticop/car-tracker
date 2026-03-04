package com.cartracker.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Fixes SSL certificate trust on Android 7.0 (API 24) and 7.1 (API 25).
 *
 * These older Android versions don't trust the ISRG Root X1 certificate used by
 * Let's Encrypt, which causes SSLHandshakeException for Carto CDN tile servers,
 * Nominatim geocoding, and other modern HTTPS endpoints.
 *
 * The fix bundles the ISRG Root X1 root certificate and merges it with the
 * system trust store, then sets it as the default SSLSocketFactory so all
 * HttpsURLConnection instances (including osmdroid tile downloads) use it.
 */
object SslCompat {

    private const val TAG = "SslCompat"

    /**
     * Call once in Application.onCreate() — patches the default SSLSocketFactory
     * on Android <= 25 to trust ISRG Root X1 (Let's Encrypt).
     * On Android 26+ this is a no-op since the system already trusts it.
     */
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            Log.d(TAG, "Android ${Build.VERSION.SDK_INT} — SSL patch not needed")
            return
        }

        try {
            // Load the bundled ISRG Root X1 certificate
            val cf = CertificateFactory.getInstance("X.509")
            val cert = context.resources.openRawResource(
                context.resources.getIdentifier("isrg_root_x1", "raw", context.packageName)
            ).use { cf.generateCertificate(it) }

            // Create a KeyStore containing the system CAs + our extra cert
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", cert)

                // Also add all system-trusted CAs
                val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                systemTmf.init(null as KeyStore?)
                val systemTm = systemTmf.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .firstOrNull()
                systemTm?.acceptedIssuers?.forEachIndexed { i, ca ->
                    setCertificateEntry("system_ca_$i", ca)
                }
            }

            // Create a TrustManager that trusts the merged store
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)

            // Install as default SSLContext
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.trustManagers, null)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)

            Log.i(TAG, "SSL patched for Android ${Build.VERSION.SDK_INT} — ISRG Root X1 trusted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install SSL compat: ${e.message}", e)
        }
    }
}
