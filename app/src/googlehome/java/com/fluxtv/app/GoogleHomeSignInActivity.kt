package com.fluxtv.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.home.PermissionsResultStatus
import kotlinx.coroutines.launch

/** Opens Google's consent screen so Flux may see and control the devices in your Google Home. */
class GoogleHomeSignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = try {
            GoogleHomeProvider.client(this)
        } catch (e: Throwable) {
            fail("Google Home isn't available on this TV: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        client.registerActivityResultCallerForPermissions(this)
        lifecycleScope.launch {
            try {
                val r = client.requestPermissions(forceLaunch = true)
                if (r.status == PermissionsResultStatus.SUCCESS) {
                    Store.put("gh_signed_in", "1")
                    Toast.makeText(this@GoogleHomeSignInActivity, "Google Home connected", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    fail("Google Home: ${r.errorMessage ?: r.status.name}")
                }
            } catch (e: Throwable) {
                fail("Google Home isn't available on this TV: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun fail(msg: String) {
        Store.put("gh_last_error", msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
