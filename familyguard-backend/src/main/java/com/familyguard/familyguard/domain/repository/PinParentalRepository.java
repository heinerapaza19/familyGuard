package com.familyguard.familyguard.domain.repository;

import com.familyguard.familyguard.domain.model.PinParental;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PinParentalRepository extends JpaRepository<PinParental, Long> {
    Optional<PinParental> findByFamilia_Id(Long familiaId);
}
