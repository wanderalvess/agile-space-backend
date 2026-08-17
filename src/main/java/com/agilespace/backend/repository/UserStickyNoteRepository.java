package com.agilespace.backend.repository;

import com.agilespace.backend.domain.UserStickyNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserStickyNoteRepository extends JpaRepository<UserStickyNote, String> {
    List<UserStickyNote> findByUserIdOrderByUpdatedAtDesc(String userId);
}
