package com.debritsu.app.ui.tv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.debritsu.app.AuthActivity
import com.debritsu.app.data.DEFAULT_ANILIST_CLIENT_ID
import com.debritsu.app.data.DebridProvider
import com.debritsu.app.data.Settings
import com.debritsu.app.ui.Ink

/**
 * Everything needed to make the app work, arranged as one vertical run.
 *
 * Deliberately a single column: with a remote, up and down are free and cheap,
 * while anything side by side has to be reasoned about — matching heights,
 * which neighbour a press will find. One control per row removes the question.
 *
 * Text entry is the sore point on a television and there is no way around it
 * for an addon URL. The remote apps that offer a phone keyboard (Google TV's
 * own, or atvtools) type into these fields, and that is the intended way to
 * fill them.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var clientId by remember { mutableStateOf(Settings.aniListClientId) }
    var newAddon by remember { mutableStateOf("") }
    var addons by remember { mutableStateOf(Settings.addons) }
    var debridToken by remember { mutableStateOf(Settings.debridToken) }
    var provider by remember { mutableStateOf(Settings.debridProvider) }
    var autoPlay by remember { mutableStateOf(Settings.autoPlay) }
    var maxRes by remember { mutableStateOf(Settings.maxResolution) }
    var maxSize by remember { mutableStateOf(Settings.maxSizeMb) }
    var preferEnglish by remember { mutableStateOf(Settings.preferEnglish) }
    var tokenTick by remember { mutableStateOf(0) }
    val signedIn = remember(tokenTick) { Settings.aniListToken.isNotEmpty() }

    val signIn = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        AuthActivity.tokenFrom(result.data?.data)?.let {
            Settings.aniListToken = it
            tokenTick++
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
            .verticalScroll(rememberScrollState())
            .padding(OVERSCAN),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = Ink.Bone
        )

        // --- AniList ---------------------------------------------------------
        Heading("AniList")
        Caption(
            if (signedIn) "Signed in — progress syncs after each episode."
            else "Optional. Browsing and playback work without an account, " +
                "but you lose your lists and progress tracking."
        )
        if (DEFAULT_ANILIST_CLIENT_ID.isEmpty()) {
            Field(
                value = clientId,
                label = "AniList client ID",
                onChange = { clientId = it; Settings.aniListClientId = it }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { signIn.launch(AuthActivity.intent(context, clientId)) }) {
                Text(if (signedIn) "Re-authorise" else "Sign in")
            }
            if (signedIn) {
                Button(onClick = { Settings.aniListToken = ""; tokenTick++ }) {
                    Text("Sign out")
                }
            }
        }

        // --- Addons ----------------------------------------------------------
        Heading("Stremio addons")
        Caption(
            "Paste an addon URL — a debrid-backed one such as AIOStreams, Comet " +
                "or MediaFusion. A phone keyboard app makes typing it bearable."
        )
        // Said outright because it is not discoverable and looks like a fault.
        // While the on-screen keyboard is up it owns the d-pad entirely: the app
        // receives nothing, so up and down do not move the selection and there
        // is no way for this screen to make them.
        Caption("Press Back to close the keyboard before moving on.")
        fun addAddon() {
            if (newAddon.isBlank()) return
            Settings.addAddon(newAddon)
            addons = Settings.addons
            newAddon = ""
        }

        Field(
            value = newAddon,
            label = "Addon URL",
            onChange = { newAddon = it },
            onDone = { addAddon() }
        )
        Button(onClick = { addAddon() }) { Text("Add addon") }

        addons.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    a.take(70),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Mist,
                    modifier = Modifier.width(760.dp)
                )
                Button(onClick = { Settings.removeAddon(a); addons = Settings.addons }) {
                    Text("Remove")
                }
            }
        }

        // --- Debrid ----------------------------------------------------------
        Heading("Debrid provider")
        Caption(
            "Only needed for addons that return a bare infoHash rather than a " +
                "ready link. If your addon already holds your key, leave this blank."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DebridProvider.entries.forEach { p ->
                Button(
                    onClick = { provider = p; Settings.debridProvider = p; debridToken = Settings.debridToken }
                ) {
                    Text(if (p == provider) "· ${p.label}" else p.label)
                }
            }
        }
        Field(
            value = debridToken,
            label = "${provider.label} API key",
            onChange = { debridToken = it; Settings.debridToken = it }
        )

        // --- Playback --------------------------------------------------------
        Heading("Playback")
        Caption("What automatic selection will and will not start.")
        Button(onClick = { autoPlay = !autoPlay; Settings.autoPlay = autoPlay }) {
            Text(if (autoPlay) "Play automatically: on" else "Play automatically: off")
        }
        if (autoPlay) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(720, 1080, 2160, 0).forEach { r ->
                    Button(onClick = { maxRes = r; Settings.maxResolution = r }) {
                        val label = if (r == 0) "Any" else "${r}p"
                        Text(if (r == maxRes) "· $label" else label)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(600, 1200, 2500, 0).forEach { s ->
                    Button(onClick = { maxSize = s; Settings.maxSizeMb = s }) {
                        val label = if (s == 0) "Any size" else "${s} MB"
                        Text(if (s == maxSize) "· $label" else label)
                    }
                }
            }
            Button(onClick = { preferEnglish = !preferEnglish; Settings.preferEnglish = preferEnglish }) {
                Text(if (preferEnglish) "English only: on" else "English only: off")
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("Done") }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Ink.Bone,
        modifier = Modifier.padding(top = 10.dp)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Mist)
}

/**
 * A text field sized for a ten-foot view, using the ordinary Compose one —
 * tv-material has no equivalent, and the on-screen keyboard is the system's
 * either way.
 *
 * Getting out of it is the part that needs writing. A text field consumes up
 * and down for its own cursor handling, so with a remote the selection simply
 * stops and the screen appears to freeze while quietly accepting typing. The
 * handler is onPreviewKeyEvent rather than onKeyEvent because Compose runs a
 * preview pass down the tree before the bubble pass back up, and the ordinary
 * handler is the bubble one — it never runs at all here.
 *
 * The keyboard is dismissed before the selection moves. On a television it
 * covers the whole screen, so leaving it up means focus travels away behind
 * something the viewer cannot see past, which looks identical to being stuck.
 */
@Composable
private fun Field(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    /** Runs on Enter or the keyboard's Done, for a field with an obvious action. */
    onDone: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun leave(direction: FocusDirection): Boolean {
        keyboard?.hide()
        focusManager.moveFocus(direction)
        return true
    }

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Ink.Edge,
            focusedBorderColor = Ink.Iris,
            unfocusedContainerColor = Ink.Veil,
            focusedContainerColor = Ink.Veil
        ),
        keyboardOptions = KeyboardOptions(
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboard?.hide()
                onDone?.invoke()
            },
            onNext = { leave(FocusDirection.Down) }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> leave(FocusDirection.Down)
                    Key.DirectionUp -> leave(FocusDirection.Up)
                    // A remote's centre and a keyboard's Enter both arrive here,
                    // and on a field with an action they should perform it
                    // rather than doing nothing.
                    Key.Enter, Key.NumPadEnter -> {
                        if (onDone == null) false
                        else { keyboard?.hide(); onDone(); true }
                    }
                    else -> false
                }
            }
    )
}
