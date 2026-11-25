package com.music.bitchord.ui.onboarding

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.playlist.NeveraPlaylistFile
import com.music.bitchord.data.playlist.PlaylistUrlImporter
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_google_g
import com.music.bitchord.sharedui.resources.ic_logo
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MESH_CYCLE_MILLIS
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.player.WelcomeMeshPalettes
import com.music.bitchord.ui.player.cyclingMeshPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The welcome: four swipes up, once.
 *
 * 1. A name — a title (Miss / Mr.) and what to call the listener, kept in
 *    settings as the greeting name and handed to Listen Together as the
 *    party name, as the old two-page welcome did.
 * 2. The ground rules — a short, plain terms page. The swipe up is locked
 *    until the agreement box is ticked; there is no agreeing by accident.
 * 3. Connect & import — sign in with Google, or don't (the app works
 *    signed out), and optionally bring a playlist: a Spotify or YouTube
 *    link pasted in, or a `.nevera` file. A successful import asks, in so
 *    many words, whether to download the whole playlist.
 * 4. The way in — a bridge page; landing on it finishes the welcome, and
 *    the caller drops this screen away over a Home that plops in.
 *
 * Shown once — to new installs and, because the flag it sets predates this
 * shape, to anyone updating from a build without it. Always drawn dark,
 * whatever the theme, for the same reason Replay is: the mesh is colour,
 * and white type is what sits on colour.
 */
@Composable
fun OnboardingScreen(
    signedIn: Boolean,
    initialName: String,
    initialTitle: String,
    /** Saves the greeting name (title + call name). Called as page 1 is left. */
    onCallNameChosen: (title: String, name: String) -> Unit,
    /** Opens the Google sign-in. The caller finishes onboarding once it lands. */
    onSignIn: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var tosAgreed by rememberSaveable { mutableStateOf(false) }
    var signInRequested by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = PAGE_NAME) { PAGE_COUNT }
    val page = pagerState.currentPage

    // The swipe up is the flow's spine, so each page decides whether it may
    // be left that way: page 1 wants a name, page 2 wants the agreement.
    // Buttons call the same gates and then scroll the pager themselves.
    val canSwipe = when (page) {
        PAGE_NAME -> name.isNotBlank()
        PAGE_TERMS -> tosAgreed
        else -> true
    }

    fun goTo(target: Int) {
        focusManager.clearFocus()
        scope.launch { pagerState.animateScrollToPage(target) }
    }

    BackHandler(enabled = page in PAGE_TERMS..PAGE_CONNECT) {
        goTo(page - 1)
    }
    // "Sign in with Google" goes through the app's own sign-in WebView, raised
    // over this page; the account landing is what ends the welcome — but only
    // a landing that was asked for from here, so someone who was already
    // signed in still gets their turn on the import page.
    LaunchedEffect(signedIn) {
        if (signedIn && signInRequested) onFinish()
    }
    // The bridge page is a threshold, not a place to stay: landing on it
    // finishes, and the caller's slide-away + Home's plop carry the motion.
    LaunchedEffect(page) {
        if (page == PAGE_ENTER) {
            delay(BRIDGE_HOLD_MS)
            onFinish()
        }
    }

    // The mesh never settles on one palette: each crossfade is as long as the
    // hold, so one runs straight into the next.
    val palette = cyclingMeshPalette(
        when (page) {
            PAGE_NAME -> WelcomeMeshPalettes
            PAGE_TERMS -> TermsPalettes
            PAGE_CONNECT -> ConnectPalettes
            else -> WelcomeMeshPalettes
        },
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallows every touch the pages don't, so nothing reaches the app
            // laid out underneath — and a tap on the backdrop puts the keyboard
            // away, which is where a thumb goes to dismiss one.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = page,
            continuous = true,
            driftMillis = 6_000,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        // The mesh carries its own light scrim for a player's controls; two
        // full-width buttons and a paragraph want the foot of it a shade deeper.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    ),
                ),
        )

        VerticalPager(
            state = pagerState,
            userScrollEnabled = canSwipe,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) { shown ->
            OnboardingPage {
                when (shown) {
                    PAGE_NAME -> NamePage(
                        title = title,
                        onTitleChange = { title = it },
                        name = name,
                        onNameChange = { name = it.take(NAME_LIMIT) },
                        onNext = next@{
                            val chosen = name.trim()
                            if (chosen.isEmpty()) return@next
                            onCallNameChosen(title, chosen)
                            goTo(PAGE_TERMS)
                        },
                    )
                    PAGE_TERMS -> TermsPage(
                        agreed = tosAgreed,
                        onAgreedChange = { tosAgreed = it },
                        onNext = { if (tosAgreed) goTo(PAGE_CONNECT) },
                    )
                    PAGE_CONNECT -> ConnectPage(
                        signedIn = signedIn,
                        onSignIn = {
                            signInRequested = true
                            onSignIn()
                        },
                        onEnter = { goTo(PAGE_ENTER) },
                    )
                    else -> EnterPage(
                        greetingName = listOf(title, name.trim())
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                    )
                }
            }
        }

        // Where in the four swipes you are: a slim column of dots at the
        // trailing edge, the current one stretched.
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until PAGE_COUNT) {
                Box(
                    Modifier
                        .size(width = 5.dp, height = if (i == page) 18.dp else 5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == page) 0.9f else 0.35f)),
                )
            }
        }
    }
}

