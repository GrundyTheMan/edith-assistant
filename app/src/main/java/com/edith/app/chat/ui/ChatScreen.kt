package com.edith.app.chat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.attachments.FileExport
import com.edith.app.chat.service.ReplyService
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.voice.handsfree.HandsFreeService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Chat tab (C07). */
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        vm.onLocalNetworkPermissionResult(granted)
    }
    // D13: the "Edith is replying…" notification (and its Stop button) is only visible once notifications are allowed.
    // Asked on the first Send of each app run; Android stops showing the prompt after the owner declines twice.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The prompt appears after Send, when the reply's notification was already posted (and dropped): post it again.
        if (granted && vm.state.value.busy) ReplyService.start(context)
    }
    // C11: a tool waiting for a permission (location for "here") gets Android's own dialog, once per request (a
    // rotation mustn't show it twice); the answer goes back to the tool.
    val toolPermission by vm.toolPermissionRequest.collectAsStateWithLifecycle()
    var shownPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val requestToolPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        shownPermissionId?.let { vm.onToolPermissionResult(it, granted) }
    }
    LaunchedEffect(toolPermission?.id) {
        val request = toolPermission ?: return@LaunchedEffect
        if (request.id == shownPermissionId) return@LaunchedEffect
        shownPermissionId = request.id
        requestToolPermission.launch(request.permission)
    }
    fun askForNotificationsOnce() {
        if (askedForNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        askedForNotifications = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // C12: the microphone is asked for on the first Record or dictation tap; a refusal shows why, with Open settings.
    var micAction by rememberSaveable { mutableStateOf<String?>(null) }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = micAction
        micAction = null
        when {
            !granted -> vm.onMicrophoneDenied()
            action == MIC_RECORD -> vm.startRecording()
            action == MIC_DICTATE -> vm.toggleDictation()
            action == MIC_HANDS_FREE -> startHandsFree(context, vm)
        }
    }
    fun withMicrophone(action: String, run: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            run()
        } else {
            micAction = action
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // C15: Android's photo picker needs no permission (on API 37 it's select, then Done); audio files come from the
    // document picker.
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) vm.attachImages(uris.map { it.toString() })
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.attachAudio(it.toString()) }
    }
    // C16: Edith's files. View shows the text; Save asks Android where (CREATE_DOCUMENT); Share sends a copy.
    var viewingFile by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }   // name to path
    viewingFile?.let { (name, path) ->
        val text by produceState<String?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() } }
        FileViewer(name, text ?: "") { viewingFile = null }
    }
    var savingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveFile = rememberLauncherForActivityResult(SaveAs()) { target ->
        val source = savingPath?.let(::File)
        savingPath = null
        if (target != null && source != null) scope.launch { copyTo(context, source, target) }
    }
    val fileActions = FileActions(
        onView = { a -> a.path?.let { viewingFile = a.fileName to it } },
        onSave = { a -> a.path?.let { savingPath = it; saveFile.launch(a.fileName to a.mimeType) } },
        onShare = { a -> a.path?.let { scope.launch { share(context, File(it), a.fileName, a.mimeType) } } },
    )
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    viewing?.let { path -> ImageViewer(path) { viewing = null } }
    // Never leave the microphone open in the background (C12 §2). A rotation isn't leaving.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (context as? Activity)?.isChangingConfigurations != true) vm.onLeftApp()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    ChatContent(
        state = state,
        draft = vm.draft,
        permissionDenied = permissionDenied,
        recordingLevel = { vm.recordingLevel.value },
        actions = ChatActions(
            voice = VoiceActions(
                onRecord = { withMicrophone(MIC_RECORD, vm::startRecording) },
                onStopAndSend = { vm.stopAndSendRecording() },
                onCancelRecording = { vm.cancelRecording() },
                onDictate = { withMicrophone(MIC_DICTATE, vm::toggleDictation) },
            ),
            onOpenAppSettings = openAppSettings,
            onAttachPhoto = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAttachAudio = { pickAudio.launch(arrayOf("audio/*")) },
            onRemoveAttachment = vm::removeAttachment,
            onToggleAttachment = vm::toggleAttachmentPlayback,
            onOpenImage = { viewing = it },
            files = fileActions,
            onHandsFree = {
                if (state.handsFree?.on == true) {
                    vm.stopHandsFree()
                } else {
                    askForNotificationsOnce()   // its notification has Pause and Stop
                    withMicrophone(MIC_HANDS_FREE) { startHandsFree(context, vm) }
                }
            },
            onDraftChange = vm::onDraftChange,
            onSend = { vm.send(); askForNotificationsOnce() },
            onStopReply = vm::stopReply,
            onAnswerConfirmation = vm::answerConfirmation,
            onRegenerate = vm::regenerate,
            onPlay = vm::play,
            onStopPlayback = vm::stopPlayback,
            onDismissError = vm::dismissError,
            onAllowLocalNetwork = {
                if (permissionDenied) {
                    // Android won't ask again after a denial; the owner allows it in the app's settings.
                    openAppSettings()
                } else {
                    requestPermission.launch(LocalNetworkAccess.PERMISSION)
                }
            },
            onNewChat = vm::newChat,
            onSelect = vm::select,
            onRename = vm::rename,
            onDelete = vm::delete,
        ),
    )
}

private const val MIC_RECORD = "record"
private const val MIC_DICTATE = "dictate"
private const val MIC_HANDS_FREE = "hands_free"
/** The photo picker's limit per pick (C15 §1). */
private const val MAX_PHOTOS = 4

/** Hands-free runs in its own service, started from here while the app is on screen (Android's rule, C14 §2). */
private fun startHandsFree(context: Context, vm: ChatViewModel) {
    if (!HandsFreeService.start(context)) vm.onHandsFreeNotStarted("Hands-free couldn't start. Try again.")
}

/** Android's "save as" (CREATE_DOCUMENT) with a file name and type: Save on Edith's file card (C16 §4). */
private class SaveAs : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second.ifEmpty { "text/plain" }).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

/** Save on a file card: the exact bytes where the owner chose. */
private suspend fun copyTo(context: Context, source: File, target: Uri) {
    withContext(Dispatchers.IO) { FileExport.save(context, source, target) }
}

/** Share on a file card: a copy named as Edith named it, through Edith's FileProvider. */
private suspend fun share(context: Context, file: File, name: String, mime: String) {
    val uri = withContext(Dispatchers.IO) { FileExport.shareUri(context, file, name) }
    val send = Intent(Intent.ACTION_SEND).setType(mime.ifEmpty { "text/plain" }).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

