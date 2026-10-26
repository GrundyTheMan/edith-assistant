package com.edith.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.network.AuthManager
import com.edith.app.network.NetworkClient
import com.edith.app.data.calendar.GoogleRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Context

class MainViewModel(context: Context) : ViewModel() {
    private val authManager = AuthManager(context)
    private val apiService = NetworkClient.getGoogleApi()

    private val _userAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val userAccount: StateFlow<GoogleSignInAccount?> = _userAccount

    init {
        viewModelScope.launch {
            authManager.authState.collect {
                _userAccount.value = it
            }
        }
    }

    fun login(activity: Activity) {
        authManager.signIn(activity)
    }
}
