package com.dompetgaruda.api.common.repository;

import com.dompetgaruda.api.common.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, String> {
    long countByUserId(UUID userId);
    boolean existsByPublicKey(String publicKey);
    Optional<Device> findByDeviceTokenHash(String deviceTokenHash);
    List<Device> findAllByUserId(UUID userId);
}
