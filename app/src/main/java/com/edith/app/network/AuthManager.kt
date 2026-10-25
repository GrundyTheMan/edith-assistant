package com.edith.app.network

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModel

class AuthManager(private val context: Context) {
    private val signInClient = GoogleSignIn.getClient(context, 
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestCalendarApi()
            .requestGmailApi()
            .build())

    private val _authState = MutableStateFlow<GoogleSignInAccount?>(null)
    val authState: StateFlow<GoogleSignInAccount?> = _authState

    fun signIn(activity: android.app.Activity) {
        val signInIntent = signInClient.signInIntent
        activity.startActivityForResult(signInIntent, 1001)
    }

    fun handleActivityResult(requestCode: Int, result: Intent?) {
        if (requestCode == 1001) {
            val task = signInClient.onActivityResult(requestCode, result)
            task.addOnCompleteListener {
                try {
                    val account = task.getResult(GoogleSignInStatusCodes.SUCCESS)
                    _authState.value = account
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    }

    fun setAuth(account: GoogleSignInAccount) {
        _authState.value = account
    }
}
