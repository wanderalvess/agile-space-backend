package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "retro_chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetroChatMessage {

    @Id
    private String id; // UUID gerado pelo cliente ou backend

    @Column(name = "board_id", nullable = false)
    private String boardId;

    @Column(name = "channel_id", nullable = false)
    private String channelId; // 'geral', 'role-{category}', 'dm_{uidA}_{uidB}'

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "sender_name", nullable = false)
    private String senderName;

    private String senderCategory; // Developer, QA, UX, Designer, Management, etc.

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String kind = "text"; // 'text' | 'code'

    @Column(name = "created_at", nullable = false)
    private String ts; // ISO 8601 string
}