/** The column every page is laid out in, capped so a tablet doesn't stretch it. */
@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = 28.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.NamePage(
    title: String,
    onTitleChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    // With the keyboard up there is room for the title or the logo, not both;
    // the title is the one that says where you are.
    val keyboardUp = WindowInsets.isImeVisible
    AnimatedVisibility(
        visible = !keyboardUp,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Spacer(Modifier.height(56.dp))
            Image(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    Title(stringResource(R.string.onboarding_title))

    Spacer(Modifier.weight(1f))

    Text(
        text = stringResource(R.string.onboarding_name_prompt),
        style = MaterialTheme.typography.titleMedium,
        fontSize = 17.sp,
        color = Color.White.copy(alpha = 0.78f),
        modifier = Modifier.padding(start = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TitleChip(
            label = stringResource(R.string.call_title_miss),
            selected = title == stringResource(R.string.call_title_miss),
            onClick = { onTitleChange("Miss") },
        )
        TitleChip(
            label = stringResource(R.string.call_title_mr),
            selected = title == stringResource(R.string.call_title_mr),
            onClick = { onTitleChange("Mr.") },
        )
    }
    Spacer(Modifier.height(10.dp))
    NameField(value = name, onValueChange = onNameChange, onDone = onNext)
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.onboarding_name_footer),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(28.dp))
    PillButton(
        label = stringResource(R.string.onboarding_next),
        onClick = onNext,
        enabled = name.isNotBlank(),
        primary = true,
    )
    Spacer(Modifier.height(10.dp))
    SwipeHint()
    Spacer(Modifier.height(6.dp))
}

/** One of the Miss / Mr. choices: a capsule that fills in when it's the answer. */
@Composable
private fun TitleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
            color = if (selected) Color.Black else Color.White,
        )
    }
}

