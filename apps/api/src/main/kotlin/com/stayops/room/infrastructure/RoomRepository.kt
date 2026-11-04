package com.stayops.room.infrastructure

import com.stayops.room.domain.Room
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RoomRepository : JpaRepository<Room, UUID>
