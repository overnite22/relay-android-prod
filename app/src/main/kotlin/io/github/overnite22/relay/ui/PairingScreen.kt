package io.github.overnite22.relay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.R
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Pairing
import io.github.overnite22.relay.kit.RelayError
import io.github.overnite22.relay.kit.ServerAccount
import kotlinx.coroutines.launch

/** Logo de l'app (icône adaptative recomposée). */
@Composable
fun RelayMark(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.23f))) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

/** Premier lancement : appairage par QR code (Relay web → Administration → Appareils) ou saisie manuelle. */
@Composable
fun PairingScreen(app: AppSession, pendingPairCode: MutableState<String?>) {
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun pair(make: () -> ServerAccount) {
        error = null
        val account = try {
            make()
        } catch (e: Exception) {
            error = e.message
            return
        }
        working = true
        scope.launch {
            try {
                app.pair(account)
            } catch (e: Exception) {
                error = explainPairing(e)
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(pendingPairCode.value) {
        val code = pendingPairCode.value ?: return@LaunchedEffect
        pendingPairCode.value = null
        pair { Pairing.parse(code) }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        RelayMark(84.dp)
        Spacer(Modifier.height(12.dp))
        Text("Relay", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Vos connexions SSH et RDP, votre coffre et l'état de votre parc, depuis votre téléphone.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(28.dp))
        Text("Appairer ce téléphone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (scanning) {
            QrScanner(
                onCode = { code ->
                    scanning = false
                    pair { Pairing.parse(code) }
                },
                onError = {
                    scanning = false
                    error = it
                },
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(14.dp)),
            )
            TextButton(onClick = { scanning = false }) { Text("Annuler") }
        } else {
            Button(
                onClick = { error = null; scanning = true },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag("pairScan"),
            ) {
                Icon(Icons.Filled.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Scanner le QR code d'appairage")
            }
        }
        Text(
            "Dans Relay web : Administration → Appareils → Appairer. Le QR code n'est affiché qu'une fois.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth().testTag("pairManual")) {
            Icon(if (manual) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            Spacer(Modifier.width(8.dp))
            Text("Saisie manuelle")
        }
        if (manual) {
            OutlinedTextField(
                server, { server = it }, label = { Text("Serveur") }, placeholder = { Text("https://relay.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pairServer"),
            )
            OutlinedTextField(
                token, { token = it }, label = { Text("Jeton (rlt_…)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pairToken"),
            )
            Button(
                onClick = { pair { Pairing.account(server, token) } },
                enabled = server.isNotEmpty() && token.isNotEmpty() && !working,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pairConnect"),
            ) { Text("Se connecter") }
        }
        if (working) {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text("Vérification auprès du serveur…")
            }
        }
        error?.let { StatusBanner(it, modifier = Modifier.padding(top = 12.dp)) }
    }
}

fun explainPairing(e: Exception): String = when (e) {
    RelayError.Unauthorized ->
        "Le serveur refuse ce jeton. Il a peut-être été révoqué, ou le SSO bloque l'API : ajoutez une exception sur /api/ pour Relay."
    RelayError.InvalidResponse ->
        "Réponse inattendue : vérifiez l'adresse, et que le SSO laisse passer /api/ sans redirection."
    else -> e.message ?: "Erreur"
}
