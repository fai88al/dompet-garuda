package com.dompetgaruda.api.auth;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId()            { return id; }
    public String getUsername()    { return username; }
    public String getPasswordHash(){ return passwordHash; }
    public String getRole()        { return role; }
}
