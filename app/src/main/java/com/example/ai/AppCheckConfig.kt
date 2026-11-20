package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private const val APPCHECK_META_PREFS = "cinestream_appcheck_meta"
private const val KEY_HAS_REGISTERED_DEBUG_TOKEN = "has_registered_debug_token"

enum class AppCheckProviderMode {
    UNINITIALIZED,
    DEBUG_REGISTERED_TOKEN,
    RECAPTCHA_ENTERPRISE,
    DEBUG_FALLBACK
}

object AppCheckRuntimeState {
    @Volatile
    var activeMode: AppCheckProviderMode = AppCheckProviderMode.UNINITIALIZED
        internal set
}

/**
 * Configures Firebase App Check before any UI, ViewModel, or Firebase AI call.
 *
 * - In AI Studio Streaming Emulator / CI (where launch Intent supplies FIREBASE_APPCHECK_DEBUG_TOKEN):
 *   persists the registered debug secret synchronously via .commit() and installs DebugAppCheckProviderFactory.
 * - On real Android phones / Android TVs running a sideloaded APK (where no debug token Intent extra exists):
 *   clears any stale unregistered local debug UUID and installs RecaptchaAppCheckProviderFactory using the
 *   provisioned `recaptcha_site_key` resource so Firebase App Check attestation succeeds instead of failing
 *   with "Firebase App Check token is invalid".
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        val app = FirebaseApp.getInstance()
        val persistenceKey = app.persistenceKey
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        val metaPrefs = context.getSharedPreferences(APPCHECK_META_PREFS, Context.MODE_PRIVATE)
        val incomingDebugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()

        if (BuildConfig.DEBUG && !incomingDebugToken.isNullOrEmpty()) {
            // 1. Registered debug token supplied by AI Studio / emulator harness
            // CRITICAL: MUST use commit() instead of apply() so the secret is written
            // synchronously before DebugAppCheckProviderFactory reads it.
            debugPrefs.edit()
                .putString(DEBUG_SECRET_KEY, incomingDebugToken)
                .commit()
            metaPrefs.edit()
                .putBoolean(KEY_HAS_REGISTERED_DEBUG_TOKEN, true)
                .commit()

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            AppCheckRuntimeState.activeMode = AppCheckProviderMode.DEBUG_REGISTERED_TOKEN
            Log.d(TAG, "App Check configured with registered debug token.")
            return
        }

        if (BuildConfig.DEBUG &&
            metaPrefs.getBoolean(KEY_HAS_REGISTERED_DEBUG_TOKEN, false) &&
            !debugPrefs.getString(DEBUG_SECRET_KEY, null).isNullOrBlank()
        ) {
            // 2. Subsequent launch inside emulator where registered debug token was already saved
            if (AppCheckRuntimeState.activeMode != AppCheckProviderMode.DEBUG_REGISTERED_TOKEN) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                AppCheckRuntimeState.activeMode = AppCheckProviderMode.DEBUG_REGISTERED_TOKEN
                Log.d(TAG, "App Check resumed with persisted registered debug token.")
            }
            return
        }

        // 3. Release builds OR sideloaded debug APKs on real Android Phone / Android TV:
        // Use provisioned reCAPTCHA Enterprise App Check provider (never an unregistered random debug UUID)
        val siteKey = resolveRecaptchaSiteKey(context)
        if (!siteKey.isNullOrBlank()) {
            // Clear any unregistered random UUID left over from an earlier debug-provider run
            if (debugPrefs.contains(DEBUG_SECRET_KEY)) {
                debugPrefs.edit().remove(DEBUG_SECRET_KEY).commit()
            }
            if (AppCheckRuntimeState.activeMode != AppCheckProviderMode.RECAPTCHA_ENTERPRISE) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                AppCheckRuntimeState.activeMode = AppCheckProviderMode.RECAPTCHA_ENTERPRISE
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed for package ${context.packageName}.")
            }
        } else if (BuildConfig.DEBUG) {
            if (AppCheckRuntimeState.activeMode != AppCheckProviderMode.DEBUG_FALLBACK) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                AppCheckRuntimeState.activeMode = AppCheckProviderMode.DEBUG_FALLBACK
                Log.w(TAG, "No recaptcha_site_key or debug token found; fell back to DebugAppCheckProviderFactory.")
            }
        } else {
            Log.e(TAG, "recaptcha_site_key string resource not found or blank in package.")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

internal fun resolveRecaptchaSiteKey(context: Context): String? {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
        "string",
        context.packageName
    )
    if (resId == 0) return null
    return context.getString(resId).trim().takeIf { it.isNotEmpty() }
}
