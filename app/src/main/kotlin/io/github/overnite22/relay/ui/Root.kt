package io.github.overnite22.relay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.overnite22.relay.core.AppSession

@Composable
fun RelayRoot(app: AppSession, pendingPairCode: MutableState<String?>) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (app.phase) {
            AppSession.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            AppSession.Phase.UNPAIRED -> PairingScreen(app, pendingPairCode)
            AppSession.Phase.PAIRED -> HomeScreen(app)
        }
    }
}
