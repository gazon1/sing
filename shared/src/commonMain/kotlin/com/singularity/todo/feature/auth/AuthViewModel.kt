package com.singularity.todo.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AuthUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AuthUiEvent> = _events.asSharedFlow()

    val session: StateFlow<Session> = authRepository.session

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = authRepository.signIn(email, password)
            result.fold(
                onSuccess = {
                    _state.value = AuthUiState.Success
                    _events.emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    _state.value = AuthUiState.Error(it.message ?: "Sign in failed")
                }
            )
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = authRepository.signUp(email, password)
            result.fold(
                onSuccess = {
                    _state.value = AuthUiState.Success
                    _events.emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    _state.value = AuthUiState.Error(it.message ?: "Sign up failed")
                }
            )
        }
    }

    fun signInAnonymously() {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = authRepository.signInAnonymously()
            result.fold(
                onSuccess = {
                    _state.value = AuthUiState.Success
                    _events.emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    _state.value = AuthUiState.Error(it.message ?: "Failed")
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
        }
    }

    fun resetState() {
        _state.value = AuthUiState.Idle
    }
}
