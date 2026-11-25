package com.singularity.todo.core.di

import com.singularity.todo.feature.ai.chat.persistence.ChatConversationRepository
import com.singularity.todo.feature.ai.chat.persistence.ChatConversationRepositoryImpl
import org.koin.core.module.Module
import org.koin.dsl.module

fun aiChatModule(): Module =
    module {
        single<ChatConversationRepository> {
            ChatConversationRepositoryImpl(
                conversationDao = get(),
                messageDao = get(),
                currentUser = get(),
            )
        }
    }
