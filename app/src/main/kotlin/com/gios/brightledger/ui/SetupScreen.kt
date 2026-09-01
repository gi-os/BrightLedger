package com.gios.brightledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightledger.hw.WheelScroll
import com.gios.brightledger.ui.theme.Dim
import com.gios.brightledger.ui.theme.Faint
import com.gios.brightledger.ui.theme.RuleGrey

/**
 * The whole of setup: get a token from the Bridge, give it to the app.
 *
 * There is no account to create here and no key to obtain, because the app has no server to
 * hold one. That is worth stating plainly on the screen — a finance app asking for a pasted
 * credential looks alarming unless the user can see why there is nowhere else for it to come
 * from.
 *
 * Two ways in. Scanning is the good one: the token is a couple of hundred characters and
 * typing it on this phone is miserable, so the instructions lead with making a QR of it on
 * the machine where it was created. Pasting is kept because the clipboard survives between
 * LightOS apps and someone will want it.
 */
@Composable
fun SetupScreen(
    vm: LedgerViewModel,
    onScan: () -> Unit,
    onConnected: () -> Unit,
    /** Null on first run, where there is nothing behind this screen to go back to. */
    onBack: (() -> Unit)? = null,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            ActionBar(
                buildList {
                    if (onBack != null) add(BarAction("BACK", onBack))
                    add(BarAction("SCAN", onScan))
                    add(
                        BarAction(if (syncing) "…" else "CONNECT") {
                            if (token.isNotBlank()) {
                                vm.connect(token, name.takeIf { it.isNotBlank() }) { ok ->
                                    if (ok) onConnected()
                                }
                            }
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                if (onBack == null) "Connect a bank" else "Add a server",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            Step(1, "On a computer, go to bridge.simplefin.org and connect your bank.")
            Step(2, "It gives you a setup token — a long line of text.")
            Step(3, "Turn that token into a QR code and scan it here, or paste it below.")
            Spacer(Modifier.height(20.dp))
            Rule()
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Setup token", color = Dim) },
                placeholder = { Text("aHR0cHM6Ly9icmlkZ2Uu…", color = Faint) },
                singleLine = false,
                minLines = 3,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    // The token is Base64. Autocapitalize would corrupt it silently, and the
                    // failure would surface much later as an unhelpful "not a token".
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = RuleGrey,
                    cursorColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)", color = Dim) },
                placeholder = { Text("Revolut, via BasilNet", color = Faint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = RuleGrey,
                    cursorColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "The token is used once, to obtain a read-only access URL. That URL is " +
                    "encrypted on this phone and never sent anywhere except back to the " +
                    "server that issued it. BrightLedger has no backend to send it to.",
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    message?.let { MessageDialog(it) { vm.dismissMessage() } }
}

@Composable
private fun Step(number: Int, text: String) {
    Text(
        number.toString() + ".  " + text,
        style = MaterialTheme.typography.bodyLarge,
        color = Dim,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
