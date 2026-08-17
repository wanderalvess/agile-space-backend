package com.agilespace.backend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_availability")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAvailability {

    @Id
    private String id; // Chave estruturada no formato: {userId}_{availability_date}

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String userName;

    private String userRole;

    @Column(nullable = false)
    private String squadId;

    @Column(name = "availability_date", length = 10, nullable = false)
    private String date; // Formato YYYY-MM-DD - Mapeado para availability_date no Postgres

    @Column(length = 50, nullable = false)
    private String type; // full, partial

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 50, nullable = false)
    private String source; // manual, google_calendar

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