@Composable
private fun ColumnScope.TermsPage(
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    Spacer(Modifier.height(56.dp))
    Title(stringResource(R.string.onboarding_tos_title))
    Spacer(Modifier.height(18.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_tos_body),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.5.sp,
            lineHeight = 20.sp,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable { onAgreedChange(!agreed) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = agreed,
            onCheckedChange = onAgreedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color.White,
                uncheckedColor = Color.White.copy(alpha = 0.6f),
                checkmarkColor = Color.Black,
            ),
        )
        Text(
            text = stringResource(R.string.onboarding_tos_agree),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.5.sp,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
    Spacer(Modifier.height(14.dp))
    PillButton(
        label = stringResource(R.string.onboarding_next),
        onClick = onNext,
        enabled = agreed,
        primary = true,
    )
    Spacer(Modifier.height(10.dp))
    SwipeHint()
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ColumnScope.ConnectPage(
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onEnter: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The playlist an import just made, while the download-all question is up.
    var imported by remember { mutableStateOf<LocalPlaylist?>(null) }

    fun onImported(playlist: LocalPlaylist) {
        status = context.getString(R.string.onboarding_import_done, playlist.title, playlist.songs.size)
        failed = false
        imported = playlist
    }

    fun importUrl() {
        if (working || link.isBlank()) return
        working = true
        failed = false
        status = context.getString(R.string.onboarding_import_working)
        scope.launch {
            PlaylistUrlImporter.import(link)
                .onSuccess { result ->
                    onImported(LocalPlaylistStore.savePlaylist(result.title, result.songs))
                }
                .onFailure { error ->
                    status = context.getString(
                        R.string.onboarding_import_failed,
                        error.message.orEmpty(),
                    )
                    failed = true
                }
            working = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || working) return@rememberLauncherForActivityResult
        working = true
        failed = false
        status = context.getString(R.string.onboarding_import_working)
        scope.launch {
            NeveraPlaylistFile.readFrom(context, uri)
                .onSuccess { result ->
                    onImported(LocalPlaylistStore.savePlaylist(result.title, result.songs))
                }
                .onFailure {
                    status = context.getString(R.string.onboarding_file_invalid)
                    failed = true
                    Toast.makeText(
                        context,
                        context.getString(R.string.onboarding_file_invalid),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            working = false
        }
    }

    Spacer(Modifier.height(56.dp))
    Title(stringResource(R.string.onboarding_connect_title))
    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.onboarding_connect_body),
        style = MaterialTheme.typography.bodyLarge,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        color = Color.White.copy(alpha = 0.72f),
    )

    Spacer(Modifier.height(26.dp))
    Text(
        text = stringResource(R.string.onboarding_import_title),
        style = MaterialTheme.typography.titleMedium,
        fontSize = 17.sp,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(start = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.onboarding_import_body),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ImportField(
            value = link,
            onValueChange = {
                link = it
                if (failed) {
                    status = null
                    failed = false
                }
            },
            onDone = { importUrl() },
            enabled = !working,
        )
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.5.sp,
                color = if (failed) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        PillButton(
            label = stringResource(R.string.onboarding_import_button),
            onClick = { importUrl() },
            enabled = link.isNotBlank() && !working,
            primary = false,
        )
        Spacer(Modifier.height(10.dp))
        PillButton(
            label = stringResource(R.string.onboarding_import_file_button),
            onClick = { filePicker.launch(arrayOf("*/*")) },
            enabled = !working,
            primary = false,
        )
    }

    Spacer(Modifier.weight(1f))

    if (!signedIn) {
        PillButton(
            label = stringResource(R.string.onboarding_sign_in_google),
            onClick = onSignIn,
            primary = true,
            leading = {
                Image(
                    painter = painterResource(Res.drawable.ic_google_g),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
    }
    PillButton(
        label = stringResource(R.string.onboarding_enter),
        onClick = onEnter,
        primary = signedIn,
    )
    Spacer(Modifier.height(10.dp))
    SwipeHint()
    Spacer(Modifier.height(6.dp))

    // The question every successful import asks, in the app's own words.
    imported?.let { playlist ->
        AlertDialog(
            onDismissRequest = { imported = null },
            text = {
                Text(
                    text = stringResource(R.string.onboarding_download_all_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlist.songs.forEach { song: Song ->
                            Downloads.enqueue(context, song, playlist.title)
                        }
                        imported = null
                    },
                ) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { imported = null }) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.EnterPage(greetingName: String) {
    Spacer(Modifier.weight(1f))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(Res.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_home),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 36.sp,
                lineHeight = 40.sp,
                letterSpacing = (-1).sp,
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (greetingName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = greetingName,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.onboarding_bridge_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.weight(1f))
}

/** The one-line link field on the import page, in the name field's shape. */
@Composable
private fun ImportField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    enabled: Boolean,
) {
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.5.sp,
        color = Color.White,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_import_url_hint),
                style = style,
                color = Color.White.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Swipe up to continue", centred at the foot of the pages that can be left that way. */
@Composable
private fun SwipeHint() {
    Text(
        text = stringResource(R.string.onboarding_swipe_hint),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.45f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Replay's heading, scaled up: the one line each page exists to say. */
@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = 40.sp,
            lineHeight = 44.sp,
            letterSpacing = (-1.2).sp,
        ),
        color = Color.White,
    )
}

/**
 * The name, set in the heading's own face and weight so what you type reads
 * as part of the page rather than a form on top of it.
 */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    val style = MaterialTheme.typography.displayLarge.copy(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
        color = Color.White,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_name_hint),
                style = style,
                color = Color.White.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A full-width capsule. Primary is white on the mesh, the way Apple Music's
 * own welcome sheet does it; secondary is the same shape in frosted white, so
 * the pair reads as a choice rather than a button and an afterthought.
 */
@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "pillScale")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(180), label = "pillAlpha")
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            color = if (primary) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private const val PAGE_NAME = 0
private const val PAGE_TERMS = 1
private const val PAGE_CONNECT = 2
private const val PAGE_ENTER = 3
private const val PAGE_COUNT = 4

/** How long the bridge page holds before it hands over to Home. */
private const val BRIDGE_HOLD_MS = 650L

/** Long enough for a full name; the party server keeps the first 80. */
private const val NAME_LIMIT = 40

/** The rules page keeps a cooler head than the pages around it. */
private val TermsPalettes = listOf(
    MeshPalette(listOf(Color(0xFF1D4ED8), Color(0xFF0EA5E9), Color(0xFF134E4A), Color(0xFF1E3A8A))),
    MeshPalette(listOf(Color(0xFF0F766E), Color(0xFF2563EB), Color(0xFF164E63), Color(0xFF312E81))),
    MeshPalette(listOf(Color(0xFF0369A1), Color(0xFF0D9488), Color(0xFF1E40AF), Color(0xFF111827))),
)

/** The account page keeps leaning into YouTube's red while it moves. */
private val ConnectPalettes = listOf(
    MeshPalette(listOf(Color(0xFFFF1E3C), Color(0xFFC2185B), Color(0xFFFF8F3D), Color(0xFF4A1C8C))),
    MeshPalette(listOf(Color(0xFFE53935), Color(0xFFFF6E40), Color(0xFFAD1457), Color(0xFF311B92))),
    MeshPalette(listOf(Color(0xFFFF3D00), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFFB71C1C))),
    MeshPalette(listOf(Color(0xFFF50057), Color(0xFFFF9100), Color(0xFFC51162), Color(0xFF4527A0))),
)
