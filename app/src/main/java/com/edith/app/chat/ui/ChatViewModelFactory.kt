package com.edith.app.chat.ui

import com.edith.app.di.viewModelFactory

/** Android's way to build the chat screen's view model from the app's container (the view model itself is shared, K3.2). */
val ChatViewModel.Companion.Factory
    get() = viewModelFactory { container, savedState ->
        ChatViewModel(
            repo = container.chatRepository,
            engine = container.chatEngine,
            current = container.currentConversation,
            speech = container.speechController,
            features = container.chatFeatures,
            clock = container.clock,
            toolLabel = container.toolRegistry::displayName,
            savedState = savedState,
            permissions = container.toolPermissions,
            voiceMemos = container.voiceMemos.takeIf { container.chatFeatures.record },
            dictation = container.dictation.takeIf { container.chatFeatures.dictation },
            handsFree = container.handsFree.takeIf { container.chatFeatures.dictation },   // it needs the recognizer too
            intake = container.attachmentIntake,
            attachmentStore = container.attachmentStore,
            attachmentPlayer = container.attachmentPlayer,
        )
    }
