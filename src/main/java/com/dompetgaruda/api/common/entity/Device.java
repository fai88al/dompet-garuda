package com.dompetgaruda.api.common.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "public_key", nullable = false, unique = true)
    private String publicKey;

    @Column(name = "device_label", length = 60)
    private String deviceLabel;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "last_counter", nullable = false)
    private long lastCounter = 0L;

    // SHA-256 hex of the device API token. Plaintext is returned once at registration
    // and never stored. See CLAUDE.md §4 and §7 rule 9.
    @Column(name = "device_token_hash", nullable = false, length = 64, unique = true)
    private String deviceTokenHash;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (deviceId == null) deviceId = UUID.randomUUID();
        Instant now = Instant.now();
        if (registeredAt == null) registeredAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getDeviceLabel() { return deviceLabel; }
    public void setDeviceLabel(String deviceLabel) { this.deviceLabel = deviceLabel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getLastCounter() { return lastCounter; }
    public void setLastCounter(long lastCounter) { this.lastCounter = lastCounter; }
    public String getDeviceTokenHash() { return deviceTokenHash; }
    public void setDeviceTokenHash(String deviceTokenHash) { this.deviceTokenHash = deviceTokenHash; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
